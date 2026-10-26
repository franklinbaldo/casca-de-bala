// SPDX-License-Identifier: AGPL-3.0-or-later
package org.workspace.band7;
import java.nio.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Owns crypto + native state + synthetic/future AUTH transport. Single thread;
 * the Android adapter marshals callbacks onto that thread. No GATT IO,
 * credential input, protected operations or time/profile initialization here.
 */
public final class HuamiAuthOwner implements AutoCloseable {
 public interface Transport {
  /** Must copy before return if retaining bytes. Only AUTH payloads, not framing.
   * Callback carries both generation and request ticket; no callback implied by true. */
  boolean write(long generation,long request,byte[] authPayload);
  void close();
 }
 public enum Status { NEW,ACTIVE,PEER_ACCEPTED,CANCELLED,BACKGROUND,REJECTED,FAILED }
 public enum Failure { NONE,CLOCK,TIMEOUT,PROTOCOL,CRYPTO,TRANSPORT,NATIVE }
 private static final AtomicLong GENERATIONS=new AtomicLong(1);
 private final long generation=GENERATIONS.getAndIncrement();private final Thread owner=Thread.currentThread();
 private final HuamiSessionCrypto crypto;private final Transport transport;private final LongSupplier clock;
 private Status status=Status.NEW;private Failure failure=Failure.NONE;private int nativeStatus,stage;
 private long handle,ticketCounter,pending;private boolean pendingProof,finalized;
 private HuamiEncryptedFrames encrypted;private byte[] early;private int earlyEndpoint;
 public HuamiAuthOwner(HuamiSessionCrypto crypto,Transport transport,LongSupplier clock){this.crypto=Objects.requireNonNull(crypto);this.transport=Objects.requireNonNull(transport);this.clock=Objects.requireNonNull(clock);if(generation<=0){crypto.close();transport.close();throw new IllegalStateException("generation exhausted");}}
 public long generation(){return generation;}public Status status(){return status;}public Failure failure(){return failure;}public int nativeStatus(){return nativeStatus;}public int stageCode(){return stage;}
 private void thread(){if(Thread.currentThread()!=owner)throw new IllegalStateException("callback thread");}
 private boolean live(){return !finalized&&status!=Status.NEW;}
 private long now(){long n=clock.getAsLong();if(n<0)throw new IllegalStateException("clock");return n;}
 private static final class Result {
  final int status,stage,ack;final long handle;final byte[] data;
  Result(byte[] raw){if(raw==null||raw.length!=536)throw new IllegalStateException("native result");ByteBuffer b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);status=b.getInt();stage=b.getInt();handle=b.getLong();int len=b.getInt();ack=b.getInt();if(len<0||len>512)throw new IllegalStateException("native length");data=Arrays.copyOfRange(raw,24,24+len);Arrays.fill(raw,(byte)0);}
 }
 private Result step(int op,int endpoint,byte[] bytes){
  long current;try{current=now();}catch(RuntimeException badClock){finish(Status.FAILED,Failure.CLOCK);return null;}
  try {Result r=new Result(NativeBridge.authSession(op,handle,generation,current,endpoint,bytes));nativeStatus=r.status;stage=r.stage;
   if(r.status!=0){Arrays.fill(r.data,(byte)0);finish(Status.FAILED,r.status==2?Failure.CLOCK:r.status==3?Failure.TIMEOUT:Failure.NATIVE);return null;}handle=r.handle;return r;
  }catch(RuntimeException|LinkageError e){finish(Status.FAILED,Failure.NATIVE);return null;}
 }
 public void start(){thread();if(status!=Status.NEW||finalized)throw new IllegalStateException("one-shot");status=Status.ACTIVE;
  byte[] publicKey=null;try {publicKey=crypto.publicKey();Result r=step(0,0,publicKey);if(r!=null)send(r.data,false);}catch(RuntimeException e){finish(Status.FAILED,Failure.CRYPTO);}finally{if(publicKey!=null)Arrays.fill(publicKey,(byte)0);}
 }
 private void send(byte[] command,boolean proof){
  if(!live()){Arrays.fill(command,(byte)0);return;}
  if(pending!=0||!(command.length==52&&command[0]==4||command.length==33&&command[0]==5)){Arrays.fill(command,(byte)0);finish(Status.FAILED,Failure.PROTOCOL);return;}
  long request=++ticketCounter;pending=request;pendingProof=proof;
  try {boolean accepted=transport.write(generation,request,command);if(!accepted&&live()&&pending==request)finish(Status.FAILED,Failure.TRANSPORT);}
  catch(RuntimeException e){finish(Status.FAILED,Failure.TRANSPORT);}finally{Arrays.fill(command,(byte)0);}
 }
 public void writeCompleted(long g,long request,boolean success){thread();if(!live()||g!=generation||request!=pending||pending==0)return;
  boolean proof=pendingProof;pending=0;if(!success){finish(Status.FAILED,Failure.TRANSPORT);return;}
  if(step(4,0,new byte[0])==null)return;
  if(proof&&step(3,0,new byte[0])==null)return;
  if(early!=null){byte[] p=early;int endpoint=earlyEndpoint;early=null;try{process(endpoint,p);}finally{Arrays.fill(p,(byte)0);}}
 }
 public void reply(long g,int endpoint,byte[] payload){thread();if(!live()||g!=generation)return;
  if(payload==null||payload.length>67||endpoint<0||endpoint>65535){finish(Status.FAILED,Failure.PROTOCOL);return;}
  if(step(4,0,new byte[0])==null)return;
  if(pending!=0){if(early!=null){finish(Status.FAILED,Failure.PROTOCOL);return;}early=payload.clone();earlyEndpoint=endpoint;return;}
  process(endpoint,payload);
 }
 private void process(int endpoint,byte[] payload){
  Result r=step(1,endpoint,payload);if(r==null)return;
  try {
   if(r.stage==2&&r.data.length==64){byte[] nonce=Arrays.copyOf(r.data,16),peer=Arrays.copyOfRange(r.data,16,64);
    try(HuamiSessionCrypto.Proof proof=crypto.respond(nonce,peer)) {encrypted=crypto.claimEncryptedFrames(2,HuamiEncryptedFrames.RxPolicy.UPSTREAM_PAYLOAD_ONLY);byte[] p=proof.copyBytes();try {Result command=step(2,0,p);if(command!=null)send(command.data,true);}finally{Arrays.fill(p,(byte)0);}}
    catch(RuntimeException e){finish(Status.FAILED,Failure.CRYPTO);}finally{Arrays.fill(nonce,(byte)0);Arrays.fill(peer,(byte)0);}
   }else if(r.stage==5){status=Status.PEER_ACCEPTED;} // no protected-operation API
   else if(r.stage==6){finish(Status.REJECTED,Failure.NONE);}
   else finish(Status.FAILED,Failure.PROTOCOL);
  }finally{Arrays.fill(r.data,(byte)0);}
 }

 /** Internal AUTH framing only; output is length-prefixed local bridge data. */
 public byte[] encodeAuth(byte[] command){thread();if(!live())return null;Result r=step(7,0,command);return r==null?null:r.data;}
 public static final class Frame {public final byte[] ack,payload;public final int endpoint;Frame(byte[] a,int e,byte[] p){ack=a;endpoint=e;payload=p;}}
 /** Reassembly through the existing Rust receiver; no cipher fallback. */
 public Frame receiveFrame(long g,byte[] frame){thread();if(!live()||g!=generation)return null;Result r=step(6,0,frame);if(r==null)return null;
  byte[] a=(r.ack&0x10000)==0?null:new byte[]{4,0,(byte)(r.ack>>8),1,(byte)r.ack};
  if(r.data.length==0)return new Frame(a,-1,null);if(r.data.length<2){finish(Status.FAILED,Failure.PROTOCOL);return null;}
  int endpoint=(r.data[0]&255)|((r.data[1]&255)<<8);byte[] p=Arrays.copyOfRange(r.data,2,r.data.length);Arrays.fill(r.data,(byte)0);return new Frame(a,endpoint,p);
 }
 /** Source-compatible RX does not check CRC or replay; no protected TX API. */
 public boolean canDecodeEncrypted(){thread();return live()&&encrypted!=null;}
 public Frame receiveEncryptedFrame(long g,byte[] frame){thread();if(!live()||g!=generation)return null;if(encrypted==null){finish(Status.FAILED,Failure.PROTOCOL);return null;}
  if(step(4,0,new byte[0])==null)return null;
  try{HuamiEncryptedFrames.Received r=encrypted.receive(now(),frame);return new Frame(r.ack,r.endpoint,r.payload);}catch(RuntimeException rejected){finish(Status.FAILED,Failure.PROTOCOL);return null;}
 }
 /** Explicit read-only query only; never called as an AUTH side effect. */
 java.util.List<byte[]> encodeReadOnlyQuery(int endpoint,boolean cipher){thread();
  if(status!=Status.PEER_ACCEPTED||!live()||encrypted==null||!(endpoint==0||endpoint==0x29)||endpoint==0&&cipher)throw new IllegalStateException("query gate");
  if(step(4,0,new byte[0])==null)throw new IllegalStateException("session expired");
  return cipher?encrypted.encode(endpoint,23,new byte[]{3}):encrypted.encodePlainQuery(endpoint,new byte[]{3});
 }
 public void tick(){thread();if(live()){if(encrypted!=null){try{encrypted.tick(now());}catch(RuntimeException rejected){finish(Status.FAILED,Failure.PROTOCOL);return;}}step(4,0,new byte[0]);}}
 public void disconnected(){thread();if(live())finish(Status.FAILED,Failure.TRANSPORT);}
 /** Idempotent owner-thread cancellation. NEW/ACTIVE/PEER_ACCEPTED are live or
  * unstarted and become CANCELLED with resources closed. An already finalized
  * CANCELLED/BACKGROUND/REJECTED/FAILED keeps its original status/cause; cancel
  * never erases an earlier error. PEER_ACCEPTED alone is not finalization. */
 public void cancel(){thread();finish(Status.CANCELLED,Failure.NONE);}
 public void background(){thread();finish(Status.BACKGROUND,Failure.NONE);}
 @Override public void close(){cancel();}
 private void finish(Status end,Failure why){
  if(finalized)return;finalized=true;status=end;failure=why;pending=0;stage=6;
  if(early!=null){Arrays.fill(early,(byte)0);early=null;}
  if(handle!=0){try{NativeBridge.authSession(5,handle,generation,0,0,new byte[0]);}catch(RuntimeException|LinkageError ignored){}finally{handle=0;}}
  if(encrypted!=null){encrypted.close();encrypted=null;}
  try{crypto.close();}finally{try{transport.close();}catch(RuntimeException ignored){}}
 }
}

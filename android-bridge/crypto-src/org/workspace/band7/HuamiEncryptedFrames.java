// SPDX-License-Identifier: AGPL-3.0-or-later
package org.workspace.band7;
import java.util.*;import java.util.zip.CRC32;
/** Extended encrypted2021 framing, pinned Gadgetbridge a0948ee1. No IO or auth
 * permission. AES-ECB + CRC32 is NOT AEAD. RX source only trims payload; stronger
 * TX-layout checks are explicit experimental policy, not proven Band RX format.
 */
public final class HuamiEncryptedFrames implements AutoCloseable {
 public enum RxPolicy { UPSTREAM_PAYLOAD_ONLY, REQUIRE_TX_LAYOUT }
 public static final class Received {
  public final int endpoint;public final byte[] payload,ack;public final Long sequence;
  public final boolean crcChecked;
  private Received(int e,byte[] p,byte[] a,Long s,boolean c){endpoint=e;payload=p;ack=a;sequence=s;crcChecked=c;}
 }
 private final HuamiSessionCrypto crypto;private final RxPolicy policy;private final Thread thread=Thread.currentThread();
 private long txSequence,lastTime=-1,deadline;private Long lastRx;private int handle,rxHandle,rxNext,rxLength,rxEndpoint,used;private byte[] partial;private boolean closed;
 HuamiEncryptedFrames(HuamiSessionCrypto crypto,int lastHandle,long initialSequence,RxPolicy policy){
  if(lastHandle<0||lastHandle>255||initialSequence<0||initialSequence>0xffffffffL)throw new IllegalArgumentException("counter");
  this.crypto=Objects.requireNonNull(crypto);this.policy=Objects.requireNonNull(policy);handle=lastHandle;txSequence=initialSequence;
 }
 private void live(){if(Thread.currentThread()!=thread)throw new IllegalStateException("thread");if(closed||crypto.isClosed())throw new IllegalStateException("closed");}
 private static int padded(int n){return (n+8+15)&~15;}
 private static long u32(byte[] b,int p){return (b[p]&255L)|((b[p+1]&255L)<<8)|((b[p+2]&255L)<<16)|((b[p+3]&255L)<<24);}
 private static void put(byte[] b,int p,long n){for(int i=0;i<4;i++)b[p+i]=(byte)(n>>(8*i));}
 private static long crc(byte[] b,int n){CRC32 c=new CRC32();c.update(b,0,n);return c.getValue();}
 private IllegalArgumentException invalid(){close();return new IllegalArgumentException("encrypted frame rejected");}
 /** Caller owns returned frames. A failed transport must close this codec; never
  * re-encrypt a retry with reused handle/counter. Handle exhaustion stops early
  * rather than wrapping the XOR message key, unlike upstream byte wrap.
  */
 public List<byte[]> encode(int endpoint,int mtu,byte[] payload){
  live();if(endpoint<0||endpoint>65535||mtu<23||mtu>515||payload==null||payload.length==0||payload.length>4096)throw invalid();
  if(handle==255||txSequence>0xffffffffL)throw invalid();int size=padded(payload.length),first=mtu-14,next=mtu-8;
  if(1+(Math.max(0,size-first)+next-1)/next>256)throw invalid();
  byte[] plain=new byte[size],cipher=null;System.arraycopy(payload,0,plain,0,payload.length);put(plain,payload.length,txSequence);put(plain,payload.length+4,crc(plain,payload.length+4));
  try{cipher=crypto.cryptMessage(handle+1,plain,true);List<byte[]> frames=new ArrayList<>();int at=0,seq=0;
   while(at<cipher.length){int header=seq==0?11:5,n=Math.min(mtu-3-header,cipher.length-at);byte[] f=new byte[header+n];f[0]=3;f[1]=(byte)(8|(seq==0?1:0)|(at+n==cipher.length?6:0));f[3]=(byte)(handle+1);f[4]=(byte)seq;
    if(seq==0){put(f,5,payload.length);f[9]=(byte)endpoint;f[10]=(byte)(endpoint>>8);}System.arraycopy(cipher,at,f,header,n);frames.add(f);at+=n;seq++;
   }handle++;txSequence++;return frames;
  }catch(RuntimeException failed){close();throw failed;}finally{Arrays.fill(plain,(byte)0);if(cipher!=null)Arrays.fill(cipher,(byte)0);}
 }
 /** Narrow read-only plaintext path shares TX handle allocation with cipher frames. */
 public List<byte[]> encodePlainQuery(int endpoint,byte[] payload){
  live();if(handle==255)throw invalid();byte[] encoded=NativeBridge.zepp(4,endpoint,handle+1,payload);if(encoded==null)throw invalid();
  List<byte[]> out=new ArrayList<>();int at=0;while(at<encoded.length){int n=(encoded[at]&255)|((encoded[at+1]&255)<<8);at+=2;if(n<5||n>20||at+n>encoded.length)throw invalid();out.add(Arrays.copyOfRange(encoded,at,at+n));at+=n;}handle++;return out;
 }
 public void tick(long now){live();if(now<0||now<lastTime||(partial!=null&&now>=deadline))throw invalid();lastTime=now;}
 /** Strict ordering/bounds and consistency are local checks. Under source policy
  * no RX CRC or replay validation is claimed, exactly as the pinned decoder.
  */
 public Received receive(long now,byte[] f){
  tick(now);if(f==null||f.length<6||f.length>512||f[0]!=3||(f[1]&8)==0||(f[1]&~15)!=0||f[2]!=0)throw invalid();
  int flags=f[1]&255,h=f[3]&255,seq=f[4]&255,offset=(flags&1)!=0?11:5;boolean first=(flags&1)!=0,last=(flags&2)!=0;
  if(f.length<=offset)throw invalid();
  if(first){if(partial!=null||seq!=0||now>Long.MAX_VALUE-5000)throw invalid();long n=u32(f,5);if(n==0||n>4096)throw invalid();rxLength=(int)n;partial=new byte[padded(rxLength)];used=0;rxNext=0;rxHandle=h;rxEndpoint=(f[9]&255)|((f[10]&255)<<8);deadline=now+5000;}
  if(partial==null||h!=rxHandle||seq!=rxNext||used+f.length-offset>partial.length)throw invalid();
  System.arraycopy(f,offset,partial,used,f.length-offset);used+=f.length-offset;rxNext++;
  if(last?used!=partial.length:used==partial.length||seq==255)throw invalid();
  byte[] ack=(flags&4)==0?null:new byte[]{4,0,(byte)h,1,(byte)seq};if(!last)return new Received(-1,null,ack,null,false);
  byte[] plain=null;try{plain=crypto.cryptMessage(h,partial,false);Long sequence=null;boolean checked=false;
   if(policy==RxPolicy.REQUIRE_TX_LAYOUT){sequence=u32(plain,rxLength);if(u32(plain,rxLength+4)!=crc(plain,rxLength+4)||(lastRx!=null&&sequence<=lastRx))throw invalid();
    for(int i=rxLength+8;i<plain.length;i++)if(plain[i]!=0)throw invalid();checked=true;lastRx=sequence;
   }
   return new Received(rxEndpoint,Arrays.copyOf(plain,rxLength),ack,sequence,checked);
  }catch(RuntimeException failed){close();throw failed;}finally{if(plain!=null)Arrays.fill(plain,(byte)0);clearPartial();}
 }
 private void clearPartial(){if(partial!=null)Arrays.fill(partial,(byte)0);partial=null;used=rxNext=0;}
 /** Does not close the external session owner. All further use is rejected. */
 @Override public void close(){closed=true;clearPartial();lastRx=null;}
}

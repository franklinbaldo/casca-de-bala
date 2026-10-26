// SPDX-License-Identifier: AGPL-3.0-or-later
package org.workspace.band7;
import java.util.*;
import java.util.function.LongSupplier;
/** Serial AUTH-fragment/ACK pump. Port is simulated in host/ART fixtures; a
 * separate Android adapter owns actual GATT. No service initialization/mutations.
 */
public final class HuamiAuthTransport implements HuamiAuthOwner.Transport {
 public static final int TX=0,ACK=1;
 public interface Port {boolean write(long generation,long ticket,int target,byte[] frame);void close();}
 public enum Reason { NONE,CIPHER_UNSUPPORTED,OVERFLOW,BAD_FRAME,WRITE_FAILED,WRITE_TIMEOUT,CLOSED }
 private final Port port;private final LongSupplier clock;private HuamiAuthOwner owner;
 private final ArrayDeque<byte[]> acknowledgements=new ArrayDeque<>(),frames=new ArrayDeque<>();
 private ReadOnlyBatteryQuery query;private boolean queryWrite;
 private boolean closed;private Reason reason=Reason.NONE;private long logical,physicalCounter,pending,pendingAt;private int pendingTarget;
 public HuamiAuthTransport(Port port,LongSupplier clock){this.port=Objects.requireNonNull(port);this.clock=Objects.requireNonNull(clock);}
 public void bind(HuamiAuthOwner value){if(owner!=null||closed)throw new IllegalStateException("one owner");owner=Objects.requireNonNull(value);}
 void bindQuery(ReadOnlyBatteryQuery value){if(query!=null)throw new IllegalStateException("one query");query=Objects.requireNonNull(value);}
 boolean idle(){return !closed&&pending==0&&logical==0&&frames.isEmpty()&&acknowledgements.isEmpty();}
 boolean sendQuery(int endpoint,boolean cipher){if(closed||logical!=0||queryWrite||!frames.isEmpty()||(pending!=0&&pendingTarget==TX)||query==null)return false;try{List<byte[]> packets=owner.encodeReadOnlyQuery(endpoint,cipher);if(packets.isEmpty()||packets.size()>8)throw new IllegalArgumentException();queryWrite=true;for(byte[] f:packets){if(f.length>20)throw new IllegalArgumentException();frames.add(f);}dispatch();return !closed;}catch(RuntimeException failed){fail(Reason.BAD_FRAME);return false;}}
 public Reason reason(){return reason;}
 @Override public boolean write(long g,long request,byte[] payload){
  if(closed||owner==null||g!=owner.generation()||logical!=0)return false;
  byte[] encoded=owner.encodeAuth(payload);if(encoded==null||closed)return false;
  try {int offset=0;while(offset<encoded.length){if(offset+2>encoded.length)throw new IllegalArgumentException();int n=(encoded[offset]&255)|((encoded[offset+1]&255)<<8);offset+=2;if(n<5||n>20||offset+n>encoded.length||frames.size()>=8)throw new IllegalArgumentException();frames.add(Arrays.copyOfRange(encoded,offset,offset+n));offset+=n;}if(frames.isEmpty())throw new IllegalArgumentException();logical=request;dispatch();return !closed;}
  catch(IllegalArgumentException invalid){fail(Reason.BAD_FRAME);return false;}finally{Arrays.fill(encoded,(byte)0);}
 }
 private void dispatch(){
  if(closed||pending!=0)return;
  byte[] bytes;if(!acknowledgements.isEmpty()){pendingTarget=ACK;bytes=acknowledgements.remove();}else if(!frames.isEmpty()){pendingTarget=TX;bytes=frames.remove();}else return;
  long ticket=++physicalCounter;pending=ticket;pendingAt=clock.getAsLong();
  try{boolean accepted=port.write(owner.generation(),ticket,pendingTarget,bytes);if(!accepted&&!closed&&pending==ticket)fail(Reason.WRITE_FAILED);}catch(RuntimeException error){fail(Reason.WRITE_FAILED);}finally{Arrays.fill(bytes,(byte)0);}
 }
 public void completed(long g,long ticket,boolean success){
  if(closed||owner==null||g!=owner.generation()||ticket!=pending||pending==0)return;
  int target=pendingTarget;pending=0;if(!success){fail(Reason.WRITE_FAILED);return;}
  if(target==TX&&frames.isEmpty()){if(queryWrite){queryWrite=false;query.written();}else {long done=logical;logical=0;owner.writeCompleted(g,done,true);}}
  dispatch();
 }
 public void notified(long g,byte[] frame){
  if(closed||owner==null||g!=owner.generation())return;
  if(frame==null||frame.length>512){fail(Reason.BAD_FRAME);return;}
  boolean cipher=frame.length>=2&&frame[0]==3&&(frame[1]&8)!=0;
  if(cipher&&!owner.canDecodeEncrypted()){fail(Reason.CIPHER_UNSUPPORTED);return;}
  HuamiAuthOwner.Frame decoded=cipher?owner.receiveEncryptedFrame(g,frame):owner.receiveFrame(g,frame);if(decoded==null||closed)return;
  if(decoded.ack!=null){if(acknowledgements.size()>=8){fail(Reason.OVERFLOW);return;}acknowledgements.add(decoded.ack.clone());Arrays.fill(decoded.ack,(byte)0);}
  if(decoded.payload!=null){try{if(owner.status()==HuamiAuthOwner.Status.PEER_ACCEPTED&&decoded.endpoint!=0x82){if(query!=null)query.reply(decoded.endpoint,decoded.payload);}else owner.reply(g,decoded.endpoint,decoded.payload);}finally{Arrays.fill(decoded.payload,(byte)0);}}
  dispatch();
 }
 public void tick(){if(closed||owner==null)return;owner.tick();if(query!=null)query.tick();if(!closed&&pending!=0&&clock.getAsLong()-pendingAt>=5000)fail(Reason.WRITE_TIMEOUT);}
 private void fail(Reason why){if(closed)return;reason=why;if(owner!=null)owner.disconnected();close();}
 @Override public void close(){if(closed)return;closed=true;if(query!=null)query.tick();if(reason==Reason.NONE)reason=Reason.CLOSED;pending=logical=0;for(byte[] b:frames)Arrays.fill(b,(byte)0);for(byte[] b:acknowledgements)Arrays.fill(b,(byte)0);frames.clear();acknowledgements.clear();port.close();}
}

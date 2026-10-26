// SPDX-License-Identifier: AGPL-3.0-or-later
package org.workspace.band7;
import java.util.*;import java.util.function.LongSupplier;
/** One explicit read per authenticated connection. Wire has no request ID, so no
 * retry in-place after timeout/cancel; reconnect creates a new generation. Not IO. */
public final class ReadOnlyBatteryQuery {
 public enum State { IDLE,DISCOVERY_WRITE,DISCOVERY_WAIT,BATTERY_WRITE,BATTERY_WAIT,COMPLETE,UNSUPPORTED,TIMEOUT,CANCELLED,ERROR }
 private final HuamiAuthOwner owner;private final HuamiAuthTransport wire;private final LongSupplier clock;private final Thread thread=Thread.currentThread();
 private State state=State.IDLE;private long deadline,lastTime=-1,sampleTime=-1;private byte[] result,early;private int earlyEndpoint;
 public ReadOnlyBatteryQuery(HuamiAuthOwner owner,HuamiAuthTransport wire,LongSupplier clock){this.owner=Objects.requireNonNull(owner);this.wire=Objects.requireNonNull(wire);this.clock=Objects.requireNonNull(clock);wire.bindQuery(this);}
 private void thread(){if(Thread.currentThread()!=thread)throw new IllegalStateException("query thread");}
 public State state(){thread();return state;}public Integer percent(){thread();return result==null?null:result[2]&255;}
 public byte[] validatedPayload(){thread();return result==null?null:result.clone();}public long sampleTime(){thread();return sampleTime;}
 private boolean pending(){return state==State.DISCOVERY_WRITE||state==State.DISCOVERY_WAIT||state==State.BATTERY_WRITE||state==State.BATTERY_WAIT;}
 private long now(){long t=clock.getAsLong();if(t<0||t<lastTime)throw new IllegalStateException("query clock");lastTime=t;return t;}
 public boolean canRequest(){thread();return state==State.IDLE&&owner.status()==HuamiAuthOwner.Status.PEER_ACCEPTED&&wire.idle();}
 public boolean request(){thread();if(!canRequest())return false;
  try{long t=now();if(t>Long.MAX_VALUE-5000)throw new IllegalStateException();deadline=t+5000;state=State.DISCOVERY_WRITE;if(!wire.sendQuery(0,false))throw new IllegalStateException();return true;}catch(RuntimeException failed){fail(State.ERROR);return false;}
 }
 void written(){thread();if(state==State.DISCOVERY_WRITE)state=State.DISCOVERY_WAIT;else if(state==State.BATTERY_WRITE)state=State.BATTERY_WAIT;else return;
  if(early!=null){byte[] p=early;int e=earlyEndpoint;early=null;try{reply(e,p);}finally{Arrays.fill(p,(byte)0);}}
 }
 void reply(int endpoint,byte[] p){thread();if(!pending())return;int wanted=state==State.DISCOVERY_WRITE||state==State.DISCOVERY_WAIT?0:0x29;if(endpoint!=wanted)return;
  try{if(p==null||p.length>512)throw new IllegalArgumentException();if(now()>=deadline){fail(State.TIMEOUT);return;}
   if(state==State.DISCOVERY_WRITE||state==State.BATTERY_WRITE){if(early!=null)throw new IllegalArgumentException();early=p.clone();earlyEndpoint=endpoint;return;}
   if(state==State.DISCOVERY_WAIT){ServiceListProbe.Encryption cipher=ServiceListProbe.decode(p).get(0x29);if(cipher==null||cipher==ServiceListProbe.Encryption.UNKNOWN){state=State.UNSUPPORTED;return;}
    state=State.BATTERY_WRITE;deadline=now()+5000;if(!wire.sendQuery(0x29,cipher==ServiceListProbe.Encryption.ENCRYPTED))throw new IllegalStateException();
   }else {byte[] checked=NativeBridge.zepp(0,0,0,p);if(checked==null)throw new IllegalArgumentException();result=checked;sampleTime=now();state=State.COMPLETE;}
  }catch(RuntimeException failed){fail(State.ERROR);}
 }
 void tick(){thread();if(!pending())return;HuamiAuthOwner.Status auth=owner.status();if(auth!=HuamiAuthOwner.Status.PEER_ACCEPTED){fail(auth==HuamiAuthOwner.Status.CANCELLED||auth==HuamiAuthOwner.Status.BACKGROUND?State.CANCELLED:State.ERROR);return;}try{if(now()>=deadline)fail(State.TIMEOUT);}catch(RuntimeException badClock){fail(State.ERROR);}}
 public void cancel(){thread();if(state==State.COMPLETE||state==State.UNSUPPORTED||state==State.TIMEOUT||state==State.ERROR||state==State.CANCELLED)return;fail(State.CANCELLED);}
 private void fail(State end){state=end;if(early!=null)Arrays.fill(early,(byte)0);early=null;owner.cancel();}
}

// SPDX-License-Identifier: AGPL-3.0-or-later
package org.workspace.band7;
import java.util.*;import java.nio.file.*;import java.util.concurrent.atomic.AtomicLong;
/** Synthetic peer; real public-vector crypto/framing, JNI and Rust decoder. */
public final class BatteryQueryTest {
 static void check(boolean ok){if(!ok)throw new AssertionError("battery query contract");}
 static final class Attempt implements AutoCloseable {
  final AtomicLong time=new AtomicLong();final FramingTransportTest.Port port=new FramingTransportTest.Port();
  final HuamiAuthTransport wire=new HuamiAuthTransport(port,time::get);final HuamiAuthOwner owner;
  final ReadOnlyBatteryQuery query;final HuamiSessionCrypto peerCrypto;final HuamiEncryptedFrames peer;int completed;
  Attempt(List<String> v){owner=new HuamiAuthOwner(HuamiCryptoTest.fixed(v),wire,time::get);wire.bind(owner);query=new ReadOnlyBatteryQuery(owner,wire,time::get);peerCrypto=EncryptedFramesTest.ready(v);peer=peerCrypto.claimEncryptedFrames(20,HuamiEncryptedFrames.RxPolicy.UPSTREAM_PAYLOAD_ONLY);}
  void drain(){int guard=0;while(completed<port.tickets.size()){if(++guard>100)throw new AssertionError("queue");wire.completed(owner.generation(),port.tickets.get(completed++),true);}}
  void reply(int endpoint,byte[] payload){for(byte[] f:peer.encode(endpoint,23,payload))wire.notified(owner.generation(),f);drain();}
  void ready(List<String> v){owner.start();drain();byte[] p=new byte[67];p[0]=16;p[1]=4;p[2]=1;System.arraycopy(HuamiCryptoTest.point(v.get(4),v.get(5)),0,p,19,48);for(byte[] f:FramingTransportTest.chunks(p,11))wire.notified(owner.generation(),f);drain();for(byte[] f:peer.encode(0x82,23,new byte[]{16,5,1}))wire.notified(owner.generation(),f);check(owner.status()==HuamiAuthOwner.Status.PEER_ACCEPTED&&!query.canRequest());drain();check(query.canRequest());}
  void discover(int flag){check(query.request());drain();reply(0,new byte[]{4,1,0,0x29,0,(byte)flag});}
  public void close(){owner.cancel();peer.close();peerCrypto.close();}
 }
 static byte[] battery(){byte[] b=new byte[21];b[0]=4;b[2]=74;b[20]=100;return b;}
 public static void main(String[] args)throws Exception{run(Files.readAllLines(Paths.get(args[0])));}
 public static void run(List<String> v){
  try(Attempt a=new Attempt(v)){check(!a.query.request()&&a.port.data.isEmpty());a.ready(v);int before=a.port.data.size();check(a.query.state()==ReadOnlyBatteryQuery.State.IDLE&&a.port.data.size()==before);a.discover(1);check(a.query.state()==ReadOnlyBatteryQuery.State.BATTERY_WAIT);a.reply(0x1e,new byte[]{2,4});check(a.query.state()==ReadOnlyBatteryQuery.State.BATTERY_WAIT);a.reply(0x29,battery());check(a.query.state()==ReadOnlyBatteryQuery.State.COMPLETE&&a.query.percent()==74&&a.query.sampleTime()==a.time.get());int n=a.port.data.size();a.reply(0x29,battery());check(a.query.percent()==74&&!a.query.request());check(a.port.data.size()>=n);}
  for(int flag:new int[]{0,1,9})try(Attempt a=new Attempt(v)){a.ready(v);a.discover(flag);if(flag==9)check(a.query.state()==ReadOnlyBatteryQuery.State.UNSUPPORTED);else {a.reply(0x29,battery());check(a.query.percent()==74);}}
  try(Attempt a=new Attempt(v)){a.ready(v);a.discover(1);a.time.set(5000);a.wire.tick();check(a.query.state()==ReadOnlyBatteryQuery.State.TIMEOUT&&a.owner.status()==HuamiAuthOwner.Status.CANCELLED);a.reply(0x29,battery());check(a.query.percent()==null);}
  try(Attempt a=new Attempt(v)){a.ready(v);a.discover(1);a.query.cancel();a.query.cancel();a.reply(0x29,battery());check(a.query.state()==ReadOnlyBatteryQuery.State.CANCELLED&&a.query.percent()==null&&a.port.closes==1);}
  try(Attempt a=new Attempt(v)){a.ready(v);a.discover(1);a.reply(0x29,new byte[]{4,0,74});check(a.query.state()==ReadOnlyBatteryQuery.State.ERROR&&a.query.percent()==null);}
  try(Attempt a=new Attempt(v)){a.ready(v);check(a.query.request());a.drain();a.reply(0,new byte[]{4,0,0});check(a.query.state()==ReadOnlyBatteryQuery.State.UNSUPPORTED);}
  try(Attempt old=new Attempt(v);Attempt fresh=new Attempt(v)){old.ready(v);old.discover(1);old.query.cancel();fresh.ready(v);fresh.discover(1);for(byte[] f:old.peer.encode(0x29,23,battery()))fresh.wire.notified(old.owner.generation(),f);check(fresh.query.percent()==null);fresh.reply(0x29,battery());check(fresh.query.percent()==74);}
  check(NativeBridge.zepp(0,0,0,battery())[2]==74);check(NativeBridge.zepp(0,0,0,new byte[]{4})==null);
  check(NativeBridge.zepp(1,0,0,new byte[]{4})[0]==4);check(NativeBridge.zepp(2,0,0,new byte[]{2,4,0,0,0,0,0,0})[1]==4);
  System.out.println("PASS battery explicit action -> service discovery -> cipher choice -> synthetic encrypted reply -> JNI/Rust; wrong endpoint, duplicate, timeout, cancel, malformed, unsupported, stale generation/reconnect; find/notification native decoders. No hardware interoperability proof.");
 }
}

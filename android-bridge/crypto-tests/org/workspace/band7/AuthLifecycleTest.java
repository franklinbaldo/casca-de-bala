package org.workspace.band7;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
/** Transport is synthetic. Crypto/JNI/Rust are real and use public fixtures. */
public final class AuthLifecycleTest {
 static void check(boolean b){if(!b)throw new AssertionError("lifecycle contract");}
 static final class Wire implements HuamiAuthOwner.Transport {
  final List<byte[]> packets=new ArrayList<>();final List<Long> tickets=new ArrayList<>();int closes;boolean reject,fail,reentrant,cancelInside;HuamiAuthOwner owner;
  public boolean write(long generation,long ticket,byte[] bytes){if(fail)throw new IllegalStateException("synthetic transport failure");packets.add(bytes.clone());tickets.add(ticket);if(cancelInside)owner.cancel();else if(reentrant&&packets.size()==1){owner.writeCompleted(generation,ticket,true);owner.reply(generation,0x82,challenge);}return !reject;}
  public void close(){closes++;}
 }
 static List<String> vector;static byte[] challenge;
 static final class Attempt {
  final AtomicLong time=new AtomicLong();final Wire wire=new Wire();final HuamiSessionCrypto crypto=HuamiCryptoTest.fixed(vector);
  final HuamiAuthOwner owner=new HuamiAuthOwner(crypto,wire,time::get);
  Attempt(){wire.owner=owner;}
  long g(){return owner.generation();}long ticket(){return wire.tickets.get(wire.tickets.size()-1);}
  void ack(){owner.writeCompleted(g(),ticket(),true);}
  void reply(byte[] p){owner.reply(g(),0x82,p);}
  void ready(){owner.start();ack();reply(challenge);ack();}
  void cancel(){owner.cancel();check(crypto.isClosed()&&wire.closes==1&&owner.stageCode()==6);}
 }
 public static void main(String[] args)throws Exception{
  vector=Files.readAllLines(Paths.get(args[0]));challenge=new byte[67];challenge[0]=0x10;challenge[1]=4;challenge[2]=1;
  System.arraycopy(HuamiCryptoTest.point(vector.get(4),vector.get(5)),0,challenge,19,48);
  Attempt a=new Attempt();a.ready();a.reply(new byte[]{0x10,5,1});check(a.owner.status()==HuamiAuthOwner.Status.PEER_ACCEPTED);check(!a.crypto.isClosed());check(a.wire.packets.size()==2&&a.wire.packets.get(0).length==52&&a.wire.packets.get(1).length==33);a.cancel();a.owner.cancel();check(a.wire.closes==1);
  Attempt cancelled=new Attempt();cancelled.owner.start();long old=cancelled.g(),oldTicket=cancelled.ticket();cancelled.cancel();cancelled.owner.writeCompleted(old,oldTicket,true);cancelled.owner.reply(old,0x82,challenge);check(cancelled.wire.packets.size()==1);
  Attempt fresh=new Attempt();fresh.owner.start();fresh.owner.reply(old,0x82,new byte[1000]);fresh.owner.writeCompleted(old,oldTicket,false);check(!fresh.crypto.isClosed()&&fresh.wire.packets.size()==1);fresh.cancel();
  Attempt timeout=new Attempt();timeout.owner.start();timeout.time.set(15000);timeout.owner.tick();check(timeout.owner.status()==HuamiAuthOwner.Status.FAILED&&timeout.crypto.isClosed()&&timeout.wire.closes==1&&timeout.owner.failure()==HuamiAuthOwner.Failure.TIMEOUT);
  Attempt clock=new Attempt();clock.owner.start();clock.time.set(-1);clock.owner.tick();check(clock.crypto.isClosed());
  Attempt rejected=new Attempt();rejected.wire.reject=true;rejected.owner.start();check(rejected.crypto.isClosed()&&rejected.wire.closes==1);
  Attempt thrown=new Attempt();thrown.wire.fail=true;thrown.owner.start();check(thrown.crypto.isClosed()&&thrown.wire.closes==1);
  Attempt failed=new Attempt();failed.owner.start();failed.owner.writeCompleted(failed.g(),failed.ticket(),false);check(failed.crypto.isClosed());
  Attempt invalid=new Attempt();invalid.owner.start();invalid.ack();byte[] wrong=challenge.clone();Arrays.fill(wrong,19,67,(byte)0);invalid.reply(wrong);check(invalid.crypto.isClosed()&&invalid.wire.packets.size()==1);
  Attempt premature=new Attempt();premature.owner.start();premature.reply(new byte[]{0x10,5,1});premature.ack();check(premature.owner.status()==HuamiAuthOwner.Status.FAILED&&premature.crypto.isClosed());
  Attempt early=new Attempt();early.owner.start();early.reply(challenge);check(early.wire.packets.size()==1);early.ack();check(early.wire.packets.size()==2);early.reply(new byte[]{0x10,5,1});check(early.owner.status()!=HuamiAuthOwner.Status.PEER_ACCEPTED);early.ack();check(early.owner.status()==HuamiAuthOwner.Status.PEER_ACCEPTED);early.owner.background();check(early.crypto.isClosed());
  Attempt duplicate=new Attempt();duplicate.owner.start();duplicate.reply(challenge);duplicate.reply(challenge);check(duplicate.crypto.isClosed());
  Attempt callbacks=new Attempt();callbacks.owner.start();long firstTicket=callbacks.ticket();callbacks.ack();callbacks.reply(challenge);callbacks.owner.writeCompleted(callbacks.g(),firstTicket,false);check(!callbacks.crypto.isClosed());callbacks.ack();callbacks.reply(new byte[]{0x10,5,0x25});check(callbacks.crypto.isClosed()&&callbacks.owner.status()==HuamiAuthOwner.Status.REJECTED);
  Attempt bg=new Attempt();bg.owner.start();bg.owner.background();check(bg.crypto.isClosed()&&bg.wire.closes==1);
  Attempt again=new Attempt();again.owner.start();boolean oneShot=false;try{again.owner.start();}catch(IllegalStateException expected){oneShot=true;}check(oneShot&&again.wire.packets.size()==1);again.cancel();

  Attempt before=new Attempt();before.cancel();boolean noRestart=false;try{before.owner.start();}catch(IllegalStateException expected){noRestart=true;}check(noRestart&&before.wire.packets.isEmpty());
  Attempt reenter=new Attempt();reenter.wire.reentrant=true;reenter.owner.start();check(reenter.wire.packets.size()==2);reenter.ack();reenter.reply(new byte[]{0x10,5,1});check(reenter.owner.status()==HuamiAuthOwner.Status.PEER_ACCEPTED);reenter.cancel();
  Attempt inside=new Attempt();inside.wire.cancelInside=true;inside.owner.start();check(inside.owner.status()==HuamiAuthOwner.Status.CANCELLED&&inside.crypto.isClosed()&&inside.wire.closes==1);
  Attempt acceptedTimeout=new Attempt();acceptedTimeout.ready();acceptedTimeout.reply(new byte[]{0x10,5,1});acceptedTimeout.time.set(15000);acceptedTimeout.owner.tick();check(acceptedTimeout.crypto.isClosed()&&acceptedTimeout.owner.failure()==HuamiAuthOwner.Failure.TIMEOUT);
  Attempt link=new Attempt();link.ready();link.owner.disconnected();check(link.crypto.isClosed()&&link.wire.closes==1);
  List<Attempt> cap=new ArrayList<>();for(int i=0;i<16;i++){Attempt item=new Attempt();item.owner.start();check(item.owner.status()==HuamiAuthOwner.Status.ACTIVE);cap.add(item);}Attempt over=new Attempt();over.owner.start();check(over.crypto.isClosed()&&over.owner.nativeStatus()==10&&over.wire.closes==1);for(Attempt item:cap)item.cancel();
  Attempt confined=new Attempt();confined.owner.start();final boolean[] wrongThread={false};Thread callback=new Thread(()->{try{confined.owner.reply(confined.g(),0x82,challenge);}catch(IllegalStateException rejectedThread){wrongThread[0]=true;}});callback.start();callback.join();check(wrongThread[0]&&!confined.crypto.isClosed());confined.cancel();

  AtomicLong reads=new AtomicLong();Wire slowWire=new Wire();HuamiSessionCrypto slowCrypto=HuamiCryptoTest.fixed(vector);HuamiAuthOwner slow=new HuamiAuthOwner(slowCrypto,slowWire,()->reads.incrementAndGet()>=5?15000:0);slowWire.owner=slow;slow.start();slow.writeCompleted(slow.generation(),slowWire.tickets.get(0),true);slow.reply(slow.generation(),0x82,challenge);check(slowCrypto.isClosed()&&slowWire.packets.size()==1&&slow.failure()==HuamiAuthOwner.Failure.TIMEOUT);

  Attempt proofRejected=new Attempt();proofRejected.owner.start();proofRejected.ack();proofRejected.wire.reject=true;proofRejected.reply(challenge);check(proofRejected.crypto.isClosed()&&proofRejected.wire.closes==1&&proofRejected.wire.packets.size()==2);
  Attempt proofTimeout=new Attempt();proofTimeout.owner.start();proofTimeout.ack();proofTimeout.reply(challenge);long proofTicket=proofTimeout.ticket();proofTimeout.time.set(15000);proofTimeout.owner.tick();proofTimeout.owner.writeCompleted(proofTimeout.g(),proofTicket,true);check(proofTimeout.crypto.isClosed()&&proofTimeout.owner.failure()==HuamiAuthOwner.Failure.TIMEOUT);
  // Exercise bounded native handles and release; no extra random keys created.
  for(int i=0;i<40;i++){Attempt loop=new Attempt();loop.owner.start();loop.cancel();}
  System.out.println("PASS synthetic transport + real BC/JNI/Rust owner: ordered proof, early replies, stale generation/tickets, cancel/background/timeout/clock, errors, repeats and handle release; public fixtures only, no GATT/ART/interoperability proof.");
 }
}

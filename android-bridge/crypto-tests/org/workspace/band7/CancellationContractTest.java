// SPDX-License-Identifier: AGPL-3.0-or-later
package org.workspace.band7;
import java.nio.file.*;import java.util.*;
/** Deterministic same-owner-thread snapshots; public fixture and synthetic clock. */
public final class CancellationContractTest {
 static final class Snapshot {final long generation,time;final HuamiAuthOwner.Status status;final HuamiAuthOwner.Failure failure;final int nativeStatus,stage,closes;final boolean keyClosed;
  Snapshot(AuthLifecycleTest.Attempt a){generation=a.g();time=a.time.get();status=a.owner.status();failure=a.owner.failure();nativeStatus=a.owner.nativeStatus();stage=a.owner.stageCode();closes=a.wire.closes;keyClosed=a.crypto.isClosed();}
  @Override public String toString(){return "{generation:"+generation+",time:"+time+",status:"+status+",failure:"+failure+",native:"+nativeStatus+",stage:"+stage+",closes:"+closes+",keyClosed:"+keyClosed+"}";}
 }
 static boolean terminal(HuamiAuthOwner.Status s){return s==HuamiAuthOwner.Status.CANCELLED||s==HuamiAuthOwner.Status.BACKGROUND||s==HuamiAuthOwner.Status.REJECTED||s==HuamiAuthOwner.Status.FAILED;}
 static void check(boolean b){if(!b)throw new AssertionError("cancel contract");}
 static void cancel(String label,AuthLifecycleTest.Attempt a){Snapshot before=new Snapshot(a);a.owner.cancel();Snapshot after=new Snapshot(a);System.out.println(label+" before="+before+" after="+after);
  check(before.generation==after.generation&&after.stage==6&&after.keyClosed);
  if(terminal(before.status))check(after.status==before.status&&after.failure==before.failure&&after.nativeStatus==before.nativeStatus&&after.closes==before.closes);
  else check(after.status==HuamiAuthOwner.Status.CANCELLED&&after.failure==HuamiAuthOwner.Failure.NONE&&after.closes==before.closes+1);
  a.owner.cancel();a.owner.writeCompleted(a.g(),1,true);a.owner.reply(a.g(),0x82,new byte[]{0x10,5,1});Snapshot repeated=new Snapshot(a);check(repeated.status==after.status&&repeated.failure==after.failure&&repeated.closes==after.closes);
 }
 public static void main(String[] args)throws Exception{
  AuthLifecycleTest.vector=Files.readAllLines(Paths.get(args[0]));AuthLifecycleTest.challenge=new byte[67];AuthLifecycleTest.challenge[0]=0x10;AuthLifecycleTest.challenge[1]=4;AuthLifecycleTest.challenge[2]=1;System.arraycopy(HuamiCryptoTest.point(AuthLifecycleTest.vector.get(4),AuthLifecycleTest.vector.get(5)),0,AuthLifecycleTest.challenge,19,48);
  AuthLifecycleTest.Attempt fresh=new AuthLifecycleTest.Attempt();cancel("NEW",fresh);
  AuthLifecycleTest.Attempt active=new AuthLifecycleTest.Attempt();active.owner.start();cancel("ACTIVE",active);
  AuthLifecycleTest.Attempt accepted=new AuthLifecycleTest.Attempt();accepted.ready();accepted.reply(new byte[]{0x10,5,1});check(accepted.owner.status()==HuamiAuthOwner.Status.PEER_ACCEPTED&&!accepted.crypto.isClosed());cancel("PEER_ACCEPTED_LIVE",accepted);
  AuthLifecycleTest.Attempt timeout=new AuthLifecycleTest.Attempt();timeout.owner.start();timeout.time.set(15000);timeout.owner.tick();check(timeout.owner.status()==HuamiAuthOwner.Status.FAILED&&timeout.owner.failure()==HuamiAuthOwner.Failure.TIMEOUT);cancel("TIMEOUT_BEFORE_CANCEL",timeout);check(timeout.owner.status()!=HuamiAuthOwner.Status.CANCELLED);System.out.println("COUNTEREXAMPLE: unconditional old CANCELLED expectation fails after timeout; preserving FAILED/TIMEOUT is required.");
  AuthLifecycleTest.Attempt failed=new AuthLifecycleTest.Attempt();failed.wire.reject=true;failed.owner.start();cancel("TRANSPORT_ERROR",failed);
  AuthLifecycleTest.Attempt background=new AuthLifecycleTest.Attempt();background.owner.start();background.owner.background();cancel("BACKGROUND",background);
  AuthLifecycleTest.Attempt rejected=new AuthLifecycleTest.Attempt();rejected.ready();rejected.reply(new byte[]{0x10,5,0x25});cancel("REJECTED",rejected);
  for(int i=0;i<20;i++){AuthLifecycleTest.Attempt loop=new AuthLifecycleTest.Attempt();loop.owner.start();cancel("REPEAT_"+i,loop);}
  System.out.println("PASS deterministic cancel contract with atomic before/after and cause/resource preservation; real JNI/Rust, synthetic transport/clock, no Band/ART assertion.");
 }
}

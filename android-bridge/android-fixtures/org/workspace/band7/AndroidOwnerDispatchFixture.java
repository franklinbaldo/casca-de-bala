// SPDX-License-Identifier: AGPL-3.0-or-later
package org.workspace.band7;
import android.os.Handler;import android.os.HandlerThread;import android.os.Looper;import android.os.SystemClock;
import java.nio.file.*;import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
/** Actual Android Handler/Looper + real JNI/Rust owner; explicit lifecycle policy
 * calls and transport are synthetic. Not an installed Activity lifecycle test. */
public final class AndroidOwnerDispatchFixture {
 private static void check(boolean b){if(!b)throw new AssertionError("Android dispatch contract");}
 public static void main(String[] args)throws Exception{
  List<String> v=Files.readAllLines(Paths.get(args[0]));HandlerThread thread=new HandlerThread("casca-fixture-owner");thread.start();Handler handler=new Handler(thread.getLooper());CountDownLatch done=new CountDownLatch(1);AtomicReference<Throwable> error=new AtomicReference<>();AtomicLong offset=new AtomicLong();
  handler.post(()->{try{
   check(Looper.myLooper()==thread.getLooper());
   HuamiSessionCrypto oldKey=HuamiCryptoTest.fixed(v);AuthLifecycleTest.Wire oldWire=new AuthLifecycleTest.Wire();HuamiAuthOwner old=new HuamiAuthOwner(oldKey,oldWire,SystemClock::elapsedRealtime);oldWire.owner=old;old.start();long generation=old.generation(),ticket=oldWire.tickets.get(0);old.background();check(old.status()==HuamiAuthOwner.Status.BACKGROUND&&oldKey.isClosed()&&oldWire.closes==1);
   HuamiSessionCrypto key=HuamiCryptoTest.fixed(v);AuthLifecycleTest.Wire wire=new AuthLifecycleTest.Wire();HuamiAuthOwner fresh=new HuamiAuthOwner(key,wire,()->SystemClock.elapsedRealtime()+offset.get());wire.owner=fresh;fresh.start();check(fresh.status()==HuamiAuthOwner.Status.ACTIVE);
   Runnable cancelled=()->error.compareAndSet(null,new AssertionError("removed callback ran"));handler.postDelayed(cancelled,5000);check(handler.hasCallbacks(cancelled));handler.removeCallbacks(cancelled);check(!handler.hasCallbacks(cancelled));
   handler.postDelayed(()->{try{
    fresh.reply(generation,0x82,new byte[1000]);fresh.writeCompleted(generation,ticket,false);check(fresh.status()==HuamiAuthOwner.Status.ACTIVE&&!key.isClosed()&&wire.packets.size()==1);
    // Injected clock offset tests timeout policy, not fifteen seconds of wall time.
    offset.set(15000);fresh.tick();check(fresh.failure()==HuamiAuthOwner.Failure.TIMEOUT&&key.isClosed()&&wire.closes==1);
    old.writeCompleted(generation,ticket,true);old.reply(generation,0x82,new byte[]{0x10,5,1});check(oldWire.packets.size()==1&&oldWire.closes==1);
    HuamiSessionCrypto againKey=HuamiCryptoTest.fixed(v);AuthLifecycleTest.Wire againWire=new AuthLifecycleTest.Wire();HuamiAuthOwner again=new HuamiAuthOwner(againKey,againWire,SystemClock::elapsedRealtime);againWire.owner=again;again.start();again.cancel();again.background();check(again.status()==HuamiAuthOwner.Status.CANCELLED&&againKey.isClosed()&&againWire.closes==1);
   }catch(Throwable t){error.compareAndSet(null,t);}finally{done.countDown();}},50);
  }catch(Throwable t){error.compareAndSet(null,t);done.countDown();}});
  try{if(!done.await(30,TimeUnit.SECONDS))throw new AssertionError("Android Handler deadline");if(error.get()!=null)throw new AssertionError("Android dispatch failed",error.get());}
  finally{handler.removeCallbacksAndMessages(null);thread.quitSafely();thread.join(5000);check(!thread.isAlive());}
  System.out.println("PASS Android HandlerThread/Looper/SystemClock + real JNI/Rust owner: posted callbacks, stale generations, removed timers, injected timeout, background/cancel/repeat cleanup. Lifecycle events/transport simulated; no Activity/onStop/rotation/UI or GATT validation.");
 }
}

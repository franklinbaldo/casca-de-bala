// SPDX-License-Identifier: AGPL-3.0-or-later
package org.workspace.band7;
import android.os.*;import java.io.*;import java.nio.charset.StandardCharsets;import java.util.*;import java.util.function.Consumer;
import org.workspace.band7.app.PilotActivity;
/** Non-exported debug fixture only. Entered fixture bytes are never used as a
 * credential; real crypto/JNI/Rust use the existing public known-answer material. */
public final class PilotFixtureActivity extends PilotActivity {
 private int created,closed;private Fixture session;private Consumer<PilotConnection.State> priorObserver;
 @Override protected boolean fixtureMode(){return true;}
 @Override protected boolean selectionAvailable(){return true;}
 public int fixtureCreated(){return created;}public int fixtureClosed(){return closed;}
 public void fixtureSilent(boolean value){if(session!=null)session.silent=value;}
 public void fixtureLateCallback(){if(priorObserver!=null)priorObserver.accept(new PilotConnection.State(PilotConnection.Stage.READY,PilotConnection.Reason.NONE,PilotConnection.Origin.SIMULATED_PEER,null,-1));}
 @Override protected PilotConnection createSession(byte[] unused,Consumer<PilotConnection.State> observer){if(unused.length!=16)throw new IllegalArgumentException("fixture input");List<String> v=new ArrayList<>();try(BufferedReader r=new BufferedReader(new InputStreamReader(getAssets().open("openssl-b163-public-test-vector.txt"),StandardCharsets.UTF_8))){String s;while((s=r.readLine())!=null)v.add(s);}catch(IOException e){throw new IllegalStateException("public fixture unavailable");}created++;priorObserver=observer;session=new Fixture(v,observer);return session;}
 private final class Fixture implements PilotConnection {
  private final BatteryQueryTest.Attempt a;private final List<String> vector;private final Consumer<State> observer;private final Handler main=new Handler(Looper.getMainLooper());private boolean stopped,silent;
  Fixture(List<String> v,Consumer<State> o){vector=v;observer=o;a=new BatteryQueryTest.Attempt(v);}
  private void emit(Stage stage,Reason why){observer.accept(new State(stage,why,Origin.SIMULATED_PEER,a.query.percent(),a.query.sampleTime()));}
  public void start(){a.time.set(SystemClock.elapsedRealtime());a.ready(vector);emit(Stage.READY,Reason.NONE);main.post(tick);}
  public boolean requestBattery(){if(stopped||!a.query.request())return false;a.drain();emit(Stage.READING,Reason.NONE);if(!silent)main.postDelayed(()->{if(stopped)return;a.time.set(SystemClock.elapsedRealtime());a.reply(0,new byte[]{4,1,0,0x29,0,1});a.reply(0x29,BatteryQueryTest.battery());emit(Stage.COMPLETE,Reason.NONE);},120);return true;}
  private final Runnable tick=new Runnable(){public void run(){if(stopped)return;a.time.set(SystemClock.elapsedRealtime());a.wire.tick();if(a.query.state()==ReadOnlyBatteryQuery.State.TIMEOUT){emit(Stage.ERROR,Reason.TIMEOUT);shutdown();return;}if(a.owner.status()!=HuamiAuthOwner.Status.PEER_ACCEPTED){emit(Stage.STOPPED,Reason.TRANSPORT);shutdown();return;}main.postDelayed(this,100);}};
  private void shutdown(){if(stopped)return;stopped=true;main.removeCallbacksAndMessages(null);a.close();closed++;}
  public void close(){shutdown();}
 }
}

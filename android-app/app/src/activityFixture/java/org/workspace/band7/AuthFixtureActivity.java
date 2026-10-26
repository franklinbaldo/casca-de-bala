// SPDX-License-Identifier: AGPL-3.0-or-later
package org.workspace.band7;
import android.app.Activity;import android.os.*;import android.view.*;import android.widget.*;
import java.util.*;import java.io.*;import java.nio.charset.StandardCharsets;
/** Debug-only ART harness. Does NOT instantiate HuamiGattAdapter or ask BLE permission. */
public final class AuthFixtureActivity extends Activity {
 private final Handler main=new Handler(Looper.getMainLooper());private TextView state;private List<String> vector;
 static final int START=0x00ca0001,CANCEL=0x00ca0002,SILENT=0x00ca0003,BATTERY=0x00ca0004;
 private boolean silent,foreground;private int portCloses;private long cancelSequence;private CancelTrace cancelTrace;private HuamiSessionCrypto fixtureCrypto;
 static final class State {
  final long generation,time;final String status,reason,failure;final int nativeStatus,stage,closes;final boolean keyClosed,foreground;
  State(AuthFixtureActivity a){HuamiAuthOwner o=a.owner;generation=o==null?0:o.generation();time=SystemClock.elapsedRealtime();status=o==null?"NONE":o.status().name();reason=a.wire==null?"NONE":a.wire.reason().name();failure=o==null?"NONE":o.failure().name();nativeStatus=o==null?0:o.nativeStatus();stage=o==null?0:o.stageCode();closes=a.portCloses;keyClosed=a.fixtureCrypto==null||a.fixtureCrypto.isClosed();foreground=a.foreground;}
  public String toString(){return "{g:"+generation+",t:"+time+",status:"+status+",failure:"+failure+",native:"+nativeStatus+",stage:"+stage+",wire:"+reason+",closes:"+closes+",keyClosed:"+keyClosed+",foreground:"+foreground+"}";}
 }
 static final class CancelTrace {final long sequence;final State before,after;CancelTrace(long n,State b,State a){sequence=n;before=b;after=a;}public String toString(){return "cancel#"+sequence+" before="+before+" after="+after;}}
 State fixtureState(){return new State(this);}
 CancelTrace lastCancel(){return cancelTrace;}
 private void cancelFromView(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("UI thread");State before=fixtureState();if(owner!=null)owner.cancel();cancelTrace=new CancelTrace(++cancelSequence,before,fixtureState());render();}
 private HuamiAuthOwner owner;private HuamiAuthTransport wire;private ReadOnlyBatteryQuery query;private HuamiSessionCrypto peerCrypto;private HuamiEncryptedFrames peerFrames;private Button battery;
 ReadOnlyBatteryQuery.State queryState(){return query==null?ReadOnlyBatteryQuery.State.IDLE:query.state();}
 Integer queryPercent(){return query==null?null:query.percent();}
 @Override public void onCreate(Bundle saved){super.onCreate(saved);setTitle("Fixture AUTH · sem Bluetooth");
  ScrollView scroll=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(24,80,24,32);scroll.addView(root);
  TextView intro=new TextView(this);intro.setTextSize(20);intro.setText("Teste local de sessão\nTransporte e peer simulados. Nenhuma pulseira é acessada. Só dados públicos de teste; nenhuma chave nova ou credencial real.");root.addView(intro);
  state=new TextView(this);state.setTextSize(18);root.addView(state);Button start=new Button(this);start.setId(START);start.setText("Iniciar fixture local");start.setMinHeight(56);start.setOnClickListener(v->begin());root.addView(start);Button cancel=new Button(this);cancel.setId(CANCEL);cancel.setText("Cancelar e fechar contexto");cancel.setMinHeight(56);cancel.setOnClickListener(v->cancelFromView());root.addView(cancel);CheckBox noReply=new CheckBox(this);noReply.setId(SILENT);noReply.setText("Simular ausência de resposta (timeout local de5s)");noReply.setOnCheckedChangeListener((b,checked)->silent=checked);root.addView(noReply);battery=new Button(this);battery.setId(BATTERY);battery.setText("Consultar bateria · peer simulado");battery.setMinHeight(56);battery.setEnabled(false);battery.setOnClickListener(v->{if(query!=null)query.request();render();});root.addView(battery);setContentView(scroll);
  try{vector=new ArrayList<>();try(BufferedReader r=new BufferedReader(new InputStreamReader(getAssets().open("openssl-b163-public-test-vector.txt"),StandardCharsets.UTF_8))){String line;while((line=r.readLine())!=null)vector.add(line);}state.setText("Pronto. Inicie explicitamente; não é prova de interoperabilidade.");}catch(IOException e){state.setText("Fixture indisponível; nenhum teste iniciado.");start.setEnabled(false);}
 }
 private void begin(){if(owner!=null)owner.cancel();if(peerFrames!=null)peerFrames.close();if(peerCrypto!=null)peerCrypto.close();peerCrypto=EncryptedFramesTest.ready(vector);peerFrames=peerCrypto.claimEncryptedFrames(30,HuamiEncryptedFrames.RxPolicy.UPSTREAM_PAYLOAD_ONLY);main.removeCallbacksAndMessages(null);
  final class FakePort implements HuamiAuthTransport.Port {
   public boolean write(long generation,long ticket,int target,byte[] bytes){if(silent)return true;byte[] copy=bytes.clone();
    main.postDelayed(()->{if(owner==null||owner.generation()!=generation)return;wire.completed(generation,ticket,true);
     if(target==HuamiAuthTransport.TX&&owner.status()==HuamiAuthOwner.Status.PEER_ACCEPTED){
      int endpoint=-1;byte[] request=null;
      if((copy[1]&8)!=0){HuamiEncryptedFrames.Received r=peerFrames.receive(SystemClock.elapsedRealtime(),copy);endpoint=r.endpoint;request=r.payload;}
      else if((copy[1]&3)==3){endpoint=(copy[9]&255)|((copy[10]&255)<<8);request=Arrays.copyOfRange(copy,11,copy.length);}
      if(request!=null&&request.length==1&&request[0]==3){byte[] response;if(endpoint==0)response=new byte[]{4,1,0,0x29,0,1};else if(endpoint==0x29)response=BatteryQueryTest.battery();else return;
       for(byte[] packet:peerFrames.encode(endpoint,23,response)){byte[] next=packet.clone();main.postDelayed(()->{if(owner.generation()==generation){wire.notified(generation,next);render();}},20);}
      }
     }else if(target==HuamiAuthTransport.TX&&(copy[1]&2)!=0){byte[] reply;if((copy[3]&255)==1){reply=new byte[67];reply[0]=0x10;reply[1]=4;reply[2]=1;System.arraycopy(HuamiCryptoTest.point(vector.get(4),vector.get(5)),0,reply,19,48);}else if((copy[3]&255)==2)reply=new byte[]{0x10,5,1};else return;
      int i=0;for(byte[] frame:FramingTransportTest.chunks(reply,20+(copy[3]&255))){byte[] next=frame.clone();main.postDelayed(()->{if(owner.generation()==generation)wire.notified(generation,next);render();},20L*(++i));}
     }Arrays.fill(copy,(byte)0);render();},60);return true;
   }
   public void close(){portCloses++;main.removeCallbacksAndMessages(null);}
  }
  wire=new HuamiAuthTransport(new FakePort(),SystemClock::elapsedRealtime);fixtureCrypto=HuamiCryptoTest.fixed(vector);owner=new HuamiAuthOwner(fixtureCrypto,wire,SystemClock::elapsedRealtime);wire.bind(owner);query=new ReadOnlyBatteryQuery(owner,wire,SystemClock::elapsedRealtime);owner.start();render();main.post(tick);
 }
 private final Runnable tick=new Runnable(){public void run(){if(owner==null)return;wire.tick();render();if(owner.status()==HuamiAuthOwner.Status.ACTIVE||owner.status()==HuamiAuthOwner.Status.PEER_ACCEPTED)main.postDelayed(this,250);}};
 private void render(){if(owner==null)return;battery.setEnabled(query!=null&&query.canRequest());state.setText("Geração local: "+owner.generation()+"\nEstado: "+owner.status()+"\nFase Rust: "+owner.stageCode()+"\nFalha: "+owner.failure()+"\nTransporte: "+wire.reason()+"\n\nConsulta de fixture: "+queryState()+" / bateria: "+queryPercent()+"%\nPEER_ACCEPTED aqui é resposta de fixture. Nenhum AUTH real, consulta física, configuração ou dado de saúde. O piloto fecha em até15s; sair/girar cancela e não retoma sozinho.");}
 @Override protected void onResume(){super.onResume();foreground=true;}
 @Override protected void onPause(){foreground=false;super.onPause();}
 @Override protected void onStop(){if(owner!=null)owner.background();main.removeCallbacksAndMessages(null);render();super.onStop();}
 @Override protected void onDestroy(){if(owner!=null)owner.close();if(peerFrames!=null)peerFrames.close();if(peerCrypto!=null)peerCrypto.close();main.removeCallbacksAndMessages(null);super.onDestroy();}
}

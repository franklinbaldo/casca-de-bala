// SPDX-License-Identifier: AGPL-3.0-or-later
package org.workspace.band7.app;
import android.Manifest;import android.app.*;import android.bluetooth.*;import android.content.pm.PackageManager;import android.os.*;
import java.util.*;import java.util.concurrent.atomic.*;import java.util.function.Consumer;
import org.workspace.band7.*;
/** Explicit-start AUTH and separately requested read-only battery pilot. Explicit product action; fixture overrides avoid GATT. UUIDs:
 * Gadgetbridge HuamiService/MiBandService a0948ee1..., and sanitized Band7 discovery.
 * Never pairs, initializes time/profile, fetches health or probes automatically after AUTH.
 */
public final class HuamiGattAdapter implements AutoCloseable {
 private static final UUID SERVICE=UUID.fromString("0000fee0-0000-1000-8000-00805f9b34fb"),TX=UUID.fromString("00000016-0000-3512-2118-0009af100700"),RX=UUID.fromString("00000017-0000-3512-2118-0009af100700"),CCCD=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
 public enum Phase { NEW,CONNECTING,DISCOVERING,SUBSCRIBING,AUTH,STOPPED }
 public enum Reason { NONE,PERMISSION,UNAVAILABLE,RADIO_OFF,NOT_BONDED,UNSUPPORTED,CONNECT_ERROR,TIMEOUT,OVERFLOW,CANCELLED,BACKGROUND,SESSION_END }
 public static final class Snapshot {
  public final Phase phase;public final Reason reason;public final HuamiAuthOwner.Status auth;public final HuamiAuthOwner.Failure failure;public final HuamiAuthTransport.Reason transport;public final ReadOnlyBatteryQuery.State batteryQuery;public final boolean batteryCanRequest;public final Integer batteryPercent;public final long batterySampleElapsed;
  private Snapshot(HuamiGattAdapter a){phase=a.phase;reason=a.reason;auth=a.owner==null?null:a.owner.status();failure=a.owner==null?null:a.owner.failure();transport=a.wire==null?null:a.wire.reason();batteryQuery=a.battery==null?ReadOnlyBatteryQuery.State.IDLE:a.battery.state();batteryCanRequest=a.battery!=null&&a.battery.canRequest();batteryPercent=a.battery==null?null:a.battery.percent();batterySampleElapsed=a.battery==null?-1:a.battery.sampleTime();}
 }
 private final Activity activity;private final HuamiSessionCrypto crypto;private final Consumer<Snapshot> observer;private final Handler main=new Handler(Looper.getMainLooper());
 private volatile BluetoothGatt gatt;private BluetoothGattCharacteristic tx,rx;private ReadOnlyBatteryQuery battery;private HuamiAuthOwner owner;private HuamiAuthTransport wire;private Phase phase=Phase.NEW;private Reason reason=Reason.NONE;private volatile boolean closed;private boolean registered;private long setupDeadline;
 private volatile long writeTicket;private volatile int writeTarget;private byte[] pendingValue;
 private final AtomicInteger postedRx=new AtomicInteger();private final AtomicBoolean overflowPosted=new AtomicBoolean();
 public HuamiGattAdapter(Activity activity,HuamiSessionCrypto crypto,Consumer<Snapshot> observer){this.activity=Objects.requireNonNull(activity);this.crypto=Objects.requireNonNull(crypto);this.observer=Objects.requireNonNull(observer);}
 private void thread(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("main thread required");}
 private boolean permitted(){return activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;}
 private void emit(){try{observer.accept(new Snapshot(this));}catch(RuntimeException ignored){}}
 public void start(BluetoothDevice selected){thread();if(phase!=Phase.NEW||closed)throw new IllegalStateException("one-shot");
  try{if(!permitted()){stop(Reason.PERMISSION);return;}BluetoothManager manager=activity.getSystemService(BluetoothManager.class);BluetoothAdapter adapter=manager==null?null:manager.getAdapter();if(adapter==null){stop(Reason.UNAVAILABLE);return;}if(!adapter.isEnabled()){stop(Reason.RADIO_OFF);return;}if(selected==null||selected.getBondState()!=BluetoothDevice.BOND_BONDED){stop(Reason.NOT_BONDED);return;}
   activity.getApplication().registerActivityLifecycleCallbacks(lifecycle);registered=true;phase=Phase.CONNECTING;setupDeadline=SystemClock.elapsedRealtime()+15000;gatt=selected.connectGatt(activity,false,callback,BluetoothDevice.TRANSPORT_LE);if(gatt==null){stop(Reason.CONNECT_ERROR);return;}main.post(tick);emit();
  }catch(SecurityException denied){stop(Reason.PERMISSION);}catch(RuntimeException failed){stop(Reason.CONNECT_ERROR);}
 }
 private boolean current(BluetoothGatt value){return !closed&&value==gatt;}
 private final BluetoothGattCallback callback=new BluetoothGattCallback(){
  @Override public void onConnectionStateChange(BluetoothGatt source,int status,int state){main.post(()->{if(!current(source))return;if(status!=0||state==BluetoothProfile.STATE_DISCONNECTED){stop(Reason.CONNECT_ERROR);return;}if(state==BluetoothProfile.STATE_CONNECTED&&phase==Phase.CONNECTING){phase=Phase.DISCOVERING;try{if(!source.discoverServices())stop(Reason.CONNECT_ERROR);}catch(SecurityException denied){stop(Reason.PERMISSION);}emit();}});}
  @Override public void onServicesDiscovered(BluetoothGatt source,int status){main.post(()->{if(!current(source)||phase!=Phase.DISCOVERING)return;if(status!=0){stop(Reason.CONNECT_ERROR);return;}subscribe(source);});}
  @Override public void onDescriptorWrite(BluetoothGatt source,BluetoothGattDescriptor d,int status){main.post(()->{if(!current(source)||phase!=Phase.SUBSCRIBING||!CCCD.equals(d.getUuid())||!RX.equals(d.getCharacteristic().getUuid()))return;if(status!=0){stop(Reason.CONNECT_ERROR);return;}beginAuth();});}
  @Override public void onCharacteristicWrite(BluetoothGatt source,BluetoothGattCharacteristic c,int status){long ticket=writeTicket;int target=writeTarget;UUID uuid=c.getUuid();main.post(()->{if(!current(source)||phase!=Phase.AUTH||ticket==0||ticket!=writeTicket||!(target==HuamiAuthTransport.TX?TX:RX).equals(uuid))return;writeTicket=0;clearValue();wire.completed(owner.generation(),ticket,status==BluetoothGatt.GATT_SUCCESS);emit();});}
  @Override public void onCharacteristicChanged(BluetoothGatt source,BluetoothGattCharacteristic c,byte[] value){notification(source,c,value);}
  @Override public void onCharacteristicChanged(BluetoothGatt source,BluetoothGattCharacteristic c){notification(source,c,c.getValue());}
 };
 private void notification(BluetoothGatt source,BluetoothGattCharacteristic c,byte[] value){
  if(closed||source!=gatt||!RX.equals(c.getUuid()))return;if(value==null||value.length>512){main.post(()->{if(current(source))stop(Reason.UNSUPPORTED);});return;}
  if(postedRx.incrementAndGet()>16){postedRx.decrementAndGet();if(overflowPosted.compareAndSet(false,true))main.post(()->{if(current(source))stop(Reason.OVERFLOW);});return;}
  byte[] copy=value.clone();main.post(()->{try{if(current(source)&&phase==Phase.AUTH){wire.notified(owner.generation(),copy);emit();}}finally{Arrays.fill(copy,(byte)0);postedRx.decrementAndGet();}});
 }
 private static boolean writable(BluetoothGattCharacteristic c){return c!=null&&(c.getProperties()&(BluetoothGattCharacteristic.PROPERTY_WRITE|BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE))!=0;}
 private void subscribe(BluetoothGatt source){try{
  BluetoothGattService service=source.getService(SERVICE);tx=service==null?null:service.getCharacteristic(TX);rx=service==null?null:service.getCharacteristic(RX);
  if(!writable(tx)||!writable(rx)||(rx.getProperties()&BluetoothGattCharacteristic.PROPERTY_NOTIFY)==0){stop(Reason.UNSUPPORTED);return;}
  BluetoothGattDescriptor d=rx.getDescriptor(CCCD);if(d==null||!source.setCharacteristicNotification(rx,true)){stop(Reason.UNSUPPORTED);return;}
  phase=Phase.SUBSCRIBING;boolean accepted;if(Build.VERSION.SDK_INT>=33)accepted=source.writeDescriptor(d,BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)==BluetoothStatusCodes.SUCCESS;else{d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);accepted=source.writeDescriptor(d);}if(!accepted)stop(Reason.CONNECT_ERROR);emit();
 }catch(SecurityException denied){stop(Reason.PERMISSION);}catch(RuntimeException failed){stop(Reason.CONNECT_ERROR);}}
 private void beginAuth(){
  wire=new HuamiAuthTransport(new HuamiAuthTransport.Port(){public boolean write(long generation,long ticket,int target,byte[] frame){return writeFrame(ticket,target,frame);}public void close(){stop(Reason.SESSION_END);}},SystemClock::elapsedRealtime);
  owner=new HuamiAuthOwner(crypto,wire,SystemClock::elapsedRealtime);wire.bind(owner);battery=new ReadOnlyBatteryQuery(owner,wire,SystemClock::elapsedRealtime);phase=Phase.AUTH;owner.start();emit();
 }
 /** Explicit foreground user action only. Single request per connection; no retry
  * after a timeout without reconnecting. No automatic call from AUTH callbacks. */
 public boolean requestBattery(){thread();if(closed||phase!=Phase.AUTH||battery==null)return false;boolean started=battery.request();emit();return started;}
 private boolean writeFrame(long ticket,int target,byte[] frame){
  if(closed||!permitted()||writeTicket!=0||frame.length>20||target<0||target>1)return false;
  BluetoothGattCharacteristic c=target==HuamiAuthTransport.TX?tx:rx;writeTicket=ticket;writeTarget=target;pendingValue=frame.clone();
  try{int type=(c.getProperties()&BluetoothGattCharacteristic.PROPERTY_WRITE)!=0?BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT:BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
   boolean accepted;if(Build.VERSION.SDK_INT>=33)accepted=gatt.writeCharacteristic(c,pendingValue,type)==BluetoothStatusCodes.SUCCESS;else{c.setWriteType(type);c.setValue(pendingValue);accepted=gatt.writeCharacteristic(c);}if(!accepted){writeTicket=0;clearValue();}return accepted;
  }catch(SecurityException denied){writeTicket=0;clearValue();return false;}catch(RuntimeException failed){writeTicket=0;clearValue();return false;}
 }
 private final Runnable tick=new Runnable(){public void run(){if(closed)return;try{if(!permitted()){stop(Reason.PERMISSION);return;}if(phase==Phase.AUTH)wire.tick();else if(SystemClock.elapsedRealtime()>=setupDeadline){stop(Reason.TIMEOUT);return;}}catch(RuntimeException failed){stop(Reason.CONNECT_ERROR);return;}if(!closed){emit();main.postDelayed(this,250);}}};
 private void clearValue(){if(pendingValue!=null)Arrays.fill(pendingValue,(byte)0);pendingValue=null;}
 private void stop(Reason why){thread();if(closed)return;closed=true;phase=Phase.STOPPED;reason=why;main.removeCallbacksAndMessages(null);writeTicket=0;clearValue();
  if(registered){activity.getApplication().unregisterActivityLifecycleCallbacks(lifecycle);registered=false;}
  if(owner!=null){if(why==Reason.BACKGROUND)owner.background();else if(why==Reason.CANCELLED)owner.cancel();else owner.disconnected();}else crypto.close();
  BluetoothGatt old=gatt;gatt=null;if(old!=null){try{old.disconnect();}catch(SecurityException ignored){}try{old.close();}catch(SecurityException ignored){}}emit();
 }
 @Override public void close(){stop(Reason.CANCELLED);}
 private final Application.ActivityLifecycleCallbacks lifecycle=new Application.ActivityLifecycleCallbacks(){
  public void onActivityStopped(Activity a){if(a==activity)stop(Reason.BACKGROUND);}public void onActivityDestroyed(Activity a){if(a==activity)stop(Reason.BACKGROUND);}
  public void onActivityCreated(Activity a,Bundle b){}public void onActivityStarted(Activity a){}public void onActivityResumed(Activity a){}public void onActivityPaused(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}
 };
}

package org.workspace.band7.app;

import android.Manifest;
import android.bluetooth.*;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.*;
import org.json.*;
import org.workspace.band7.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import static org.workspace.band7.BasicReadSession.Key;
import static org.workspace.band7.ReadViewModel.*;

/** Foreground one-shot adapter. Events are typed; copy belongs in the Activity. */
final class BasicGattReader implements AutoCloseable {
    private final Context context;private final Consumer<Snapshot> observer;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Map<Key,BluetoothGattCharacteristic> characteristics=new EnumMap<>(Key.class);
    private final ReadViewModel model=new ReadViewModel();
    private BluetoothGatt gatt;private BasicReadSession session;private boolean closed;
    private long deadline;private Cause requestedStop;
    BasicGattReader(Context context,Consumer<Snapshot> observer){this.context=context;this.observer=observer;}
    private void publish(){observer.accept(model.snapshot());}
    private boolean permitted(){return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;}
    void connect(BluetoothDevice selected){
        if(gatt!=null||closed)throw new IllegalStateException("One-shot connection");publish();
        try{
            if(!permitted()){finish(Cause.PERMISSION);return;}
            BluetoothManager manager=context.getSystemService(BluetoothManager.class);
            BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
            if(adapter==null){finish(Cause.UNAVAILABLE);return;}
            if(!adapter.isEnabled()){finish(Cause.BLUETOOTH_OFF);return;}
            if(selected==null){finish(Cause.CONNECTION);return;}
            deadline=SystemClock.elapsedRealtime()+15000;
            gatt=selected.connectGatt(context,false,callback,BluetoothDevice.TRANSPORT_LE);
            if(gatt==null){finish(Cause.CONNECTION);return;}main.post(tick);
        }catch(SecurityException denied){finish(Cause.PERMISSION);}
    }
    private final Runnable tick=new Runnable(){public void run(){
        if(closed)return;
        if(!permitted()){finish(Cause.PERMISSION);return;}
        try{BluetoothManager manager=context.getSystemService(BluetoothManager.class);BluetoothAdapter a=manager==null?null:manager.getAdapter();if(a!=null&&!a.isEnabled()){finish(Cause.BLUETOOTH_OFF);return;}}
        catch(SecurityException denied){finish(Cause.PERMISSION);return;}
        long now=SystemClock.elapsedRealtime();
        if(session!=null)session.tick(now);else if(now>=deadline)finish(Cause.TIMEOUT);
        if(!closed)main.postDelayed(this,250);
    }};
    private final BluetoothGattCallback callback=new BluetoothGattCallback(){
        @Override public void onServiceChanged(BluetoothGatt source){main.post(()->{if(!closed&&source==gatt)finish(Cause.SERVICE_CHANGED);});}
        @Override public void onConnectionStateChange(BluetoothGatt source,int status,int state){main.post(()->{
            if(closed||source!=gatt)return;
            if(status!=0||state==BluetoothProfile.STATE_DISCONNECTED){finish(Cause.CONNECTION);return;}
            if(state==BluetoothProfile.STATE_CONNECTED)try{
                model.discovering();publish();if(!source.discoverServices())finish(Cause.DISCOVERY);
            }catch(SecurityException denied){finish(Cause.PERMISSION);}
        });}
        @Override public void onServicesDiscovered(BluetoothGatt source,int status){main.post(()->{
            if(closed||source!=gatt||session!=null)return;
            if(status!=0){finish(Cause.DISCOVERY);return;}
            try{plan(source);}catch(SecurityException denied){finish(Cause.PERMISSION);}
            catch(JSONException|IllegalArgumentException|IllegalStateException invalid){finish(Cause.INVALID_REPLY);}
            catch(LinkageError failure){finish(Cause.NATIVE);}
        });}
        @Override public void onCharacteristicRead(BluetoothGatt source,BluetoothGattCharacteristic characteristic,byte[] value,int status){deliver(source,characteristic,value,status);}
        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicRead(BluetoothGatt source,BluetoothGattCharacteristic characteristic,int status){
            if(Build.VERSION.SDK_INT<33)deliver(source,characteristic,characteristic.getValue(),status);
        }
    };
    private void deliver(BluetoothGatt source,BluetoothGattCharacteristic characteristic,byte[] value,int status){
        byte[] copy=value==null?new byte[0]:value.length<=64?value.clone():new byte[65];
        main.post(()->{
            if(closed||source!=gatt||session==null||characteristic.getService()==null)return;
            Key key=Key.from(characteristic.getService().getUuid(),characteristic.getUuid());
            if(key!=null&&characteristics.get(key)==characteristic)
                session.read(session.generation(),key,status,copy,SystemClock.elapsedRealtime());
        });
    }
    private void plan(BluetoothGatt source)throws JSONException{
        model.reading();JSONArray discovery=new JSONArray();List<Key> candidates=new ArrayList<>();
        for(Key key:Key.values()){
            BluetoothGattService service=source.getService(Key.sig(key.service));
            BluetoothGattCharacteristic c=service==null?null:service.getCharacteristic(Key.sig(key.characteristic));
            if(c==null){model.unavailable(key,Availability.NOT_PRESENT);continue;}
            characteristics.put(key,c);candidates.add(key);
            discovery.put(new JSONObject().put("service",new JSONObject().put("bluetooth16",key.service))
                .put("characteristic",new JSONObject().put("bluetooth16",key.characteristic))
                .put("readable",(c.getProperties()&BluetoothGattCharacteristic.PROPERTY_READ)!=0));
        }
        JSONObject request=new JSONObject().put("version",1).put("id",1).put("command",new JSONObject()
            .put("op","plan").put("connect_permission",permitted()).put("discovery",discovery));
        JSONObject response=new JSONObject(new String(GattBasics.process(request.toString().getBytes(StandardCharsets.UTF_8)),StandardCharsets.UTF_8));
        JSONArray decisions=response.getJSONObject("result").getJSONArray("plan");List<Key> reads=new ArrayList<>();
        if(decisions.length()!=candidates.size())throw new JSONException("Plan length");
        for(int i=0;i<decisions.length();i++){
            JSONObject decision=decisions.getJSONObject(i);Key key=candidates.get(i);
            if(decision.has("read")&&key.kind.equals(decision.getString("read")))reads.add(key);
            else model.unavailable(key,Availability.NOT_READ);
        }
        publish();session=new BasicReadSession(new BasicReadSession.Transport(){
            public boolean read(long generation,Key key){
                if(context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)
                    throw new SecurityException("CONNECT revoked");
                return gatt!=null&&gatt.readCharacteristic(characteristics.get(key));
            }
            public void close(){cleanup();}
        },new BasicReadSession.Observer(){
            public void result(Key key,String json){decoded(key,json);}
            public void stopped(String reason){
                Cause cause=requestedStop;
                if(cause==null)switch(reason){
                    case "plan_finished_not_full_collection":cause=Cause.NONE;break;
                    case "permission_denied":cause=Cause.PERMISSION;break;
                    case "timeout":cause=Cause.TIMEOUT;break;
                    case "cancelled":cause=Cause.CANCELLED;break;
                    default:cause=Cause.GATT;break;
                }
                end(cause);
            }
        },BasicReadSession.Origin.OUR_APP);
        session.start(reads,permitted(),SystemClock.elapsedRealtime());
    }
    private void decoded(Key key,String json){
        try{
            Object state=new JSONObject(json).getJSONObject("result").get("read");
            if(state instanceof String){model.unavailable(key,"permission_denied".equals(state)?Availability.DENIED:Availability.NOT_READ);publish();return;}
            JSONObject result=(JSONObject)state;
            if(!result.has("read")){model.unavailable(key,result.has("gatt_failure")?Availability.GATT_ERROR:Availability.INVALID);publish();return;}
            JSONObject value=result.getJSONObject("read").getJSONObject("value");long at=System.currentTimeMillis();
            if(key==Key.BATTERY)model.battery(value.getInt("battery_percent"),at);
            else if(key==Key.HARDWARE||key==Key.SOFTWARE)model.revision(key,value.getString("revision"),at);
            else {JSONObject p=value.getJSONObject("pnp");model.pnp(new Pnp(p.getInt("vendor_source"),p.getInt("vendor_id"),p.getInt("product_id"),p.getInt("product_version_bcd")),at);}
            publish();
        }catch(JSONException|ClassCastException invalid){finish(Cause.INVALID_REPLY);}
    }
    private void finish(Cause reason){requestedStop=reason;if(session!=null)session.stop("adapter_stop");else end(reason);}
    private void end(Cause reason){model.finish(reason);cleanup();publish();}
    private void cleanup(){
        if(closed)return;closed=true;main.removeCallbacks(tick);BluetoothGatt old=gatt;gatt=null;characteristics.clear();
        if(old!=null){try{old.disconnect();}catch(SecurityException ignored){}try{old.close();}catch(SecurityException ignored){}}
    }
    void close(Cause reason){finish(reason);}
    @Override public void close(){finish(Cause.CANCELLED);}
}

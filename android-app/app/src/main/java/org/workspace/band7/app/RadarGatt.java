package org.workspace.band7.app;
import android.Manifest;
import android.bluetooth.*;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.*;
import java.util.function.Consumer;
import org.workspace.band7.RadarSession;
import static org.workspace.band7.RadarSession.*;

/** Selected bonded device, foreground connected RSSI only. No scan/ATT write/auth. */
final class RadarGatt {
    private final Context context;private final Handler main=new Handler(Looper.getMainLooper());private final RadarSession model;
    private BluetoothGatt gatt;private boolean closed,started;
    RadarGatt(Context context,Consumer<Snapshot> observer){this.context=context;
        model=new RadarSession(new Transport(){
            public boolean read(long generation){if(context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)throw new SecurityException();return gatt!=null&&gatt.readRemoteRssi();}
            public void close(){cleanup();}
        },observer::accept,Origin.OUR_APP,Mode.CONTINUOUS);
    }
    private boolean permitted(){return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;}
    void connect(BluetoothDevice selected){
        if(started)throw new IllegalStateException("one-shot");started=true;model.start(SystemClock.elapsedRealtime());
        try{
            if(!permitted()){stop(Reason.PERMISSION);return;}
            BluetoothManager manager=context.getSystemService(BluetoothManager.class);BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
            if(adapter==null){stop(Reason.UNAVAILABLE);return;}if(!adapter.isEnabled()){stop(Reason.BLUETOOTH_OFF);return;}
            if(selected==null){stop(Reason.FAILED);return;}
            gatt=selected.connectGatt(context,false,callback,BluetoothDevice.TRANSPORT_LE);
            if(gatt==null){stop(Reason.FAILED);return;}main.post(tick);
        }catch(SecurityException denied){stop(Reason.PERMISSION);}catch(IllegalStateException failed){stop(Reason.FAILED);}
    }
    private final BluetoothGattCallback callback=new BluetoothGattCallback(){
        @Override public void onConnectionStateChange(BluetoothGatt source,int status,int state){main.post(()->{
            if(closed||source!=gatt)return;
            if(status!=0||state==BluetoothProfile.STATE_DISCONNECTED){stop(Reason.DISCONNECTED);return;}
            if(state==BluetoothProfile.STATE_CONNECTED)model.connected(model.generation(),SystemClock.elapsedRealtime());
        });}
        @Override public void onReadRemoteRssi(BluetoothGatt source,int rssi,int status){long receivedAt=SystemClock.elapsedRealtime();main.post(()->{
            if(!closed&&source==gatt)model.read(model.generation(),rssi,status,receivedAt,SystemClock.elapsedRealtime());
        });}
    };
    private final Runnable tick=new Runnable(){public void run(){
        if(closed)return;
        try{if(!permitted()){stop(Reason.PERMISSION);return;}
            BluetoothManager manager=context.getSystemService(BluetoothManager.class);BluetoothAdapter a=manager==null?null:manager.getAdapter();
            if(a==null){stop(Reason.UNAVAILABLE);return;}if(!a.isEnabled()){stop(Reason.BLUETOOTH_OFF);return;}
            model.tick(SystemClock.elapsedRealtime());
        }catch(SecurityException denied){stop(Reason.PERMISSION);return;}
        if(!closed)main.postDelayed(this,250);
    }};
    void measure(){if(!closed)model.measure(SystemClock.elapsedRealtime());}
    void stop(Reason reason){model.stop(reason,SystemClock.elapsedRealtime());}
    private void cleanup(){if(closed)return;closed=true;main.removeCallbacks(tick);BluetoothGatt old=gatt;gatt=null;
        if(old!=null){try{old.disconnect();}catch(SecurityException ignored){}try{old.close();}catch(SecurityException ignored){}}
    }
}

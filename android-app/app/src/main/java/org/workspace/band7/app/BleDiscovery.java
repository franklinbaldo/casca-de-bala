package org.workspace.band7.app;
import android.Manifest;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.workspace.band7.DiscoverySession;
import static org.workspace.band7.DiscoverySession.*;

/** Main-looper catalogue; bounded worker for saved bonds, bounded radio callbacks. */
final class BleDiscovery implements AutoCloseable {
    private final Context context;private final Handler main=new Handler(Looper.getMainLooper());
    private final DiscoverySession model;private BluetoothLeScanner scanner;private ScanCallback callback;
    private long activeGeneration;private final AtomicInteger pending=new AtomicInteger();private boolean requiresScan;
    private final Map<String,BluetoothDevice> devices=new HashMap<>();
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1));
    private Future<?> bondTask;
    private static final class Item {final BluetoothDevice device;final String address;final Bonded value;
        Item(BluetoothDevice d,String a,String name,Kind kind){device=d;address=a;value=new Bonded(a,name,kind);} }
    BleDiscovery(Context context,DiscoverySession.Observer observer){
        this.context=context;model=new DiscoverySession(new DiscoverySession.Transport(){
            public void start(long token){startPlatform(token);}public void stop(long token){stopPlatform(token);}
        },observer);
    }
    private boolean connectAllowed(){return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;}
    private boolean permitted(){return connectAllowed()&&(!requiresScan||context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED);}
    private BluetoothAdapter adapter(){BluetoothManager manager=context.getSystemService(BluetoothManager.class);return manager==null?null:manager.getAdapter();}
    private Radio radio(){
        BluetoothAdapter a=adapter();if(a==null||!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE))return Radio.UNAVAILABLE;
        try{return state(a.getState());}catch(SecurityException denied){return Radio.DENIED;}
    }
    private static Radio state(int value){return value==BluetoothAdapter.STATE_ON?Radio.ON:value==BluetoothAdapter.STATE_OFF?Radio.OFF:Radio.CHANGING;}
    void bonded(){
        main.removeCallbacks(tick);cancelBondTask();requiresScan=false;devices.clear();
        boolean allowed=connectAllowed();long token=model.beginBonded(allowed,allowed?radio():Radio.OFF,SystemClock.elapsedRealtime());
        if(!model.readingBonded())return;
        try{bondTask=worker.submit(()->loadBonded(token));}catch(RejectedExecutionException busy){model.bondedFailure(token,false);}
        if(model.readingBonded())main.post(tick);
    }
    private void loadBonded(long token){
        try{
            if(context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)throw new SecurityException();
            BluetoothAdapter a=adapter();if(a==null){main.post(()->model.radioChanged(token,Radio.UNAVAILABLE));return;}
            Radio before=state(a.getState());if(before!=Radio.ON){main.post(()->model.radioChanged(token,before));return;}
            Set<BluetoothDevice> saved=a.getBondedDevices();List<Item> items=new ArrayList<>();
            if(saved==null)throw new IllegalStateException();
            int inspected=0;
            for(BluetoothDevice d:saved){
                if(Thread.currentThread().isInterrupted())return;if(++inspected>256)break;
                if(d==null||d.getBondState()!=BluetoothDevice.BOND_BONDED)continue;
                int type=d.getType();if(type==BluetoothDevice.DEVICE_TYPE_CLASSIC)continue;
                Kind kind=type==BluetoothDevice.DEVICE_TYPE_LE?Kind.LE:type==BluetoothDevice.DEVICE_TYPE_DUAL?Kind.DUAL:Kind.UNKNOWN;
                items.add(new Item(d,d.getAddress(),DiscoverySession.safeName(d.getName()),kind));if(items.size()==33)break;
            }
            if(context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)throw new SecurityException();
            Radio after=state(a.getState());
            main.post(()->{
                if(token!=model.generation()||!model.readingBonded())return;
                if(!connectAllowed()){model.bondedFailure(token,true);return;}
                if(after!=Radio.ON){model.radioChanged(token,after);return;}
                List<Bonded> records=new ArrayList<>();for(Item i:items)records.add(i.value);
                model.bonded(token,records);for(Item i:items)if(model.knownKey(token,i.address))devices.put(i.address,i.device);
            });
        }catch(SecurityException denied){main.post(()->model.bondedFailure(token,true));}
        catch(RuntimeException failed){main.post(()->model.bondedFailure(token,false));}
    }
    void search(){
        main.removeCallbacks(tick);cancelBondTask();requiresScan=true;boolean allowed=permitted();Radio state=allowed?radio():Radio.OFF;
        model.start(allowed,state==Radio.ON,state!=Radio.UNAVAILABLE,SystemClock.elapsedRealtime());
        if(allowed&&state==Radio.CHANGING)model.radioChanged(model.generation(),state);
        devices.keySet().removeIf(key->!model.knownKey(model.generation(),key));if(model.active())main.post(tick);
    }
    private final Runnable tick=new Runnable(){public void run(){
        boolean allowed=permitted();Radio value=allowed?radio():Radio.OFF;
        if(allowed&&value!=Radio.ON)model.radioChanged(model.generation(),value);
        else model.tick(SystemClock.elapsedRealtime(),allowed,value==Radio.ON);
        if(model.active()||model.readingBonded())main.postDelayed(this,250);else cancelBondTask();
    }};
    private void startPlatform(long token){
        if(context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED||context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)throw new SecurityException();
        BluetoothAdapter a=adapter();if(a==null)throw new IllegalStateException();
        scanner=a.getBluetoothLeScanner();if(scanner==null)throw new IllegalStateException();activeGeneration=token;
        callback=new ScanCallback(){
            @Override public void onScanResult(int type,ScanResult result){enqueue(token,result);}
            @Override public void onBatchScanResults(List<ScanResult> results){for(int i=0;i<Math.min(64,results.size());i++)enqueue(token,results.get(i));}
            @Override public void onScanFailed(int errorCode){main.post(()->model.fail(token));}
        };
        scanner.startScan(Collections.emptyList(),new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).setReportDelay(500).build(),callback);
    }
    private void enqueue(long token,ScanResult result){
        if(pending.incrementAndGet()>64){pending.decrementAndGet();return;}
        try{
            if(context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){pending.decrementAndGet();return;}
            BluetoothDevice d=result.getDevice();String address=d.getAddress();ScanRecord record=result.getScanRecord();
            final String display=DiscoverySession.safeName(record==null?null:record.getDeviceName());final int rssi=result.getRssi();
            main.post(()->{try{model.result(token,address,display,rssi,SystemClock.elapsedRealtime());if(model.knownKey(token,address))devices.put(address,d);}finally{pending.decrementAndGet();}});
        }catch(SecurityException denied){pending.decrementAndGet();main.post(()->model.permissionLost(token));}
    }
    private void stopPlatform(long token){
        if(token!=activeGeneration)return;BluetoothLeScanner old=scanner;ScanCallback cb=callback;scanner=null;callback=null;
        if(old!=null&&cb!=null)try{old.stopScan(cb);}catch(SecurityException|IllegalStateException ignored){}
    }
    BluetoothDevice device(String address){return devices.get(address);}
    void select(long token,int id,boolean allowUnknown){
        boolean allowed=connectAllowed();Radio value=allowed?radio():Radio.OFF;
        if(allowed&&value!=Radio.ON){model.radioChanged(token,value);return;}
        if(model.select(token,id,allowed,value==Radio.ON,allowUnknown)){main.removeCallbacks(tick);cancelBondTask();devices.clear();}
    }
    private void cancelBondTask(){if(bondTask!=null){bondTask.cancel(true);bondTask=null;worker.purge();}}
    @Override public void close(){main.removeCallbacks(tick);cancelBondTask();model.cancel();devices.clear();}
    void dispose(){close();worker.shutdownNow();}
}

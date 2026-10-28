package org.workspace.casca;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.SharedPreferences;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationSpec;
import nodomain.freeyourgadget.gadgetbridge.model.NotificationType;
import nodomain.freeyourgadget.gadgetbridge.model.RecordedDataTypes;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Keeps the MCP server (Rust) alive while the owner wants it on, shows the
 * approve/deny notifications, and answers the band.* jobs AIs queue with
 * Gadgetbridge's own device layer: no BLE code of our own. */
public final class CascaMcpService extends Service {
    static final String ACTION_STOP="org.workspace.casca.MCP_STOP";
    static final String CHANNEL_RUNNING="casca_mcp_running",CHANNEL_APPROVAL="casca_mcp_approval";
    static final int RUNNING_ID=0xca5ca,APPROVAL_BASE=0xca5ca+1000;
    static volatile boolean running;static volatile int port;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Set<Long> shown=new HashSet<>();
    private final Runnable poll=this::poll;
    // JNI polling and JSON parsing never run on Android's main looper.
    // Distinct serial executor keeps long database reads from delaying approvals.
    private final ExecutorService poller=Executors.newSingleThreadExecutor();
    private final ExecutorService reads=Executors.newSingleThreadExecutor();
    private final AtomicBoolean polling=new AtomicBoolean();
    private volatile boolean active;

    private NotificationManager notifications;

    @Override public IBinder onBind(Intent intent){return null;}

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&ACTION_STOP.equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
        if(running)return START_NOT_STICKY;
        notifications=getSystemService(NotificationManager.class);channels();
        try{startForeground(RUNNING_ID,ongoing(getString(R.string.casca_starting)),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);}
        catch(RuntimeException refused){stopSelf();return START_NOT_STICKY;}
        try{
            JSONObject reply=McpNative.call(new JSONObject().put("op","start")
                .put("dir",new File(getFilesDir(),"mcp").getPath()).put("port",McpNative.DEFAULT_PORT));
            if(reply.has("error")&&!"already_running".equals(reply.getString("error")))throw new JSONException(reply.getString("error"));
            port=reply.optInt("port",McpNative.DEFAULT_PORT);
        }catch(JSONException|RuntimeException|LinkageError failure){stopSelf();return START_NOT_STICKY;}
        McpNative.log("app: servidor ligado na porta "+port+" (Gadgetbridge)");
        active=true;running=true;notifications.notify(RUNNING_ID,ongoing(getString(R.string.casca_running,port)));handler.post(poll);
        return START_NOT_STICKY;
    }

    @Override public void onDestroy(){
        boolean wasRunning=running;
        active=false;running=false;handler.removeCallbacksAndMessages(null);poller.shutdownNow();reads.shutdownNow();
        if(wasRunning){try{McpNative.op("stop");}catch(JSONException|RuntimeException|LinkageError ignored){}}
        if(notifications!=null)for(long id:shown)notifications.cancel(APPROVAL_BASE+(int)id);
        shown.clear();super.onDestroy();
    }

    /** Poll JNI off the UI thread. Never stack poll requests if the native hub is busy. */
    private void poll(){
        if(!active)return;
        if(polling.compareAndSet(false,true)){
            try{poller.execute(()->{
                try{
                    JSONArray pending=McpNative.op("status").getJSONArray("pending");
                    if(!active)return;
                    handler.post(()->showApprovals(pending));
                    runJobs();
                }catch(JSONException|RuntimeException|LinkageError ignored){}
                finally{polling.set(false);}
            });}
            catch(RejectedExecutionException rejected){polling.set(false);}
        }
        if(active)handler.postDelayed(poll,500);
    }

    /** Only notification state is touched on the main looper. */
    private void showApprovals(JSONArray pending){
        if(!active)return;
        try{
            Set<Long> now=new HashSet<>();
            for(int i=0;i<pending.length();i++){
                JSONObject p=pending.getJSONObject(i);long id=p.getLong("id");now.add(id);
                if(shown.add(id))notifications.notify(APPROVAL_BASE+(int)id,approval(id,p.optString("purpose","pairing"),p.getString("client"),p.getInt("code"),p.getString("peer")));
            }
            for(long id:new HashSet<>(shown))if(!now.contains(id)){shown.remove(id);notifications.cancel(APPROVAL_BASE+(int)id);}
        }catch(JSONException|RuntimeException ignored){}
    }

    /** Gadgetbridge device access stays on the UI thread; DB/Termux are queued
     * on a background executor. Each executor is FIFO; cross-executor completion
     * order is unspecified (jobs are identified by their native IDs). */
    private void runJobs()throws JSONException{
        if(!active)return;
        JSONArray jobs=McpNative.call(new JSONObject().put("op","jobs").put("prefixes",new JSONArray().put("band.").put("gb."))).getJSONArray("jobs");
        for(int i=0;i<jobs.length()&&active;i++){
            JSONObject job=jobs.getJSONObject(i);long id=job.getLong("id");
            JSONObject args=job.optJSONObject("args");if(args==null)args=new JSONObject();
            String kind=job.getString("kind");
            JSONObject finalArgs=args;
            if(CascaJobDispatch.mainThreadRequired(kind,args.optString("action",""))){
                handler.post(()->{
                    if(!active)return;
                    JSONObject result=compute(kind,finalArgs);
                    try{reads.execute(()->sendResult(id,result));}
                    catch(RejectedExecutionException ignored){}
                });
            }else{
                try{reads.execute(()->complete(id,kind,finalArgs));}
                catch(RejectedExecutionException ignored){}
            }
        }
    }

    private JSONObject compute(String kind,JSONObject args){
        try{return handle(kind,args);}
        catch(JSONException|RuntimeException failed){
            try{return error("Falha ao atender o pedido: "+failed.getClass().getSimpleName());}
            catch(JSONException impossible){throw new IllegalStateException(impossible);}
        }
    }
    private void complete(long id,String kind,JSONObject args){
        if(active)sendResult(id,compute(kind,args));
    }
    private void sendResult(long id,JSONObject result){
        if(!active)return;
        try{McpNative.call(new JSONObject().put("op","complete").put("id",id).put("result",result));}
        catch(JSONException|RuntimeException|LinkageError ignored){}
    }

    private JSONObject handle(String kind,JSONObject args)throws JSONException{
        switch(kind){
            case "gb.shell":{
                return CascaTermux.run(this,args);
            }
            case "gb.shell.result":{
                return CascaTermux.result(args);
            }
            case "gb.phone":{
                return "sensors".equals(args.optString("action"))
                    ?CascaPhone.sensors(this):CascaPhone.status(this);
            }
            case "gb.bluetooth":{
                return CascaBluetooth.handle(this,args);
            }
            case "gb.devices":{
                JSONArray list=new JSONArray();
                for(GBDevice device:devices())list.put(describe(device));
                list.put(new JSONObject().put("id","phone")
                    .put("type","android").put("model",android.os.Build.MODEL)
                    .put("provider","Android").put("operations",new JSONArray()
                        .put("gb_phone").put("gb_bluetooth").put("gb_shell").put("gb_shell_result")));
                return new JSONObject().put("devices",list)
                    .put("providers",new JSONArray().put("Gadgetbridge").put("Android"));
            }
            case "gb.discover":case "gb.inspect":case "gb.invoke":{
                return CascaGateway.handle(kind,args,devices());
            }
            case "band.devices":{
                JSONArray list=new JSONArray();
                for(GBDevice device:devices())list.put(describe(device));
                return new JSONObject().put("source","Gadgetbridge").put("devices",list);
            }
            case "band.lists":{
                GBDevice device=pick(args);if(device==null)return pickError(args);
                if("set".equals(args.optString("action","")))
                    return "reminders".equals(args.optString("kind",""))
                        ?CascaBandLists.setReminder(device,args):CascaBandLists.setAlarm(device,args);
                return CascaBandLists.read(device,args.optString("kind",""));
            }
            case "band.settings":{
                GBDevice device=pick(args);if(device==null)return pickError(args);
                return CascaBandSettings.handle(device,args);
            }
            case "band.capabilities":{
                GBDevice device=pick(args);if(device==null)return pickError(args);
                // Preferences hold configuration snapshots, not the complete on-wire protocol
                // declarations. Do not confuse missing entries with unsupported capabilities.
                SharedPreferences prefs=GBApplication.getDeviceSpecificSharedPrefs(device.getAddress());
                return new JSONObject().put("device",deviceId(device.getAddress()))
                    .put("source","Gadgetbridge: respostas de protocolo persistidas por dispositivo")
                    .put("config_api_level",prefs.contains("casca_zepp_config_api_level")?prefs.getInt("casca_zepp_config_api_level",0):JSONObject.NULL)
                    .put("config_groups",capabilitySet(prefs,"casca_zepp_config_groups"))
                    .put("declared_config_keys",capabilitySet(prefs,"casca_zepp_declared_config_keys"))
                    .put("service_endpoints",capabilitySet(prefs,"casca_zepp_service_endpoints"))
                    .put("menu_items",JSONObject.NULL).put("reminder_slots",JSONObject.NULL)
                    .put("contact_slots",JSONObject.NULL).put("installed_apps",JSONObject.NULL)
                    .put("note","null significa não observado; configuração declarada não garante disponibilidade de escrita.");
            }
            case "band.read":{
                GBDevice device=pick(args);if(device==null)return pickError(args);
                int hours=Math.max(1,Math.min(168,args.optInt("hours",24)));
                return new JSONObject().put("device",describe(device)).put("health",CascaHealth.read(device,hours));
            }
            case "band.notify":{
                List<GBDevice> ready=new ArrayList<>();
                for(GBDevice device:devices())if(device.isInitialized())ready.add(device);
                if(ready.isEmpty())return error("Nenhum aparelho conectado e inicializado no Gadgetbridge.");
                NotificationSpec spec=new NotificationSpec();
                spec.setTitle(args.optString("title"));spec.setBody(args.optString("body"));
                spec.setSender("Casca");spec.setSourceName("Casca");spec.setSourceAppId(getPackageName());
                spec.setType(NotificationType.UNKNOWN);
                JSONArray sent=new JSONArray();
                for(GBDevice device:ready){GBApplication.deviceService(device).onNotification(spec);sent.put(deviceId(device.getAddress()));}
                return new JSONObject().put("sent_to",sent);
            }
            case "band.find":{
                GBDevice device=pick(args);if(device==null)return pickError(args);
                if(!device.isInitialized())return error(device.getAliasOrName()+" não está conectado no Gadgetbridge.");
                int seconds=Math.max(1,Math.min(30,args.optInt("seconds",5)));
                GBApplication.deviceService(device).onFindDevice(true);
                handler.postDelayed(()->GBApplication.deviceService(device).onFindDevice(false),seconds*1000L);
                return new JSONObject().put("device",deviceId(device.getAddress())).put("vibrating",true).put("seconds",seconds);
            }
            case "band.sync":{
                GBDevice device=pick(args);if(device==null)return pickError(args);
                if(!device.isInitialized())return error(device.getAliasOrName()+" não está conectado no Gadgetbridge.");
                GBApplication.deviceService(device).onFetchRecordedData(RecordedDataTypes.TYPE_SYNC);
                return new JSONObject().put("device",deviceId(device.getAddress())).put("requested",true)
                    .put("note","A sincronização segue em segundo plano no Gadgetbridge.");
            }
            default:return error("Tarefa desconhecida para este app.");
        }
    }

    /** Stable ordering and null for unobserved capability groups. */
    private static Object capabilitySet(SharedPreferences prefs,String key){
        if(!prefs.contains(key))return JSONObject.NULL;
        List<String> values=new ArrayList<>(prefs.getStringSet(key,java.util.Collections.emptySet()));
        java.util.Collections.sort(values);return new JSONArray(values);
    }

    private static List<GBDevice> devices(){return GBApplication.app().getDeviceManager().getDevices();}

    /** The device named by args.device, else the only initialized one, else the only one. */
    private static GBDevice pick(JSONObject args){
        List<GBDevice> all=devices();
        String wanted=args.isNull("device")?null:args.optString("device",null);
        if(wanted!=null){for(GBDevice d:all)if(deviceId(d.getAddress()).equals(wanted))return d;return null;}
        List<GBDevice> ready=new ArrayList<>();for(GBDevice d:all)if(d.isInitialized())ready.add(d);
        if(ready.size()==1)return ready.get(0);
        return all.size()==1?all.get(0):null;
    }
    private static JSONObject pickError(JSONObject args)throws JSONException{
        if(!args.isNull("device")&&args.has("device"))return error("Aparelho não encontrado no Gadgetbridge; use band_devices.");
        return error(devices().isEmpty()?"Nenhum aparelho no Gadgetbridge.":"Há vários aparelhos; escolha um com band_devices e passe o id em device.");
    }

    private static JSONObject describe(GBDevice device)throws JSONException{
        int battery=device.getBatteryLevel(0);
        return new JSONObject().put("id",deviceId(device.getAddress())).put("name",device.getAliasOrName())
            .put("model",device.getModel()).put("type",String.valueOf(device.getType()))
            .put("state",String.valueOf(device.getState())).put("connected",device.isConnected())
            .put("initialized",device.isInitialized())
            .put("battery_percent",battery>=0&&battery<=100?battery:JSONObject.NULL)
            .put("firmware",device.getFirmwareVersion()==null?JSONObject.NULL:device.getFirmwareVersion());
    }

    /** Opaque and stable per device, the same id the Casca app used: the address never leaves the phone. */
    static String deviceId(String address){
        try{byte[] h=MessageDigest.getInstance("SHA-256").digest(address.toUpperCase().getBytes(StandardCharsets.UTF_8));
            StringBuilder b=new StringBuilder();for(int i=0;i<6;i++)b.append(String.format("%02x",h[i]));return b.toString();}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }

    private static JSONObject error(String message)throws JSONException{return new JSONObject().put("error",message);}

    private void channels(){
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL_RUNNING,getString(R.string.casca_channel_running),NotificationManager.IMPORTANCE_LOW));
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL_APPROVAL,getString(R.string.casca_channel_approval),NotificationManager.IMPORTANCE_HIGH));
    }

    private Notification ongoing(String text){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,CascaActivity.class),PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,CascaMcpService.class).setAction(ACTION_STOP),PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL_RUNNING).setSmallIcon(R.drawable.ic_notifications).setContentTitle(getString(R.string.casca_title))
            .setContentText(text).setOngoing(true).setContentIntent(open)
            .addAction(new Notification.Action.Builder(null,getString(R.string.casca_stop),stop).build()).build();
    }

    private Notification approval(long id,String purpose,String client,int code,String peer){
        boolean write="write_unlock".equals(purpose);
        // For write_unlock, "peer" carries the requested window ("30 min").
        String title=getString(write?R.string.casca_write_title:R.string.casca_approve_title);
        String text=write?getString(R.string.casca_write_text,client,peer):getString(R.string.casca_approve_text,client,code,peer);
        return new Notification.Builder(this,CHANNEL_APPROVAL).setSmallIcon(R.drawable.ic_notifications)
            .setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_STATUS).setAutoCancel(true).setTimeoutAfter(write?600_000:120_000)
            .addAction(new Notification.Action.Builder(null,getString(R.string.casca_allow),decision(id,true)).build())
            .addAction(new Notification.Action.Builder(null,getString(R.string.casca_deny),decision(id,false)).build()).build();
    }

    private PendingIntent decision(long id,boolean approve){
        Intent intent=new Intent(this,CascaDecisionReceiver.class).putExtra(CascaDecisionReceiver.EXTRA_ID,id).putExtra(CascaDecisionReceiver.EXTRA_APPROVE,approve);
        return PendingIntent.getBroadcast(this,(int)(id*2+(approve?1:0)),intent,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
    }
}

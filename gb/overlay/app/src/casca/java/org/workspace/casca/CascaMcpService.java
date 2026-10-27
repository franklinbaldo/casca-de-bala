package org.workspace.casca;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
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
    private final ExecutorService reads=Executors.newSingleThreadExecutor();
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
        running=true;notifications.notify(RUNNING_ID,ongoing(getString(R.string.casca_running,port)));handler.post(poll);
        return START_NOT_STICKY;
    }

    @Override public void onDestroy(){
        handler.removeCallbacksAndMessages(null);reads.shutdownNow();
        if(running){try{McpNative.op("stop");}catch(JSONException|RuntimeException|LinkageError ignored){}}
        running=false;
        if(notifications!=null)for(long id:shown)notifications.cancel(APPROVAL_BASE+(int)id);
        shown.clear();super.onDestroy();
    }

    /** One approve/deny notification per pending request; answered ones are cleared. */
    private void poll(){
        try{
            JSONArray pending=McpNative.op("status").getJSONArray("pending");Set<Long> now=new HashSet<>();
            for(int i=0;i<pending.length();i++){
                JSONObject p=pending.getJSONObject(i);long id=p.getLong("id");now.add(id);
                if(shown.add(id))notifications.notify(APPROVAL_BASE+(int)id,approval(id,p.optString("purpose","pairing"),p.getString("client"),p.getInt("code"),p.getString("peer")));
            }
            for(long id:new HashSet<>(shown))if(!now.contains(id)){shown.remove(id);notifications.cancel(APPROVAL_BASE+(int)id);}
        }catch(JSONException|RuntimeException|LinkageError ignored){}
        try{runJobs();}catch(JSONException|RuntimeException|LinkageError ignored){}
        handler.postDelayed(poll,500);
    }

    private void runJobs()throws JSONException{
        JSONArray jobs=McpNative.call(new JSONObject().put("op","jobs").put("prefixes",new JSONArray().put("band."))).getJSONArray("jobs");
        for(int i=0;i<jobs.length();i++){
            JSONObject job=jobs.getJSONObject(i);long id=job.getLong("id");
            JSONObject args=job.optJSONObject("args");if(args==null)args=new JSONObject();
            String kind=job.getString("kind");
            // Database reads stay off the main thread.
            if("band.read".equals(kind)){JSONObject a=args;reads.execute(()->complete(id,kind,a));continue;}
            complete(id,kind,args);
        }
    }

    private void complete(long id,String kind,JSONObject args){
        try{
            JSONObject result;
            try{result=handle(kind,args);}
            catch(JSONException|RuntimeException failed){result=error("Falha ao atender o pedido: "+failed.getClass().getSimpleName());}
            McpNative.call(new JSONObject().put("op","complete").put("id",id).put("result",result));
        }catch(JSONException|RuntimeException|LinkageError ignored){}
    }

    private JSONObject handle(String kind,JSONObject args)throws JSONException{
        switch(kind){
            case "band.devices":{
                JSONArray list=new JSONArray();
                for(GBDevice device:devices())list.put(describe(device));
                return new JSONObject().put("source","Gadgetbridge").put("devices",list);
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

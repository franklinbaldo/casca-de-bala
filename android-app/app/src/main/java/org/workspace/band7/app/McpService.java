package org.workspace.band7.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.Manifest;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.workspace.band7.BasicReadSession;
import org.workspace.band7.BasicSnapshot;
import org.workspace.band7.McpHub;

/** Keeps the phone's MCP server alive while the owner wants it on. The server
 * itself lives in Rust; this service owns its lifetime, the persistent
 * notification, the approve/deny notifications for pairing requests and the
 * device jobs AIs ask for (Bluetooth reads), which only the app can perform.
 */
public final class McpService extends Service {
    static final String ACTION_STOP="org.workspace.band7.app.MCP_STOP";
    static final String CHANNEL_RUNNING="mcp_running",CHANNEL_APPROVAL="mcp_approval";
    static final int RUNNING_ID=1,APPROVAL_BASE=1000;
    static volatile boolean running;static volatile int port;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Set<Long> shown=new HashSet<>();
    private final Runnable poll=this::poll;
    private NotificationManager notifications;
    private BasicGattReader jobReader;private long jobReaderId=-1;

    @Override public IBinder onBind(Intent intent){return null;}

    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&ACTION_STOP.equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
        if(running)return START_NOT_STICKY;
        notifications=getSystemService(NotificationManager.class);channels();
        try{startForeground(RUNNING_ID,ongoing(getString(R.string.mcp_starting)),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);}
        catch(RuntimeException refused){stopSelf();return START_NOT_STICKY;}
        try{
            JSONObject reply=new JSONObject(McpHub.start(new File(getFilesDir(),"mcp").getPath(),McpHub.DEFAULT_PORT));
            if(reply.has("error")&&!"already_running".equals(reply.getString("error")))throw new JSONException(reply.getString("error"));
            port=reply.optInt("port",McpHub.DEFAULT_PORT);
        }catch(JSONException|RuntimeException|LinkageError failure){stopSelf();return START_NOT_STICKY;}
        String ble;try{ble=BleNative.start(getApplicationContext());}catch(RuntimeException|LinkageError failed){ble="ERRO: "+failed;}
        try{McpHub.log("app: servidor ligado na porta "+port+"; laboratório BLE: "+ble);}catch(RuntimeException|LinkageError ignored){}
        running=true;notifications.notify(RUNNING_ID,ongoing(getString(R.string.mcp_running,port)));handler.post(poll);
        return START_NOT_STICKY;
    }

    @Override public void onDestroy(){
        handler.removeCallbacks(poll);
        if(jobReader!=null){jobReader.close();jobReader=null;}
        if(running){try{McpHub.stop();}catch(RuntimeException|LinkageError ignored){}}
        running=false;
        if(notifications!=null)for(long id:shown)notifications.cancel(APPROVAL_BASE+(int)id);
        shown.clear();super.onDestroy();
    }

    /** Shows one approve/deny notification per pending request; clears answered ones. */
    private void poll(){
        try{
            JSONArray pending=new JSONObject(McpHub.status()).getJSONArray("pending");Set<Long> now=new HashSet<>();
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
        // ble.* and lab.* belong to the Rust worker (BleNative); only band.* is ours.
        JSONArray jobs=new JSONObject(McpHub.jobs("band.")).getJSONArray("jobs");
        for(int i=0;i<jobs.length();i++){
            JSONObject job=jobs.getJSONObject(i);long id=job.getLong("id");JSONObject args=job.optJSONObject("args");
            switch(job.getString("kind")){
                case "band.devices":McpHub.complete(id,bandDevices());break;
                case "band.read":bandRead(id,args==null||args.isNull("device")?null:args.getString("device"));break;
                default:McpHub.complete(id,error("Tarefa desconhecida para este app."));
            }
        }
    }

    private static String error(String message){return "{\"error\":"+JSONObject.quote(message)+"}";}

    /** Opaque, stable per device; the Bluetooth address itself never leaves the phone. */
    static String deviceId(String address){
        try{byte[] h=MessageDigest.getInstance("SHA-256").digest(address.getBytes(StandardCharsets.UTF_8));
            StringBuilder b=new StringBuilder();for(int i=0;i<6;i++)b.append(String.format("%02x",h[i]));return b.toString();}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }

    // Each Bluetooth helper checks BLUETOOTH_CONNECT itself (lint requires it per method);
    // callers turn the SecurityException into a readable error for the AI.
    private String type(BluetoothDevice device){
        if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)throw new SecurityException("BLUETOOTH_CONNECT");
        switch(device.getType()){case BluetoothDevice.DEVICE_TYPE_LE:return "le";case BluetoothDevice.DEVICE_TYPE_DUAL:return "dual";case BluetoothDevice.DEVICE_TYPE_CLASSIC:return "classic";default:return "unknown";}
    }

    /** Bonded LE-capable devices only: the band reader speaks GATT over LE. */
    private List<BluetoothDevice> bonded(){
        if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)throw new SecurityException("BLUETOOTH_CONNECT");
        BluetoothManager manager=getSystemService(BluetoothManager.class);BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
        List<BluetoothDevice> found=new ArrayList<>();if(adapter==null||!adapter.isEnabled())return found;
        for(BluetoothDevice d:adapter.getBondedDevices())if(d.getType()!=BluetoothDevice.DEVICE_TYPE_CLASSIC)found.add(d);
        return found;
    }

    private JSONObject describe(BluetoothDevice d)throws JSONException{
        if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)throw new SecurityException("BLUETOOTH_CONNECT");
        String name=d.getName();return new JSONObject().put("id",deviceId(d.getAddress())).put("name",name==null?"(sem nome)":name).put("type",type(d));
    }

    private String bandDevices(){
        try{JSONArray list=new JSONArray();for(BluetoothDevice d:bonded())list.put(describe(d));
            return new JSONObject().put("devices",list).put("note","Somente vinculadas ao celular; o nome é anunciado pelo dispositivo e não comprova o modelo.").toString();}
        catch(SecurityException denied){return error("O Casca não tem permissão de Dispositivos próximos.");}
        catch(JSONException|RuntimeException failed){return error("Não foi possível listar as vinculadas.");}
    }

    private void bandRead(long id,String wanted){
        if(jobReader!=null){McpHub.complete(id,error("Já há uma leitura em andamento; tente de novo em alguns segundos."));return;}
        SharedPreferences prefs=getSharedPreferences("mcp",MODE_PRIVATE);BluetoothDevice target=null;List<BluetoothDevice> candidates;
        try{candidates=bonded();}catch(SecurityException denied){McpHub.complete(id,error("O Casca não tem permissão de Dispositivos próximos."));return;}
        String preferred=wanted!=null?wanted:prefs.getString("last_device",null);
        for(BluetoothDevice d:candidates)if(deviceId(d.getAddress()).equals(preferred))target=d;
        if(target==null&&wanted!=null){McpHub.complete(id,error("Pulseira não encontrada entre as vinculadas; use band_devices."));return;}
        if(target==null&&candidates.size()==1)target=candidates.get(0);
        if(target==null){McpHub.complete(id,error(candidates.isEmpty()?"Nenhum dispositivo Bluetooth LE vinculado ao celular (ou o Bluetooth está desligado).":"Há "+candidates.size()+" dispositivos vinculados; escolha um com band_devices e passe o id em device."));return;}
        final BluetoothDevice device=target;final String deviceKey=deviceId(device.getAddress());
        prefs.edit().putString("last_device",deviceKey).apply();jobReaderId=id;
        jobReader=new BasicGattReader(this,snapshot->{
            if(!snapshot.isFinal()||jobReaderId!=id)return;jobReader=null;jobReaderId=-1;
            try{byte[] reply=BasicSnapshot.process(snapshot,BasicReadSession.Origin.OUR_APP,id);McpHub.publish(reply);
                McpHub.complete(id,new JSONObject().put("device",describe(device)).put("reading",new JSONObject(new String(reply,StandardCharsets.UTF_8))).toString());}
            catch(JSONException|RuntimeException|LinkageError failed){McpHub.complete(id,error("A leitura terminou, mas não foi possível montar a resposta."));}
        });
        jobReader.connect(device);
    }

    private void channels(){
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL_RUNNING,getString(R.string.mcp_channel_running),NotificationManager.IMPORTANCE_LOW));
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL_APPROVAL,getString(R.string.mcp_channel_approval),NotificationManager.IMPORTANCE_HIGH));
    }

    private Notification ongoing(String text){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,McpService.class).setAction(ACTION_STOP),PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL_RUNNING).setSmallIcon(R.drawable.ic_stat_casca).setContentTitle(getString(R.string.mcp_title))
            .setContentText(text).setOngoing(true).setContentIntent(open)
            .addAction(new Notification.Action.Builder(null,getString(R.string.mcp_stop),stop).build()).build();
    }

    private Notification approval(long id,String purpose,String client,int code,String peer){
        boolean write="write_unlock".equals(purpose);
        // For write_unlock, "peer" carries the requested window ("30 min").
        String title=getString(write?R.string.mcp_write_title:R.string.mcp_approve_title);
        String text=write?getString(R.string.mcp_write_text,client,peer):getString(R.string.mcp_approve_text,client,code,peer);
        return new Notification.Builder(this,CHANNEL_APPROVAL).setSmallIcon(R.drawable.ic_stat_casca)
            .setContentTitle(title).setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_STATUS).setAutoCancel(true).setTimeoutAfter(120_000)
            .addAction(new Notification.Action.Builder(null,getString(R.string.mcp_allow),decision(id,true)).build())
            .addAction(new Notification.Action.Builder(null,getString(R.string.mcp_deny),decision(id,false)).build()).build();
    }

    private PendingIntent decision(long id,boolean approve){
        Intent intent=new Intent(this,McpDecisionReceiver.class).putExtra(McpDecisionReceiver.EXTRA_ID,id).putExtra(McpDecisionReceiver.EXTRA_APPROVE,approve);
        return PendingIntent.getBroadcast(this,(int)(id*2+(approve?1:0)),intent,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
    }
}

package org.workspace.band7.app;

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
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.workspace.band7.McpHub;

/** Keeps the phone's MCP server alive while the owner wants it on. The server
 * itself lives in Rust; this service owns its lifetime, the persistent
 * notification and the approve/deny notifications for pairing requests.
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
        running=true;notifications.notify(RUNNING_ID,ongoing(getString(R.string.mcp_running,port)));handler.post(poll);
        return START_NOT_STICKY;
    }

    @Override public void onDestroy(){
        handler.removeCallbacks(poll);
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
                if(shown.add(id))notifications.notify(APPROVAL_BASE+(int)id,approval(id,p.getString("client"),p.getInt("code"),p.getString("peer")));
            }
            for(long id:new HashSet<>(shown))if(!now.contains(id)){shown.remove(id);notifications.cancel(APPROVAL_BASE+(int)id);}
        }catch(JSONException|RuntimeException|LinkageError ignored){}
        handler.postDelayed(poll,1000);
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

    private Notification approval(long id,String client,int code,String peer){
        return new Notification.Builder(this,CHANNEL_APPROVAL).setSmallIcon(R.drawable.ic_stat_casca)
            .setContentTitle(getString(R.string.mcp_approve_title)).setContentText(getString(R.string.mcp_approve_text,client,code,peer))
            .setStyle(new Notification.BigTextStyle().bigText(getString(R.string.mcp_approve_text,client,code,peer)))
            .setCategory(Notification.CATEGORY_STATUS).setAutoCancel(true).setTimeoutAfter(120_000)
            .addAction(new Notification.Action.Builder(null,getString(R.string.mcp_allow),decision(id,true)).build())
            .addAction(new Notification.Action.Builder(null,getString(R.string.mcp_deny),decision(id,false)).build()).build();
    }

    private PendingIntent decision(long id,boolean approve){
        Intent intent=new Intent(this,McpDecisionReceiver.class).putExtra(McpDecisionReceiver.EXTRA_ID,id).putExtra(McpDecisionReceiver.EXTRA_APPROVE,approve);
        return PendingIntent.getBroadcast(this,(int)(id*2+(approve?1:0)),intent,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
    }
}

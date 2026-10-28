package org.workspace.casca;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import nodomain.freeyourgadget.gadgetbridge.R;
import org.json.JSONException;

/** "Casca MCP" launcher entry: turns the MCP server on and off and shows how
 * to pair an AI. The devices themselves are managed in Gadgetbridge. */
public final class CascaActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable refresh=this::render;
    private TextView info;private Button toggle;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(48,48,48,48);
        TextView title=new TextView(this);title.setText(R.string.casca_title);title.setTextSize(24);root.addView(title);
        info=new TextView(this);info.setTextSize(16);info.setTextIsSelectable(true);info.setPadding(0,32,0,32);root.addView(info);
        toggle=new Button(this);toggle.setOnClickListener(v->toggle());root.addView(toggle);
        Button termux=new Button(this);termux.setText("Preparar Termux");
        termux.setOnClickListener(v->prepareTermux());root.addView(termux);
        ScrollView scroll=new ScrollView(this);scroll.addView(root);setContentView(scroll);
    }

    @Override protected void onResume(){super.onResume();render();}
    @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}

    /** The owner confirms the official install and Android permission dialogs. */
    private void prepareTermux(){
        Intent launch=getPackageManager().getLaunchIntentForPackage("com.termux");
        if(launch==null){
            new android.app.AlertDialog.Builder(this).setTitle("Instalar Termux")
                .setMessage("Abra a página oficial do F-Droid para instalar o Termux. O Android exigirá sua confirmação; o Casca não instala outros apps silenciosamente.")
                .setPositiveButton("Abrir F-Droid",(dialog,which)->startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://f-droid.org/packages/com.termux/"))))
                .setNegativeButton("Cancelar",null).show();
            return;
        }
        String permission="com.termux.permission.RUN_COMMAND";
        boolean granted=checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED;
        new android.app.AlertDialog.Builder(this).setTitle("Preparar Termux")
            .setMessage("Termux instalado. Nele, configure ~/.termux/termux.properties com a linha allow-external-apps = true e reinicie o Termux. "
                +(granted?"Permissão Android RUN_COMMAND concedida.":"Falta conceder ao Casca a permissão adicional RUN_COMMAND."))
            .setPositiveButton(granted?"Abrir Termux":"Solicitar permissão",(dialog,which)->{
                if(granted)startActivity(launch);
                else requestPermissions(new String[]{permission},2);
            })
            .setNeutralButton("Configurações do Casca",(dialog,which)->startActivity(
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()))))
            .setNegativeButton("Fechar",null).show();
    }

    private void toggle(){
        if(CascaMcpService.running){startService(new Intent(this,CascaMcpService.class).setAction(CascaMcpService.ACTION_STOP));}
        else if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
            info.setText(R.string.casca_permission);requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},1);return;
        }else startForegroundService(new Intent(this,CascaMcpService.class));
        handler.postDelayed(refresh,700);
    }

    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){
        super.onRequestPermissionsResult(code,permissions,results);
        if(code==2){
            boolean granted=results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED;
            android.widget.Toast.makeText(this,granted?
                "Permissão concedida. Configure allow-external-apps no Termux.":
                "Permissão não concedida. Use as configurações do Android.",
                android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        if(code==1&&results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED){
            startForegroundService(new Intent(this,CascaMcpService.class));handler.postDelayed(refresh,700);
        }
    }

    private void render(){
        handler.removeCallbacks(refresh);
        if(!CascaMcpService.running){info.setText(R.string.casca_off);toggle.setText(R.string.casca_turn_on);return;}
        int port=CascaMcpService.port,clients=0;
        try{clients=McpNative.op("status").getJSONArray("clients").length();}
        catch(JSONException|RuntimeException|LinkageError ignored){}
        String ip=tailnetAddress();
        String where=ip==null?getString(R.string.casca_no_tailnet,port):"http://"+ip+":"+port;
        info.setText(getString(R.string.casca_on,where,clients,where));
        toggle.setText(R.string.casca_turn_off);
        handler.postDelayed(refresh,3000);
    }

    /** The phone's Tailscale IPv4 (100.64.0.0/10), if Tailscale is up. */
    private static String tailnetAddress(){
        try{
            for(NetworkInterface n:Collections.list(NetworkInterface.getNetworkInterfaces()))
                for(InetAddress a:Collections.list(n.getInetAddresses())){
                    byte[] b=a.getAddress();
                    if(a instanceof Inet4Address&&(b[0]&0xff)==100&&(b[1]&0xc0)==64)return a.getHostAddress();
                }
        }catch(Exception ignored){}
        return null;
    }
}

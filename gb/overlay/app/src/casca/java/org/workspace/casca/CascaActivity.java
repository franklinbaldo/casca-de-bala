package org.workspace.casca;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import nodomain.freeyourgadget.gadgetbridge.R;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONArray;

/** "Casca MCP" launcher entry: turns the MCP server on and off and shows how
 * to pair an AI. The devices themselves are managed in Gadgetbridge. */
public final class CascaActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable refresh=this::render;
    private TextView info;private Button toggle;
    private TextView shellInfo;private Button shellProbe;
    private ServiceConnection shellConnection;
    private int shellProbeGeneration;
    private final Messenger shellReplies = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override public void handleMessage(Message msg) {
            if (msg.what != CascaIsolatedShellService.MSG_RESULT ||
                    shellConnection == null || msg.arg1 != shellProbeGeneration) return;
            Bundle result = msg.getData();
            int uid = result.getInt("uid", -1);
            boolean isolated = result.getBoolean("isolated") && uid != Process.myUid();
            boolean privateDenied = !result.getBoolean("privateFileReadable", true);
            boolean shellWorks = result.containsKey("exitCode") && result.getInt("exitCode") == 0;
            String status = isolated && privateDenied && shellWorks
                    ? "PROVA LOCAL FAVORÁVEL (ainda precisa de teste instrumental)"
                    : "NÃO HABILITAR SHELL MCP: prova incompleta ou bloqueada";
            String details = "UID app=" + Process.myUid() + ", UID isolado=" + uid
                    + ", isolated=" + isolated + ", arquivo privado negado=" + privateDenied
                    + ", exit=" + result.getInt("exitCode", -1)
                    + ", timeout=" + result.getBoolean("timedOut")
                    + ", erro=" + result.getString("failure", "nenhum")
                    + "\nComando fixo (id; pwd):\n" + result.getString("output", "");
            if (isolated && privateDenied && shellWorks && CascaMcpService.running) {
                try {
                    JSONObject enabled = McpNative.call(new JSONObject().put("op", "shell_enable").put("enabled", true));
                    if (!enabled.optBoolean("enabled", false)) status = "Não foi possível habilitar Shell no MCP";
                } catch (JSONException | RuntimeException | LinkageError ignored) { status = "Não foi possível habilitar Shell no MCP"; }
            }
            finishShellProbe(status + "\n" + details);
        }
    });

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(48,48,48,48);
        TextView title=new TextView(this);title.setText(R.string.casca_title);title.setTextSize(24);root.addView(title);
        info=new TextView(this);info.setTextSize(16);info.setTextIsSelectable(true);info.setPadding(0,32,0,32);root.addView(info);
        toggle=new Button(this);toggle.setOnClickListener(v->toggle());root.addView(toggle);
        shellProbe=new Button(this);shellProbe.setText(R.string.casca_shell_probe_button);
        shellProbe.setOnClickListener(v->probeShell());root.addView(shellProbe);
        shellInfo=new TextView(this);shellInfo.setText(R.string.casca_shell_probe_warning);
        shellInfo.setTextIsSelectable(true);root.addView(shellInfo);
        ScrollView scroll=new ScrollView(this);scroll.addView(root);setContentView(scroll);
        handler.post(() -> showShellApproval(getIntent()));
    }

    @Override protected void onResume(){super.onResume();render();}
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent);setIntent(intent);showShellApproval(intent);
    }
    private void showShellApproval(Intent intent){
        if (intent == null || !intent.hasExtra("shell_approval_id")) return;
        long id = intent.getLongExtra("shell_approval_id", -1);
        intent.removeExtra("shell_approval_id");
        if (id <= 0) return;
        try {
            JSONArray pending = McpNative.op("shell_pending").getJSONArray("requests");
            for (int i = 0; i < pending.length(); i++) {
                JSONObject request = pending.getJSONObject(i);
                if (request.getLong("id") != id) continue;
                String details = "Cliente: " + request.getString("client")
                    + "\nEfeito: EXECUTAR comando no Shell isolado Android (sem ADB/root)"
                    + "\nLimite: " + request.getLong("timeout_ms") + " ms"
                    + "\n\nCOMANDO COMPLETO:\n" + request.getString("command");
                TextView full = new TextView(this);
                full.setText(details);full.setTextIsSelectable(true);full.setPadding(32,32,32,32);
                ScrollView view = new ScrollView(this);view.addView(full);
                new AlertDialog.Builder(this).setTitle("Autorizar comando Shell?")
                    .setView(view).setPositiveButton("Executar uma vez", (dialog, which) -> decideShell(id, true))
                    .setNegativeButton("Negar", (dialog, which) -> decideShell(id, false))
                    .setOnCancelListener(dialog -> decideShell(id, false)).show();
                return;
            }
            shellInfo.setText("Pedido Shell não encontrado ou expirado.");
        } catch (JSONException | RuntimeException | LinkageError failed) {
            shellInfo.setText("Não foi possível validar o pedido Shell; execução bloqueada.");
        }
    }
    private void decideShell(long id, boolean approve){
        try {
            JSONObject reply = McpNative.call(new JSONObject().put("op", "shell_decide")
                .put("id", id).put("approve", approve));
            shellInfo.setText(reply.has("error") ? "Pedido expirado ou recusado: " + reply.optString("error")
                : approve ? "Comando autorizado UMA VEZ; consulte gb_shell_result." : "Comando negado.");
        } catch (JSONException | RuntimeException | LinkageError failed) {
            shellInfo.setText("Não foi possível registrar a decisão; comando não autorizado.");
        }
    }
    @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
    @Override protected void onDestroy(){
        if (shellConnection != null) {
            unbindService(shellConnection);
            shellConnection = null;
        }
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** User-initiated, fixed-command proof. Never accepts a command from MCP. */
    private void probeShell(){
        if (shellConnection != null) return;
        shellProbe.setEnabled(false);
        shellProbeGeneration++;
        shellInfo.setText(R.string.casca_shell_probe_running);
        try (FileOutputStream out = new FileOutputStream(new File(
                getFilesDir(), CascaIsolatedShellService.SENTINEL))) {
            out.write("Casca isolation test; not a secret".getBytes(StandardCharsets.UTF_8));
        } catch (IOException | SecurityException failed) {
            finishShellProbe("Não foi possível criar o marcador privado para a prova.");
            return;
        }
        shellConnection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                if (shellConnection != this) return;
                Message request = Message.obtain(null, CascaIsolatedShellService.MSG_PROBE);
                request.arg1 = shellProbeGeneration;
                request.replyTo = shellReplies;
                Bundle evidence = new Bundle();
                evidence.putString("private_path", new File(getFilesDir(),
                    CascaIsolatedShellService.SENTINEL).getAbsolutePath());
                request.setData(evidence);
                try { new Messenger(binder).send(request); }
                catch (RemoteException failed) { finishShellProbe("Falha ao enviar teste ao processo isolado."); }
            }
            @Override public void onServiceDisconnected(ComponentName name) {
                if (shellConnection == this)
                    finishShellProbe("Processo isolado desconectado antes da resposta.");
            }
        };
        try {
            if (!bindService(new Intent(this, CascaIsolatedShellService.class),
                    shellConnection, Context.BIND_AUTO_CREATE)) {
                shellConnection = null;
                finishShellProbe("Android recusou iniciar o serviço isolado.");
                return;
            }
        } catch (RuntimeException denied) {
            // A failed bind does not establish a connection that can be unbound.
            shellConnection = null;
            finishShellProbe("O Android bloqueou o bind do serviço isolado.");
            return;
        }
        handler.postDelayed(() -> {
            if (shellConnection != null) finishShellProbe("Tempo esgotado aguardando o serviço isolado.");
        }, 8_000);
    }

    private void finishShellProbe(String result) {
        if (shellConnection != null) {
            unbindService(shellConnection);
            shellConnection = null;
        }
        if (!CascaMcpService.running)
            new File(getFilesDir(), CascaIsolatedShellService.SENTINEL).delete();
        shellInfo.setText(result);
        shellProbe.setEnabled(true);
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

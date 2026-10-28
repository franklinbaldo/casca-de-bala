package org.workspace.casca;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import java.util.UUID;
import org.json.JSONException;
import org.json.JSONObject;

/** A bounded, asynchronous Termux RUN_COMMAND bridge. No shell runs inside Casca. */
final class CascaTermux {
    private static final String PACKAGE="com.termux";
    private static final int MAX_OUTPUT_CHARS=8192;
    private static final CascaTermuxJobBook<JSONObject> jobs=new CascaTermuxJobBook<>();
    private CascaTermux(){}

    private static JSONObject error(String message)throws JSONException{
        return new JSONObject().put("error",message);
    }
    static synchronized JSONObject run(Context context,JSONObject args)throws JSONException{
        if(!McpNative.op("status").optBoolean("write_allowed",false))
            return error("Janela de execução não autorizada no Casca.");
        String command=args.optString("command","");
        String client=args.optString("client","");
        if(command.trim().isEmpty()||command.length()>4096||client.isEmpty())
            return error("Comando ou cliente MCP inválido.");
        if(context.getPackageManager().getLaunchIntentForPackage(PACKAGE)==null)
            return error("Termux não encontrado. Abra Preparar Termux.");
        if(context.checkSelfPermission("com.termux.permission.RUN_COMMAND")!=PackageManager.PERMISSION_GRANTED)
            return error("Permissão RUN_COMMAND ausente. Abra as permissões adicionais do Casca no Android.");
        String jobId=UUID.randomUUID().toString();
        CascaTermuxJobBook.Admission admission=jobs.reserve(jobId,client,SystemClock.elapsedRealtime());
        if(admission==CascaTermuxJobBook.Admission.IN_FLIGHT_LIMIT)
            return error("Limite de quatro comandos simultâneos atingido.");
        if(admission!=CascaTermuxJobBook.Admission.ACCEPTED)
            return error("Fila de resultados cheia; aguarde comandos pendentes.");
        Intent callback=new Intent(context,CascaTermuxResultReceiver.class)
            .setData(Uri.parse("casca-result:"+jobId)).putExtra("job_id",jobId);
        int flags=PendingIntent.FLAG_ONE_SHOT;
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S)flags|=PendingIntent.FLAG_MUTABLE;
        PendingIntent reply=PendingIntent.getBroadcast(context,0,callback,flags);
        Intent intent=new Intent("com.termux.RUN_COMMAND");
        intent.setClassName(PACKAGE,"com.termux.app.RunCommandService");
        intent.putExtra("com.termux.RUN_COMMAND_PATH","/data/data/com.termux/files/usr/bin/bash");
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS",new String[]{"-lc",command});
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND",true);
        intent.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT",reply);
        try{
            ComponentName sent=context.startService(intent);
            if(sent==null){jobs.cancel(jobId);reply.cancel();return error("Serviço RUN_COMMAND indisponível.");}
        }catch(RuntimeException failed){
            jobs.cancel(jobId);reply.cancel();
            return error("Termux recusou a solicitação: "+failed.getClass().getSimpleName());
        }
        return new JSONObject().put("job_id",jobId).put("status","submitted")
            .put("confirmed",false).put("note","Consulte gb_shell_result; o envio não confirma execução.");
    }

    static synchronized JSONObject result(JSONObject args)throws JSONException{
        String id=args.optString("job_id","");
        CascaTermuxJobBook.Lookup<JSONObject> lookup=jobs.lookup(id,args.optString("client",""),SystemClock.elapsedRealtime());
        if(lookup.status==CascaTermuxJobBook.Status.MISSING)
            return error("Job inexistente, expirado ou de outro cliente MCP.");
        if(lookup.status==CascaTermuxJobBook.Status.RUNNING)
            return new JSONObject().put("job_id",id).put("status","running").put("confirmed",false);
        return new JSONObject(lookup.value.toString()).put("job_id",id);
    }

    static synchronized void receive(Intent intent){
        if(intent==null)return;
        String id=intent.getStringExtra("job_id");
        if(!jobs.pending(id,SystemClock.elapsedRealtime()))return;
        try{
            Bundle result=intent.getBundleExtra("result");
            if(result==null){jobs.finish(id,error("Termux não retornou o pacote de resultados."),SystemClock.elapsedRealtime());return;}
            String stdout=result.getString("stdout","");
            String stderr=result.getString("stderr","");
            JSONObject output=new JSONObject().put("status","finished").put("confirmed",true)
                .put("stdout",clip(stdout)).put("stderr",clip(stderr))
                .put("stdout_truncated",stdout.length()>MAX_OUTPUT_CHARS)
                .put("stderr_truncated",stderr.length()>MAX_OUTPUT_CHARS)
                .put("stdout_original_length",result.get("stdout_original_length"))
                .put("stderr_original_length",result.get("stderr_original_length"))
                .put("exit_code",result.containsKey("exitCode")?result.getInt("exitCode"):JSONObject.NULL);
            if(result.containsKey("err")&&result.getInt("err")!=-1)
                output.put("error",result.getString("errmsg","Erro interno do Termux."));
            jobs.finish(id,output,SystemClock.elapsedRealtime());
        }catch(JSONException|RuntimeException ignored){
            try{jobs.finish(id,error("Falha ao interpretar resposta do Termux."),SystemClock.elapsedRealtime());}
            catch(JSONException alsoIgnored){}
        }
    }
    private static String clip(String text){
        return text.length()<=MAX_OUTPUT_CHARS?text:text.substring(0,MAX_OUTPUT_CHARS);
    }
}

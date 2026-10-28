package org.workspace.casca;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import org.json.JSONException;
import org.json.JSONObject;

/** Termux RUN_COMMAND bridge; never executes commands in the Casca process. */
final class CascaTermux {
    private CascaTermux(){}
    static JSONObject run(Context context,JSONObject args)throws JSONException{
        if(!McpNative.op("status").optBoolean("write_allowed",false))
            return error("Janela de execução não autorizada.");
        String command=args.optString("command","");
        if(command.trim().isEmpty()||command.length()>4096)return error("Comando inválido.");
        if(context.getPackageManager().getLaunchIntentForPackage("com.termux")==null)
            return error("Termux não instalado. Use Preparar Termux.");
        if(context.checkSelfPermission("com.termux.permission.RUN_COMMAND")!=PackageManager.PERMISSION_GRANTED)
            return error("Permissão RUN_COMMAND ausente; autorize o Casca nas configurações Android.");
        // The Android/Termux permission is not a substitute for the owner's MCP approval.
        if(!McpNative.op("status").optBoolean("write_allowed",false))
            return error("Autorização expirada.");
        Intent intent=new Intent("com.termux.RUN_COMMAND");
        intent.setClassName("com.termux","com.termux.app.RunCommandService");
        intent.putExtra("com.termux.RUN_COMMAND_PATH","/data/data/com.termux/files/usr/bin/bash");
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS",new String[]{"-lc",command});
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND",true);
        try{context.startService(intent);}
        catch(SecurityException|IllegalStateException failed){return error("Termux recusou a execução: "+failed.getClass().getSimpleName());}
        return new JSONObject().put("requested",true).put("confirmed",false)
            .put("note","Comando enviado ao Termux. Esta etapa não captura stdout/stderr nem confirma execução.");
    }
    private static JSONObject error(String message)throws JSONException{return new JSONObject().put("error",message);}
}

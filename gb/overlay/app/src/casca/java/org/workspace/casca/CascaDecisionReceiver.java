package org.workspace.casca;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import org.json.JSONException;
import org.json.JSONObject;

/** Approve/deny buttons of the pairing and write-unlock notifications. */
public final class CascaDecisionReceiver extends BroadcastReceiver {
    static final String EXTRA_ID="id",EXTRA_APPROVE="approve";
    @Override public void onReceive(Context context,Intent intent){
        long id=intent.getLongExtra(EXTRA_ID,-1);if(id<0)return;
        try{McpNative.call(new JSONObject().put("op","decide").put("id",id).put("approve",intent.getBooleanExtra(EXTRA_APPROVE,false)));}
        catch(JSONException|RuntimeException|LinkageError ignored){}
        context.getSystemService(NotificationManager.class).cancel(CascaMcpService.APPROVAL_BASE+(int)id);
    }
}

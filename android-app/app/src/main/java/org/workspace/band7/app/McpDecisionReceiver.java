package org.workspace.band7.app;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import org.workspace.band7.McpHub;

/** The owner's tap on Permitir/Negar; the waiting /pair request gets its answer. */
public final class McpDecisionReceiver extends BroadcastReceiver {
    static final String EXTRA_ID="id",EXTRA_APPROVE="approve";
    @Override public void onReceive(Context context,Intent intent){
        long id=intent.getLongExtra(EXTRA_ID,-1);if(id<0)return;
        try{McpHub.decide(id,intent.getBooleanExtra(EXTRA_APPROVE,false));}catch(RuntimeException|LinkageError ignored){}
        context.getSystemService(NotificationManager.class).cancel(McpService.APPROVAL_BASE+(int)id);
    }
}

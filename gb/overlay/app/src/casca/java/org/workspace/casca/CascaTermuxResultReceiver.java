package org.workspace.casca;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Explicit, non-exported PendingIntent endpoint for RUN_COMMAND results. */
public final class CascaTermuxResultReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){CascaTermux.receive(intent);}
}

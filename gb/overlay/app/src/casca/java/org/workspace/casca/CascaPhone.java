package org.workspace.casca;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import org.json.JSONException;
import org.json.JSONObject;

/** Read-only Android provider: deliberately omits serial, IMEI, accounts and identifiers. */
final class CascaPhone {
    private CascaPhone(){}
    static JSONObject status(Context context)throws JSONException{
        Intent battery=context.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        JSONObject power=new JSONObject();
        if(battery!=null){
            int level=battery.getIntExtra(BatteryManager.EXTRA_LEVEL,-1);
            int scale=battery.getIntExtra(BatteryManager.EXTRA_SCALE,-1);
            power.put("percent",level>=0&&scale>0?Math.round(level*100f/scale):JSONObject.NULL)
                .put("charging_status",battery.getIntExtra(BatteryManager.EXTRA_STATUS,-1));
        }
        StatFs storage=new StatFs(Environment.getDataDirectory().getAbsolutePath());
        return new JSONObject().put("id","phone").put("type","android")
            .put("manufacturer",Build.MANUFACTURER).put("model",Build.MODEL)
            .put("sdk",Build.VERSION.SDK_INT).put("battery",power)
            .put("storage",new JSONObject().put("total_bytes",storage.getTotalBytes())
                .put("available_bytes",storage.getAvailableBytes()))
            .put("source","Android APIs");
    }
}

package org.workspace.casca;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import org.json.JSONArray;
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
        JSONObject network=new JSONObject();
        ConnectivityManager connectivity=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network active=connectivity==null?null:connectivity.getActiveNetwork();
        NetworkCapabilities capabilities=active==null?null:connectivity.getNetworkCapabilities(active);
        if(capabilities!=null){
            network.put("wifi",capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                .put("cellular",capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
                .put("vpn",capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
                .put("ethernet",capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
                .put("validated",capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
        }
        return new JSONObject().put("id","phone").put("type","android")
            .put("manufacturer",Build.MANUFACTURER).put("model",Build.MODEL)
            .put("sdk",Build.VERSION.SDK_INT).put("battery",power)
            .put("network",capabilities==null?JSONObject.NULL:network)
            .put("storage",new JSONObject().put("total_bytes",storage.getTotalBytes())
                .put("available_bytes",storage.getAvailableBytes()))
            .put("source","Android APIs");
    }
    /** Inventory only; reading actual sensor values requires a separate approved flow. */
    static JSONObject sensors(Context context)throws JSONException{
        SensorManager manager=(SensorManager)context.getSystemService(Context.SENSOR_SERVICE);
        JSONArray list=new JSONArray();
        int total=0;
        if(manager!=null)for(Sensor sensor:manager.getSensorList(Sensor.TYPE_ALL)){
            total++;
            if(list.length()>=96)continue;
            list.put(new JSONObject().put("type",sensor.getType())
                .put("name",sensor.getName()).put("string_type",sensor.getStringType())
                .put("reporting_mode",sensor.getReportingMode()));
        }
        return new JSONObject().put("device","phone").put("sensors",list)
            .put("total",total).put("truncated",total>list.length())
            .put("note","Somente inventário; nenhuma amostra ou localização foi coletada.");
    }
}

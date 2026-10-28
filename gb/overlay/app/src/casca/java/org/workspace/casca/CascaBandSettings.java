package org.workspace.casca;

import android.content.SharedPreferences;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.activities.devicesettings.DeviceSettingsUtils;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.devices.huami.zeppos.services.ZeppOsConfigService.ConfigArg;
import org.json.JSONException;
import org.json.JSONObject;

/** ConfigArg booleans only: never expose arbitrary preferences or sensitive values. */
final class CascaBandSettings {
    private static final ConfigArg[] ALLOWED={
        ConfigArg.SCREEN_AUTO_BRIGHTNESS,ConfigArg.SCREEN_ON_ON_NOTIFICATIONS,
        ConfigArg.WORKOUT_KEEP_SCREEN_ON,ConfigArg.ALWAYS_ON_DISPLAY_FOLLOW_WATCHFACE,
        ConfigArg.COVER_TO_MUTE,ConfigArg.VIBRATE_FOR_ALERT,
        ConfigArg.HEART_RATE_ACTIVITY_MONITORING,ConfigArg.SLEEP_HIGH_ACCURACY_MONITORING,
        ConfigArg.STRESS_MONITORING,ConfigArg.SPO2_ALL_DAY_MONITORING,
        ConfigArg.INACTIVITY_WARNINGS_ENABLED,ConfigArg.INACTIVITY_WARNINGS_DND_ENABLED,
        ConfigArg.FITNESS_GOAL_NOTIFICATION,ConfigArg.CAMERA_REMOTE
    };
    private CascaBandSettings(){}

    private static ConfigArg find(String key){
        for(ConfigArg arg:ALLOWED)if(arg.getPrefKey().equals(key))return arg;
        return null;
    }

    private static boolean observed(SharedPreferences prefs,ConfigArg arg){
        return prefs.getBoolean(DeviceSettingsUtils.getPrefKnownConfig(arg.name()),false)
            && prefs.contains(arg.getPrefKey());
    }

    private static JSONObject view(SharedPreferences prefs,ConfigArg arg)throws JSONException{
        final boolean known=observed(prefs,arg);
        return new JSONObject().put("observed",known)
            .put("value",known?prefs.getBoolean(arg.getPrefKey(),false):JSONObject.NULL);
    }

    private static JSONObject error(String message)throws JSONException{
        return new JSONObject().put("error",message);
    }

    static JSONObject handle(GBDevice device,JSONObject args)throws JSONException{
        final String action=args.optString("action","");
        if(!"get".equals(action)&&!"set".equals(action))return error("Ação inválida.");
        final SharedPreferences prefs=GBApplication.getDeviceSpecificSharedPrefs(device.getAddress());
        final String key=args.isNull("key")?null:args.optString("key",null);
        final ConfigArg arg=key==null?null:find(key);
        if(key!=null&&arg==null)return error("Configuração não autorizada.");
        if("get".equals(action)){
            final JSONObject settings=new JSONObject();
            if(arg!=null)settings.put(key,view(prefs,arg));
            else for(ConfigArg candidate:ALLOWED)settings.put(candidate.getPrefKey(),view(prefs,candidate));
            return new JSONObject().put("device",CascaMcpService.deviceId(device.getAddress()))
                .put("settings",settings).put("source","Preferências confirmadas pelo Gadgetbridge")
                .put("note","observed=false significa desconhecido, não necessariamente sem suporte.");
        }
        // Defense in depth: a queued job may outlive the approved write window.
        if(!McpNative.op("status").optBoolean("write_allowed",false))return error("Escrita não autorizada no celular.");
        if(!device.isInitialized())return error("Pulseira desconectada ou não inicializada.");
        if(arg==null||!observed(prefs,arg))return error("Configuração não declarada pela pulseira.");
        if(!args.has("value")||args.isNull("value")||!(args.opt("value") instanceof Boolean))
            return error("O valor deve ser booleano.");
        final boolean value=args.getBoolean("value");
        if(!prefs.edit().putBoolean(key,value).commit())return error("Não foi possível guardar a configuração.");
        GBApplication.deviceService(device).onSendConfiguration(key);
        return new JSONObject().put("device",CascaMcpService.deviceId(device.getAddress()))
            .put("key",key).put("value",value).put("requested",true).put("confirmed",false)
            .put("note","Enviado ao Gadgetbridge; ainda sem confirmação da pulseira.");
    }
}

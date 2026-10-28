package org.workspace.casca;

import java.util.List;
import nodomain.freeyourgadget.gadgetbridge.database.DBHelper;
import nodomain.freeyourgadget.gadgetbridge.entities.Alarm;
import nodomain.freeyourgadget.gadgetbridge.entities.Reminder;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Cached alarms/reminders from Gadgetbridge's DB; never sends a BLE command. */
final class CascaBandLists {
    private CascaBandLists(){}

    static JSONObject read(GBDevice device,String kind)throws JSONException{
        final JSONArray items=new JSONArray();
        if("alarms".equals(kind)){
            final List<Alarm> alarms=DBHelper.getAlarms(device);
            for(Alarm alarm:alarms){
                items.put(new JSONObject()
                    .put("slot",alarm.getPosition())
                    .put("unused",alarm.getUnused())
                    .put("enabled",alarm.getEnabled())
                    .put("hour",alarm.getHour())
                    .put("minute",alarm.getMinute())
                    .put("title",alarm.getTitle())
                    .put("description",alarm.getDescription()));
            }
        }else if("reminders".equals(kind)){
            final List<Reminder> reminders=DBHelper.getReminders(device);
            for(Reminder reminder:reminders){
                items.put(new JSONObject()
                    .put("id",reminder.getReminderId())
                    .put("message",reminder.getMessage())
                    .put("at_unix_ms",reminder.getDate()==null?JSONObject.NULL:reminder.getDate().getTime())
                    .put("repetition",reminder.getRepetition()));
            }
        }else return new JSONObject().put("error","Tipo de lista inválido.");
        return new JSONObject().put("device",CascaMcpService.deviceId(device.getAddress()))
            .put("kind",kind).put("items",items).put("source","Banco local do Gadgetbridge")
            .put("note","Dados armazenados no celular; não confirma o estado atual no pulso.");
    }
}

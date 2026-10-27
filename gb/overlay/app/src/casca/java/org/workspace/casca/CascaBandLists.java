package org.workspace.casca;

import java.util.ArrayList;
import java.util.List;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
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


    /** Update one existing, used alarm. Never creates, deletes or changes other alarm fields. */
    static JSONObject setAlarm(GBDevice device,JSONObject args)throws JSONException{
        if(!device.isInitialized())return new JSONObject().put("error","Pulseira não inicializada.");
        if(!McpNative.op("status").optBoolean("write_allowed",false))
            return new JSONObject().put("error","Escrita não autorizada no celular.");
        if(!"alarms".equals(args.optString("kind"))||!"set".equals(args.optString("action")))
            return new JSONObject().put("error","Operação não permitida.");
        final int slot=args.optInt("slot",-1);
        final int total=device.getDeviceCoordinator().getAlarmSlotCount(device);
        if(slot<0||slot>=total)return new JSONObject().put("error","Slot de alarme fora da capacidade do aparelho.");
        final boolean hasEnabled=args.has("enabled"), hasHour=args.has("hour"), hasMinute=args.has("minute");
        if(!hasEnabled&&!hasHour&&!hasMinute)return new JSONObject().put("error","Nenhum campo solicitado.");
        if(hasEnabled&&!(args.opt("enabled") instanceof Boolean))
            return new JSONObject().put("error","enabled deve ser booleano.");
        final int hour=hasHour?args.optInt("hour",-1):-1;
        final int minute=hasMinute?args.optInt("minute",-1):-1;
        if((hasHour&&(hour<0||hour>23))||(hasMinute&&(minute<0||minute>59)))
            return new JSONObject().put("error","Horário inválido.");
        Alarm target=null;
        for(Alarm alarm:DBHelper.getAlarms(device)){
            if(alarm.getPosition()==slot){target=alarm;break;}
        }
        if(target==null||target.getUnused())
            return new JSONObject().put("error","Slot vazio; criação de alarmes ainda não liberada.");
        if(hasEnabled)target.setEnabled(args.getBoolean("enabled"));
        if(hasHour)target.setHour(hour);
        if(hasMinute)target.setMinute(minute);
        // Check again immediately before any database or device mutation.
        if(!McpNative.op("status").optBoolean("write_allowed",false))
            return new JSONObject().put("error","Janela de escrita expirada.");
        DBHelper.store(target);
        Alarm persisted=null;
        for(Alarm alarm:DBHelper.getAlarms(device)){
            if(alarm.getPosition()==slot){persisted=alarm;break;}
        }
        if(persisted==null||persisted.getEnabled()!=target.getEnabled()
                ||persisted.getHour()!=target.getHour()||persisted.getMinute()!=target.getMinute())
            return new JSONObject().put("error","Falha ao confirmar a gravação no banco local.");
        // Match Gadgetbridge's native alarm UI: deliver the full list, including unused slots.
        final ArrayList<Alarm> full=new ArrayList<>(DBHelper.getAlarmsWithDefaults(device));
        GBApplication.deviceService(device).onSetAlarms(full);
        return new JSONObject().put("device",CascaMcpService.deviceId(device.getAddress()))
            .put("kind","alarms").put("slot",slot).put("requested",true)
            .put("confirmed",false).put("source","Banco local do Gadgetbridge")
            .put("note","Atualização persistida e enviada ao Gadgetbridge; pulso não confirmou recebimento.");
    }
    /** Only edits the text of an existing reminder; no implicit creation/deletion. */
    static JSONObject setReminder(GBDevice device,JSONObject args)throws JSONException{
        if(!device.isInitialized())return new JSONObject().put("error","Pulseira não inicializada.");
        if(!McpNative.op("status").optBoolean("write_allowed",false))
            return new JSONObject().put("error","Escrita não autorizada.");
        if(!"set".equals(args.optString("action"))||!"reminders".equals(args.optString("kind")))
            return new JSONObject().put("error","Operação inválida.");
        long id=args.optLong("id",-1);String message=args.optString("message","");
        if(id<0||message.trim().isEmpty()||message.length()>200)
            return new JSONObject().put("error","Identificador ou mensagem inválidos.");
        Reminder target=null;
        for(Reminder item:DBHelper.getReminders(device))
            if(item.getReminderId()==id){target=item;break;}
        if(target==null)return new JSONObject().put("error","Lembrete não encontrado; criação não autorizada.");
        target.setMessage(message);
        if(!McpNative.op("status").optBoolean("write_allowed",false))
            return new JSONObject().put("error","Autorização expirada.");
        DBHelper.store(target);
        boolean found=false;
        for(Reminder item:DBHelper.getReminders(device))
            if(item.getReminderId()==id&&message.equals(item.getMessage())){found=true;break;}
        if(!found)return new JSONObject().put("error","Gravação local não confirmada.");
        GBApplication.deviceService(device).onSetReminders(new ArrayList<>(DBHelper.getReminders(device)));
        return new JSONObject().put("kind","reminders").put("requested",true).put("confirmed",false)
            .put("note","Gravado no banco e enviado ao Gadgetbridge; sem ACK da pulseira.");
    }

}

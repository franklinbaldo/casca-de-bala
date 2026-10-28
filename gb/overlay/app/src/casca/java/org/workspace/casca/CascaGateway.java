package org.workspace.casca;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Typed capability gateway: reflection discovers public coordinator methods, not arbitrary invocation. */
final class CascaGateway {
    private CascaGateway(){}
    private static final String[] OPERATIONS={"device.sync"};

    static JSONObject handle(String kind,JSONObject args,List<GBDevice> devices)throws JSONException{
        if("gb.inspect".equals(kind)){
            String op=args.optString("operation","");
            if(!known(op))return error("Operação não registrada.");
            return new JSONObject().put("operation",op).put("read_only",false)
                .put("parameters",new JSONObject().put("device","id opaco opcional"))
                .put("permission","cliente MCP autorizado");
        }
        if("gb.discover".equals(kind)){
            JSONArray found=new JSONArray();
            for(GBDevice device:devices){
                if(args.has("device")&&!args.isNull("device")
                        &&!CascaMcpService.deviceId(device.getAddress()).equals(args.optString("device")))continue;
                JSONArray methods=new JSONArray();
                for(Method method:device.getDeviceCoordinator().getClass().getMethods()){
                    String name=method.getName();
                    if(name.startsWith("supports")||name.startsWith("get"))
                        methods.put(new JSONObject().put("name",name)
                            .put("parameters",method.getParameterCount())
                            .put("discoverable_only",true));
                }
                found.put(new JSONObject().put("device",CascaMcpService.deviceId(device.getAddress()))
                    .put("coordinator_methods",methods).put("operations",new JSONArray(OPERATIONS)));
            }
            found.put(new JSONObject().put("device","phone").put("type","android")
                .put("operations",new JSONArray())
                .put("tools",new JSONArray().put("gb_phone").put("gb_shell")
                    .put("gb_shell_result").put("gb_bluetooth")));
            return new JSONObject().put("devices",found)
                .put("note","Métodos descobertos não são automaticamente invocáveis.");
        }
        if(!"gb.invoke".equals(kind))return error("Operação desconhecida.");
        String op=args.optString("operation","");
        if(!known(op))return error("Operação não autorizada no gateway.");
        JSONObject parameters=args.optJSONObject("args");
        if(parameters==null)parameters=new JSONObject();
        for(java.util.Iterator<String> keys=parameters.keys();keys.hasNext();)
            if(!"device".equals(keys.next()))return error("Parâmetro desconhecido.");
        GBDevice selected=null;
        for(GBDevice device:devices){
            if(parameters.has("device")&&!CascaMcpService.deviceId(device.getAddress()).equals(parameters.optString("device")))continue;
            if(selected!=null)return error("Escolha um dispositivo explicitamente.");
            selected=device;
        }
        if(selected==null||!selected.isInitialized())return error("Dispositivo indisponível.");
        if("device.sync".equals(op)){
            GBApplication.deviceService(selected).onFetchRecordedData(nodomain.freeyourgadget.gadgetbridge.model.RecordedDataTypes.TYPE_SYNC);
        }else{
            return error("Localização requer temporização de parada; use band_find até o adaptador estar pronto.");
        }
        return new JSONObject().put("requested",true).put("confirmed",false)
            .put("device",CascaMcpService.deviceId(selected.getAddress()));
    }
    private static boolean known(String operation){
        for(String item:OPERATIONS)if(item.equals(operation))return true;
        return false;
    }
    private static JSONObject error(String message)throws JSONException{return new JSONObject().put("error",message);}
}

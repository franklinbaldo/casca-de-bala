package org.workspace.casca;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.pm.PackageManager;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Android Bluetooth inventory. Discovery and pairing require explicit platform permissions. */
final class CascaBluetooth {
    private CascaBluetooth(){}
    static JSONObject handle(Context context,JSONObject args)throws JSONException{
        BluetoothManager manager=context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
        if(adapter==null)return error("Bluetooth indisponível.");
        if(context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)
            return error("Permissão BLUETOOTH_CONNECT ausente; conceda pelo Android.");
        String action=args.optString("action","");
        if("status".equals(action))return new JSONObject().put("enabled",adapter.isEnabled())
            .put("discovering",adapter.isDiscovering()).put("state",adapter.getState());
        if("paired".equals(action)){
            JSONArray list=new JSONArray();Set<BluetoothDevice> devices=adapter.getBondedDevices();
            for(BluetoothDevice d:devices)list.put(new JSONObject().put("name",d.getName())
                .put("id",CascaMcpService.deviceId(d.getAddress()))
                .put("bond_state",d.getBondState()));
            return new JSONObject().put("devices",list).put("source","Android BluetoothAdapter");
        }
        if("scan".equals(action))return error("Varredura ainda requer sessão e coletor de resultados; não iniciada.");
        if("pair".equals(action))return error("Pareamento exige confirmação nativa e seleção de aparelho descoberto; não iniciado.");
        return error("Ação Bluetooth inválida.");
    }
    private static JSONObject error(String message)throws JSONException{return new JSONObject().put("error",message);}
}

package org.workspace.band7.spike;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Shell only: a button, a text, the permission dialog. No Bluetooth code here. */
public final class MainActivity extends Activity {
    private TextView output;private Button start;
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(32,96,32,32);
        start=new Button(this);start.setText("Testar btleplug (Rust)");root.addView(start);
        output=new TextView(this);output.setTextIsSelectable(true);output.setTextSize(15);
        output.setText("Mantenha a pulseira por perto. O teste conecta em cada dispositivo LE vinculado (até 4) e lê bateria e revisões.");
        ScrollView scroll=new ScrollView(this);scroll.addView(output);root.addView(scroll);setContentView(root);
        start.setOnClickListener(v->begin());
    }
    private void begin(){
        if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN},1);return;}
        start.setEnabled(false);output.setText("Rodando…");
        // A Java thread, so btleplug::platform::init sees the app's class loader.
        new Thread(()->{
            String result;
            try{result=Native.run(getApplicationContext());}catch(Throwable t){result="Exceção: "+t;}
            String text=result;runOnUiThread(()->{output.setText(text);start.setEnabled(true);});
        },"spike-btleplug").start();
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){
        super.onRequestPermissionsResult(code,permissions,results);
        if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED)begin();
        else output.setText("Sem Dispositivos próximos o teste não roda.");
    }
}

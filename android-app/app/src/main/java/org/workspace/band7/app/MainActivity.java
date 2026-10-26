package org.workspace.band7.app;

import android.Manifest;
import android.app.*;
import android.bluetooth.BluetoothDevice;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import org.json.*;
import org.workspace.band7.*;
import static org.workspace.band7.ReadViewModel.*;

public final class MainActivity extends Activity {
    private enum Screen { HOME, SEARCH, DEVICE, PERMISSION, OFF, CHANGING, UNAVAILABLE, SEARCH_ERROR, RADAR }
    private enum Route { BONDED, NEARBY }
    private Route route=Route.BONDED;private Screen screen=Screen.HOME;private boolean resumed,askedConnect,askedScan,interrupted;
    private final ForegroundAction findIntent=new ForegroundAction();private int homeMessage;
    private BleDiscovery discovery;private BasicGattReader reader;private Snapshot snapshot;
    private BluetoothDevice selectedDevice;private String selectedName;private long readAttempt,rowGeneration=-1,diagnosticRequestId;
    private DiscoverySession.Status searchStatus=DiscoverySession.Status.CANCELLED;
    private RadarVisualModel.Palette colors;private ScrollView page;private TextView subtitle;
    private RadarPulseView radarPulse;private FrameLayout radarDial;private TextView radarMetric,radarCounter,radarBest,radarGuide;private ProgressBar radarSamples;
    private int rowCount;private TextView title,status,body,battery,hardware,software,time,device;
    private Button primary,secondary,cancel,details,diagnostic;private ProgressBar progress;private LinearLayout rows,batteryCard,infoCard;
    private RadarGatt radarReader;private RadarSession.Snapshot radarSnapshot;private boolean selectingRadar;
    private Button radarOpen,radarMeasure,radarEnd,radarChoose;private LinearLayout radarPanel,radarPoints;
    private Button mcpToggle;private TextView mcpInfo;private Snapshot mcpPublished;private long mcpPublishId;private final Runnable mcpRefresh=this::renderMcp;
    private static volatile String tailnetName;private static volatile boolean resolvingName;
    private TextView radarStatus,radarHelp;private int renderedRadarPoints=-1;private long renderedRadarGeneration=-1;
    private final Map<Integer,Button> rowButtons=new HashMap<>();private final Map<Integer,DiscoverySession.Row> rowData=new HashMap<>();private Runnable primaryAction,secondaryAction;
    /** In-process terminal entry point for a finished cached read. No new GATT IO.
     * Caller must run on the UI thread. Returns caller-owned UTF-8 envelope bytes;
     * malformed data returns the Rust error envelope. No remote transport is installed.
     */
    byte[] snapshotForTerminal(long requestId){
        if(android.os.Looper.myLooper()!=android.os.Looper.getMainLooper())
            throw new IllegalStateException("Snapshot requires UI thread");
        if(snapshot==null)throw new IllegalStateException("No basic snapshot");
        return BasicSnapshot.process(snapshot,BasicReadSession.Origin.OUR_APP,requestId);
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);askedConnect=state!=null&&state.getBoolean("asked_connect",false);askedScan=state!=null&&state.getBoolean("asked_scan",false);
        colors=RadarVisualModel.palette((getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES);
        // Screenshots are intentionally permitted. No FLAG_SECURE or identifier input.
        ScrollView scroll=new ScrollView(this);page=scroll;scroll.setFillViewport(true);scroll.setBackgroundColor(colors.background);
        getWindow().setDecorFitsSystemWindows(false);scroll.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
        LinearLayout root=column();root.setPadding(dp(16),dp(12),dp(16),dp(20));scroll.addView(root);
        title=text(root,R.string.home_title,30);title.setAccessibilityHeading(true);
        subtitle=text(root,R.string.home_subtitle,18);space(root,16);
        status=text(root,R.string.home_title,22);status.setAccessibilityHeading(true);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        body=text(root,R.string.home_subtitle,17);progress=new ProgressBar(this);root.addView(progress);progress.setVisibility(View.GONE);
        primary=button(root,R.string.find);secondary=button(root,R.string.choose_another);cancel=button(root,R.string.cancel);
        primary.setOnClickListener(v->{if(primaryAction!=null)primaryAction.run();});secondary.setOnClickListener(v->{if(secondaryAction!=null)secondaryAction.run();});cancel.setOnClickListener(v->cancelCurrent());
        radarOpen=button(root,R.string.radar_open);radarOpen.setOnClickListener(v->beginRadar());
        rows=column();root.addView(rows);space(root,16);
        radarPanel=column();root.addView(radarPanel);
        radarGuide=text(radarPanel,R.string.live_guide,17);
        radarDial=new FrameLayout(this);radarPanel.addView(radarDial,new LinearLayout.LayoutParams(-1,dp(180)));
        radarPulse=new RadarPulseView(this,colors);radarDial.addView(radarPulse,new FrameLayout.LayoutParams(-1,-1));
        radarMetric=new TextView(this);radarMetric.setGravity(Gravity.CENTER);radarMetric.setTextColor(colors.text);radarMetric.setMaxLines(3);
        radarMetric.setAutoSizeTextTypeUniformWithConfiguration(18,44,1,android.util.TypedValue.COMPLEX_UNIT_SP);radarMetric.setPadding(dp(44),dp(12),dp(44),dp(12));radarMetric.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        radarDial.addView(radarMetric,new FrameLayout.LayoutParams(-1,-1));radarDial.setFocusable(true);radarDial.setOnClickListener(v->toggleRadar());
        text(radarPanel,R.string.live_no_position,16);
        radarStatus=text(radarPanel,R.string.radar_connecting,28);radarStatus.setTypeface(null,android.graphics.Typeface.BOLD);radarStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        radarCounter=text(radarPanel,R.string.live_waiting,18);radarSamples=signalBar(radarPanel);radarSamples.setMax(6);
        radarMeasure=button(radarPanel,R.string.live_pause);radarMeasure.setBackgroundTintList(android.content.res.ColorStateList.valueOf(colors.accent));radarMeasure.setTextColor(colors.onAccent);radarMeasure.setOnClickListener(v->toggleRadar());
        radarHelp=text(radarPanel,R.string.radar_help,17);
        radarEnd=button(radarPanel,R.string.live_home);radarEnd.setOnClickListener(v->leaveRadar());
        radarChoose=button(radarPanel,R.string.choose_another);radarChoose.setOnClickListener(v->beginRadar());
        radarBest=text(radarPanel,R.string.live_best_pending,20);radarBest.setAccessibilityHeading(true);
        text(radarPanel,R.string.live_history,22).setAccessibilityHeading(true);radarPoints=column();radarPanel.addView(radarPoints);
        device=text(root,R.string.home_title,18);batteryCard=card(root);text(batteryCard,R.string.battery,18).setAccessibilityHeading(true);
        battery=text(batteryCard,R.string.waiting,34);infoCard=card(root);text(infoCard,R.string.information,20).setAccessibilityHeading(true);
        text(infoCard,R.string.hardware,16);hardware=text(infoCard,R.string.not_available,20);
        text(infoCard,R.string.software,16);software=text(infoCard,R.string.not_available,20);
        time=text(root,R.string.waiting,15);details=button(root,R.string.technical);details.setOnClickListener(v->showDetails());
        diagnostic=button(root,R.string.snapshot_action);diagnostic.setOnClickListener(v->showSnapshot());
        space(root,16);mcpInfo=text(root,R.string.mcp_off_help,16);mcpInfo.setTextIsSelectable(true);mcpToggle=button(root,R.string.mcp_turn_on);mcpToggle.setOnClickListener(v->toggleMcp());
        button(root,R.string.huami_action).setOnClickListener(v->startActivity(new Intent(this,HuamiLoginActivity.class)));
        space(root,16);text(root,R.string.version,14).setText(getString(R.string.version,versionName()));
        Button license=button(root,R.string.license_action);license.setOnClickListener(v->showLicense());setContentView(scroll);
        discovery=new BleDiscovery(this,new DiscoverySession.Observer(){
            public void changed(long token,DiscoverySession.Status value,List<DiscoverySession.Row> found){
                searchStatus=value;rowCount=found.size();
                if(rowGeneration!=token){rows.removeAllViews();rowButtons.clear();rowData.clear();rowGeneration=token;}
                if(found.isEmpty()){rows.removeAllViews();rowButtons.clear();rowData.clear();}
                for(DiscoverySession.Row row:found){
                    rowData.put(row.id,row);
                    Button b=rowButtons.get(row.id);if(b==null){b=button(rows,R.string.choose_title);final int id=row.id;final long generation=token;b.setOnClickListener(v->selectRow(generation,id));rowButtons.put(id,b);}
                    String name=row.label.isEmpty()?getString(R.string.unnamed):row.label;
                    String line=row.rssi==null?getString(R.string.row,name,row.id):getString(R.string.row_signal,name,row.id,row.rssi);
                    int origin=row.bonded?(row.seenNow?R.string.origin_both:R.string.origin_bonded):R.string.origin_found;
                    String source=getString(origin);if(row.kind==DiscoverySession.Kind.UNKNOWN&&!row.seenNow)source=getString(R.string.unknown_row,source);
                    set(b,getString(R.string.row_origin,line,source));
                }
                if(!resumed)return;
                switch(value){
                    case SELECTED:return;
                    case PERMISSION_DENIED:screen=Screen.PERMISSION;findIntent.cancel();break;
                    case BLUETOOTH_OFF:screen=Screen.OFF;break;
                    case BLUETOOTH_CHANGING:screen=Screen.CHANGING;break;
                    case UNAVAILABLE:screen=Screen.UNAVAILABLE;break;
                    case FAILED:screen=Screen.SEARCH_ERROR;break;
                    case CANCELLED:screen=snapshot==null?Screen.HOME:Screen.DEVICE;homeMessage=R.string.search_cancelled;break;
                    default:screen=Screen.SEARCH;break;
                }
                render();
            }
            public void selected(String address,String name){selectedDevice=discovery.device(address);selectedName=name.isEmpty()?getString(R.string.unnamed):name;if(selectingRadar)startRadar();else startRead();}
        });Object retained=getLastNonConfigurationInstance();if(retained instanceof RadarSession.Snapshot){radarSnapshot=(RadarSession.Snapshot)retained;screen=Screen.RADAR;}render();
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private void space(LinearLayout parent,int height){View v=new View(this);parent.addView(v,new LinearLayout.LayoutParams(1,dp(height)));}
    private TextView text(LinearLayout parent,int resource,int size){TextView t=new TextView(this);t.setText(resource);t.setTextSize(size);t.setTextColor(colors.text);t.setPadding(0,dp(5),0,dp(5));parent.addView(t,new LinearLayout.LayoutParams(-1,-2));return t;}
    private Button button(LinearLayout parent,int resource){Button b=new Button(this);b.setText(resource);b.setAllCaps(false);b.setTextColor(colors.text);b.setMinHeight(dp(56));b.setPadding(dp(12),dp(8),dp(12),dp(8));parent.addView(b,new LinearLayout.LayoutParams(-1,-2));return b;}
    private LinearLayout card(LinearLayout root){LinearLayout c=column();c.setPadding(dp(16),dp(12),dp(16),dp(12));GradientDrawable bg=new GradientDrawable();bg.setColor(colors.surface);bg.setStroke(dp(1),colors.track);bg.setCornerRadius(dp(12));c.setBackground(bg);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(8),0,dp(8));root.addView(c,p);return c;}
    private void set(TextView view,int resource){set(view,getString(resource));}
    private void set(TextView view,String value){if(!view.getText().toString().equals(value))view.setText(value);}
    private PermissionDecision.State permission(String name,boolean asked){return new PermissionDecision.State(checkSelfPermission(name)==PackageManager.PERMISSION_GRANTED,asked,shouldShowRequestPermissionRationale(name));}
    private PermissionDecision.Action permissionAction(){return PermissionDecision.discovery(route==Route.NEARBY,permission(Manifest.permission.BLUETOOTH_CONNECT,askedConnect),permission(Manifest.permission.BLUETOOTH_SCAN,askedScan));}
    private boolean granted(){return permissionAction()==PermissionDecision.Action.CONTINUE;}
    private void beginFind(){beginFind(Route.BONDED,false);}
    private void beginFind(Route next){beginFind(next,false);}
    private void beginRadar(){beginFind(Route.BONDED,true);}
    private void beginFind(Route next,boolean radar){
        readAttempt++;if(reader!=null){reader.close();reader=null;}if(radarReader!=null){radarReader.stop(RadarSession.Reason.CANCELLED);radarReader=null;}discovery.close();selectedDevice=null;route=next;selectingRadar=radar;
        if(findIntent.clicked(granted())){findNow();return;}screen=Screen.PERMISSION;render();
        if(permissionAction()==PermissionDecision.Action.REQUEST)
            new AlertDialog.Builder(this).setTitle(R.string.permission_title).setMessage(route==Route.BONDED?R.string.bonded_permission_why:R.string.permission_why).setPositiveButton(R.string.allow,(d,w)->requestNearby()).setNegativeButton(R.string.cancel,(d,w)->{findIntent.cancel();render();}).show();
    }
    private void requestNearby(){
        findIntent.external(ForegroundAction.Wait.PERMISSION);List<String> missing=new ArrayList<>();
        String[] required=route==Route.BONDED?new String[]{Manifest.permission.BLUETOOTH_CONNECT}:new String[]{Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN};
        for(String p:required)if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED){missing.add(p);if(p.equals(Manifest.permission.BLUETOOTH_CONNECT))askedConnect=true;else askedScan=true;}
        if(missing.isEmpty()){if(findIntent.permissionResult(true))findNow();return;}requestPermissions(missing.toArray(new String[0]),41);
    }
    private void selectRow(long token,int id){
        if(token!=rowGeneration)return;DiscoverySession.Row row=rowData.get(id);if(row==null)return;
        if(row.kind==DiscoverySession.Kind.UNKNOWN&&!row.seenNow)
            new AlertDialog.Builder(this).setTitle(R.string.unknown_title).setMessage(R.string.unknown_help).setPositiveButton(R.string.try_read,(d,w)->discovery.select(token,id,true)).setNegativeButton(R.string.cancel,null).show();
        else discovery.select(token,id,false);
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){
        super.onRequestPermissionsResult(code,permissions,results);if(code==42){if(mcpPermissions().isEmpty())toggleMcp();else set(mcpInfo,R.string.mcp_permission);return;}if(code!=41)return;
        if(findIntent.permissionResult(granted()))findNow();
        else if(!granted()){screen=Screen.PERMISSION;render();}
    }
    private void findNow(){if(!resumed)return;interrupted=false;homeMessage=0;screen=Screen.SEARCH;if(route==Route.BONDED)discovery.bonded();else discovery.search();}
    private void startRead(){
        if(!resumed)return;screen=Screen.DEVICE;interrupted=false;long attempt=++readAttempt;
        if(reader!=null)reader.close();snapshot=null;
        reader=new BasicGattReader(this,value->{if(attempt!=readAttempt)return;snapshot=value;publishMcp(value);if(resumed)render();});reader.connect(selectedDevice);
    }
    private void startRadar(){
        if(!resumed)return;screen=Screen.RADAR;interrupted=false;long attempt=++readAttempt;
        if(reader!=null){reader.close();reader=null;}if(radarReader!=null)radarReader.stop(RadarSession.Reason.CANCELLED);
        radarSnapshot=null;radarReader=new RadarGatt(this,value->{if(attempt!=readAttempt)return;radarSnapshot=value;if(resumed)render();});radarReader.connect(selectedDevice);page.post(()->page.smoothScrollTo(0,0));
    }
    private void cancelCurrent(){findIntent.cancel();if(screen==Screen.SEARCH){discovery.close();return;}if(reader!=null)reader.close(Cause.CANCELLED);}
    private void openSettings(boolean bluetooth){findIntent.external(ForegroundAction.Wait.SETTINGS);Intent intent=bluetooth?new Intent(Settings.ACTION_BLUETOOTH_SETTINGS):new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()));startActivity(intent);}
    @Override protected void onResume(){super.onResume();resumed=true;renderMcp();
        if(findIntent.resume(granted())){findNow();return;}render();
    }
    @Override protected void onStop(){resumed=false;if(page!=null)page.removeCallbacks(mcpRefresh);interrupted=true;findIntent.stop();if(discovery!=null)discovery.close();if(reader!=null)reader.close(Cause.BACKGROUND);if(radarReader!=null)radarReader.stop(RadarSession.Reason.BACKGROUND);
        if(!findIntent.waiting()){selectedDevice=null;if(screen!=Screen.RADAR)screen=snapshot==null?Screen.HOME:Screen.DEVICE;homeMessage=R.string.background_help;}super.onStop();}
    @Override protected void onSaveInstanceState(Bundle out){out.putBoolean("asked_connect",askedConnect);out.putBoolean("asked_scan",askedScan);super.onSaveInstanceState(out);}
    private void render(){
        title.setTextSize(screen==Screen.RADAR?18:30);subtitle.setVisibility(screen==Screen.RADAR?View.GONE:View.VISIBLE);status.setVisibility(screen==Screen.RADAR?View.GONE:View.VISIBLE);body.setVisibility(screen==Screen.RADAR?View.GONE:View.VISIBLE);
        boolean reading=screen==Screen.DEVICE&&snapshot!=null&&(snapshot.stage==Stage.CONNECTING||snapshot.stage==Stage.DISCOVERING||snapshot.stage==Stage.READING);
        boolean searching=screen==Screen.SEARCH&&(searchStatus==DiscoverySession.Status.SEARCHING||searchStatus==DiscoverySession.Status.READING_BONDED);
        progress.setVisibility(reading||searching?View.VISIBLE:View.GONE);cancel.setVisibility(reading||searching?View.VISIBLE:View.GONE);
        secondary.setVisibility(View.GONE);primary.setVisibility(reading||screen==Screen.RADAR?View.GONE:View.VISIBLE);primaryAction=()->beginFind(route,selectingRadar);set(primary,R.string.bonded);
        rows.setVisibility(screen==Screen.SEARCH?View.VISIBLE:View.GONE);radarPanel.setVisibility(screen==Screen.RADAR?View.VISIBLE:View.GONE);radarOpen.setVisibility(screen==Screen.HOME||screen==Screen.DEVICE&&!reading?View.VISIBLE:View.GONE);
        boolean show=screen==Screen.DEVICE&&snapshot!=null;diagnostic.setVisibility(show&&snapshot.isFinal()?View.VISIBLE:View.GONE);device.setVisibility(show?View.VISIBLE:View.GONE);batteryCard.setVisibility(show?View.VISIBLE:View.GONE);infoCard.setVisibility(show?View.VISIBLE:View.GONE);details.setVisibility(show?View.VISIBLE:View.GONE);time.setVisibility(View.GONE);
        if(show){
            set(device,selectedName==null?getString(R.string.unnamed):selectedName);
            set(battery,snapshot.battery==null?getString(reading?R.string.waiting:R.string.not_available):getString(R.string.battery_percent,snapshot.battery));
            set(hardware,snapshot.hardware==null?getString(R.string.not_available):snapshot.hardware);set(software,snapshot.software==null?getString(R.string.not_available):snapshot.software);
            if(snapshot.readAt!=null){time.setVisibility(View.VISIBLE);set(time,getString(R.string.read_at,android.text.format.DateFormat.getTimeFormat(this).format(new Date(snapshot.readAt))));}
        }
        switch(screen){
            case HOME:primaryAction=this::beginFind;set(status,R.string.home_title);set(body,homeMessage!=0?homeMessage:interrupted?R.string.background_help:R.string.bonded_home_help);secondary.setVisibility(View.VISIBLE);set(secondary,R.string.nearby);secondaryAction=()->beginFind(Route.NEARBY);break;
            case SEARCH:
                set(status,searchStatus==DiscoverySession.Status.READING_BONDED?R.string.bonded_loading:searching?R.string.searching:R.string.choose_title);
                if(route==Route.BONDED)set(body,searchStatus==DiscoverySession.Status.TIMED_OUT?R.string.bonded_failed:rowCount==0&&!searching?R.string.bonded_empty:R.string.bonded_help);
                else set(body,searching?R.string.choose_help:rowCount==0?R.string.empty:searchStatus==DiscoverySession.Status.LIMIT?R.string.search_limit:R.string.search_done);
                set(primary,route==Route.BONDED?R.string.bonded_refresh:R.string.find_again);primaryAction=()->beginFind(route,selectingRadar);
                secondary.setVisibility(View.VISIBLE);set(secondary,route==Route.BONDED?R.string.nearby:R.string.bonded);secondaryAction=()->beginFind(route==Route.BONDED?Route.NEARBY:Route.BONDED);if(selectingRadar)secondary.setVisibility(View.GONE);break;
            case PERMISSION:
                set(status,R.string.permission_title);boolean settings=permissionAction()==PermissionDecision.Action.SETTINGS;
                set(body,settings?R.string.permission_settings:route==Route.BONDED?R.string.bonded_permission_why:R.string.permission_why);set(primary,settings?R.string.settings:R.string.allow);primaryAction=settings?()->openSettings(false):this::requestNearby;if(route==Route.NEARBY){secondary.setVisibility(View.VISIBLE);set(secondary,R.string.bonded);secondaryAction=this::beginFind;}break;
            case CHANGING:set(status,R.string.bluetooth_changing);set(body,R.string.bluetooth_changing);primaryAction=()->beginFind(route,selectingRadar);set(primary,R.string.bonded_refresh);break;
            case OFF:set(status,R.string.bluetooth_off);set(body,R.string.bluetooth_off);secondary.setVisibility(View.VISIBLE);set(secondary,R.string.bluetooth_settings);secondaryAction=()->openSettings(true);break;
            case UNAVAILABLE:set(status,R.string.bluetooth_unavailable);set(body,R.string.bluetooth_unavailable);break;
            case SEARCH_ERROR:set(status,route==Route.BONDED?R.string.bonded_failed:R.string.search_failed);set(body,route==Route.BONDED?R.string.bonded_failed:R.string.search_failed);set(primary,R.string.find_again);primaryAction=()->beginFind(route,selectingRadar);break;
            case RADAR:set(status,R.string.radar_title);set(body,R.string.radar_description);renderRadar();break;
            case DEVICE:
                if(snapshot==null)break;
                switch(snapshot.stage){case CONNECTING:set(status,R.string.connecting);break;case DISCOVERING:case READING:set(status,R.string.reading);break;case COMPLETE:set(status,R.string.complete);break;case PARTIAL:set(status,R.string.partial);break;case EMPTY:set(status,R.string.no_information);break;case INTERRUPTED:set(status,R.string.interrupted);break;default:set(status,R.string.read_failed);}
                if(!reading&&snapshot.receivedCount==0)set(status,R.string.no_information);
                set(body,reading?R.string.read_help:snapshot.receivedCount==0?R.string.no_information_help:snapshot.stage==Stage.COMPLETE?R.string.complete_help:snapshot.stage==Stage.PARTIAL?R.string.partial_help:R.string.retry_help);
                switch(snapshot.cause){case TIMEOUT:set(body,snapshot.receivedCount==0?R.string.timeout_empty_help:R.string.timeout_help);break;case PERMISSION:set(body,R.string.permission_denied);break;case BLUETOOTH_OFF:set(body,R.string.bluetooth_off);break;case UNAVAILABLE:set(body,R.string.bluetooth_unavailable);break;case BACKGROUND:set(body,R.string.background_help);break;case CANCELLED:set(body,snapshot.receivedCount==0?R.string.cancelled_empty_help:R.string.cancelled_help);break;case NATIVE:set(body,R.string.native_help);break;case SERVICE_CHANGED:set(body,R.string.service_changed_help);break;default:break;}
                if(!reading){
                    if(selectedDevice!=null){set(primary,R.string.read_again);primaryAction=()->{if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED)startRead();else beginFind();};}
                    secondary.setVisibility(View.VISIBLE);set(secondary,R.string.choose_another);secondaryAction=this::beginFind;
                }break;
        }
    }
    @Override public Object onRetainNonConfigurationInstance(){if(screen!=Screen.RADAR)return null;if(radarReader!=null)radarReader.stop(RadarSession.Reason.BACKGROUND);return radarSnapshot;}
    @Override protected void onDestroy(){if(radarReader!=null)radarReader.stop(RadarSession.Reason.BACKGROUND);if(discovery!=null)discovery.dispose();super.onDestroy();}
    private ProgressBar signalBar(LinearLayout parent){
        ProgressBar bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(100);bar.setProgressTintList(android.content.res.ColorStateList.valueOf(colors.accent));bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(colors.track));parent.addView(bar,new LinearLayout.LayoutParams(-1,dp(32)));return bar;
    }
    private void toggleRadar(){
        if(radarSnapshot!=null&&radarSnapshot.stage!=RadarSession.Stage.ENDED){if(radarReader!=null)radarReader.stop(RadarSession.Reason.CANCELLED);}
        else if(selectedDevice!=null)startRadar();else beginRadar();
    }
    private void leaveRadar(){if(radarReader!=null){radarReader.stop(RadarSession.Reason.CANCELLED);radarReader=null;}screen=Screen.HOME;selectingRadar=false;selectedDevice=null;render();page.smoothScrollTo(0,0);}
    private int reasonText(RadarSession.Reason reason){switch(reason){case PERMISSION:return R.string.permission_denied;case BLUETOOTH_OFF:return R.string.bluetooth_off;case BACKGROUND:return R.string.radar_background;case READ_TIMEOUT:case CONNECT_TIMEOUT:return R.string.radar_timeout;case DISCONNECTED:return R.string.radar_disconnected;case CANCELLED:return R.string.live_paused_help;default:return R.string.radar_failed;}}
    private void renderRadar(){
        RadarSession.Snapshot value=radarSnapshot;if(value==null)return;RadarFeedback f=RadarFeedback.from(value);radarPulse.submit(f);
        boolean ended=value.stage==RadarSession.Stage.ENDED;radarMeasure.setEnabled(true);set(radarMeasure,ended?(selectedDevice==null?R.string.radar_choose:R.string.live_resume):R.string.live_pause);
        radarEnd.setVisibility(View.VISIBLE);radarChoose.setVisibility(View.VISIBLE);
        int headline;switch(f.state){case CONNECTING:headline=R.string.radar_connecting;break;case WAITING_RSSI:headline=R.string.live_waiting;break;case WARMUP:headline=R.string.live_warmup;break;case STALE:headline=R.string.live_stale;break;case PAUSED:headline=R.string.live_paused;break;default:headline=f.trend==RadarSession.Trend.WARMER?R.string.live_warmer:f.trend==RadarSession.Trend.COLDER?R.string.live_colder:f.trend==RadarSession.Trend.SIMILAR?R.string.live_similar:R.string.live_receiving;}
        set(radarStatus,headline);radarStatus.setTextColor(f.state==RadarFeedback.State.WARMUP||f.state==RadarFeedback.State.STALE||f.state==RadarFeedback.State.WAITING_RSSI?colors.warning:colors.text);
        set(radarMetric,f.strengthUsable?getString(R.string.live_metric,f.filteredDbm):f.state==RadarFeedback.State.WARMUP?getString(R.string.live_initial_count,f.recentCount):getString(ended?R.string.live_paused_metric:R.string.live_missing_metric));
        radarDial.setContentDescription(getString(R.string.live_dial_access,getString(headline),getString(ended?R.string.live_resume:R.string.live_pause)));
        set(radarCounter,getString(R.string.live_counter,f.windowId,f.count,f.attempts));radarSamples.setProgress(f.count);radarSamples.setContentDescription(getString(R.string.live_counter,f.windowId,f.count,f.attempts));radarSamples.setVisibility(!ended?View.VISIBLE:View.GONE);
        if(ended)set(radarHelp,reasonText(value.reason));else if(f.state==RadarFeedback.State.STALE)set(radarHelp,getString(R.string.live_old_reading,Math.min(999,f.ageMs/1000)));else set(radarHelp,f.trend==RadarSession.Trend.FIRST||f.trend==RadarSession.Trend.INCONCLUSIVE?R.string.live_comparison_wait:R.string.live_signal_only);
        set(radarBest,f.bestId==0?getString(R.string.live_best_pending):getString(R.string.live_best,f.bestId,f.bestDbm));
        if(renderedRadarGeneration==value.generation&&renderedRadarPoints==value.completedCount)return;
        renderedRadarGeneration=value.generation;renderedRadarPoints=value.completedCount;radarPoints.removeAllViews();
        if(f.history.isEmpty())text(radarPoints,R.string.live_history_empty,18);
        for(RadarSession.Point point:f.history){LinearLayout item=card(radarPoints);TextView label=text(item,R.string.live_window_title,22);label.setTypeface(null,android.graphics.Typeface.BOLD);label.setText(getString(R.string.live_window_title,point.id));
            TextView count=text(item,R.string.live_window_count,18);count.setText(getString(R.string.live_window_count,point.samples.size(),point.attempts,point.failures));
            if(point.median==null){TextView warning=text(item,R.string.live_window_missing,20);warning.setTextColor(colors.warning);if(point.reason!=RadarSession.Reason.NONE)text(item,reasonText(point.reason),16);continue;}
            if(point.samples.size()<3){TextView warning=text(item,R.string.radar_low,20);warning.setTextColor(colors.warning);continue;}
            TextView median=text(item,R.string.live_window_median,26);median.setText(getString(R.string.live_window_median,point.median));
            ProgressBar bar=signalBar(item);bar.setProgress((int)Math.max(0,Math.min(100,(point.median+110)*100/80)));bar.setContentDescription(getString(R.string.live_window_median,point.median));
            int quality=point.quality==RadarSession.Quality.VARIABLE?R.string.radar_variable:R.string.radar_stable;
            int trend=point.trend==RadarSession.Trend.WARMER?R.string.radar_warmer:point.trend==RadarSession.Trend.COLDER?R.string.radar_colder:point.trend==RadarSession.Trend.SIMILAR?R.string.radar_similar:point.trend==RadarSession.Trend.FIRST?R.string.radar_first:R.string.radar_inconclusive;
            text(item,quality,17);text(item,trend,17);if(point.reason!=RadarSession.Reason.NONE)text(item,reasonText(point.reason),16);
        }
    }
    private void showSnapshot(){
        if(snapshot==null||!snapshot.isFinal())return;
        diagnosticRequestId=diagnosticRequestId==Long.MAX_VALUE?1:diagnosticRequestId+1;
        try{
            JSONObject reply=new JSONObject(new String(snapshotForTerminal(diagnosticRequestId),StandardCharsets.UTF_8));
            if(reply.has("error")){showText(R.string.snapshot_title,getString(R.string.snapshot_error));return;}
            JSONObject result=reply.getJSONObject("result");
            if(!"cached_snapshot".equals(result.getString("delivery")))throw new JSONException("Unexpected delivery");
            showText(R.string.snapshot_title,getString(R.string.snapshot_explanation)+"\n\n"+reply.toString(2));
        }catch(JSONException|RuntimeException|LinkageError failure){showText(R.string.snapshot_title,getString(R.string.snapshot_error));}
    }
    private void showLicense(){
        String text=getString(R.string.license_explanation);
        try(InputStream input=getAssets().open("Gadgetbridge-AGPL3.txt");ByteArrayOutputStream output=new ByteArrayOutputStream()){
            byte[] buffer=new byte[4096];int count;
            while((count=input.read(buffer))!=-1){if(output.size()+count>65536)throw new IOException("License limit");output.write(buffer,0,count);}
            text+="\n\n"+new String(output.toByteArray(),StandardCharsets.UTF_8);
        }catch(IOException failure){text+="\n\n"+getString(R.string.license_unavailable);}
        showText(R.string.license_action,text);
    }
    private void showText(int titleResource,String message){
        ScrollView scroll=new ScrollView(this);TextView content=new TextView(this);content.setText(message);content.setTextColor(colors.text);content.setTextSize(16);
        content.setPadding(dp(20),dp(12),dp(20),dp(12));content.setTextIsSelectable(true);scroll.addView(content);
        new AlertDialog.Builder(this).setTitle(titleResource).setView(scroll).setPositiveButton(R.string.close,null).show();
    }
    private void showDetails(){String content=snapshot==null||snapshot.pnp==null?getString(R.string.pnp_missing):getString(R.string.pnp_details,snapshot.pnp.vendor,snapshot.pnp.product,snapshot.pnp.version);
        new AlertDialog.Builder(this).setTitle(R.string.technical_title).setMessage(content).setPositiveButton(R.string.close,null).show();}
    /** Hands each finished reading to the MCP server; band_snapshot serves the latest. */
    private void publishMcp(Snapshot value){
        if(value==null||!value.isFinal()||value==mcpPublished)return;mcpPublished=value;
        mcpPublishId=mcpPublishId==Long.MAX_VALUE?1:mcpPublishId+1;
        try{McpHub.publish(BasicSnapshot.process(value,BasicReadSession.Origin.OUR_APP,mcpPublishId));}catch(RuntimeException|LinkageError ignored){}
    }
    private void toggleMcp(){
        if(McpService.running){stopService(new Intent(this,McpService.class));page.postDelayed(this::renderMcp,300);return;}
        List<String> missing=mcpPermissions();
        if(!missing.isEmpty()){requestPermissions(missing.toArray(new String[0]),42);return;}
        startForegroundService(new Intent(this,McpService.class));page.postDelayed(this::renderMcp,500);
    }
    /** POST_NOTIFICATIONS only exists from Android 13; on 12 notifications need no grant. */
    private List<String> mcpPermissions(){
        List<String> missing=new ArrayList<>();
        if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)missing.add(Manifest.permission.BLUETOOTH_CONNECT);
        if(android.os.Build.VERSION.SDK_INT>=android.os.Build.VERSION_CODES.TIRAMISU&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)missing.add(Manifest.permission.POST_NOTIFICATIONS);
        return missing;
    }
    private void renderMcp(){
        if(mcpToggle==null)return;boolean on=McpService.running;set(mcpToggle,on?R.string.mcp_turn_off:R.string.mcp_turn_on);
        if(!on){set(mcpInfo,R.string.mcp_off_help);return;}
        int port=McpService.port;String ip=tailnetAddress();resolveName(ip);String host=tailnetName!=null&&ip!=null?tailnetName:ip;
        String base=host==null?null:"http://"+host+":"+port;int clients=0;
        try{clients=new JSONObject(McpHub.status()).getJSONArray("clients").length();}catch(JSONException|RuntimeException|LinkageError ignored){}
        set(mcpInfo,getString(R.string.mcp_on_help,base==null?getString(R.string.mcp_no_tailnet,port):base,clients,base==null?"http://<celular>:"+port:base));
        page.removeCallbacks(mcpRefresh);if(resumed)page.postDelayed(mcpRefresh,2000);
    }
    /** MagicDNS name (celular.tailnet.ts.net) by reverse lookup through Tailscale's resolver; IP stays as fallback. */
    private void resolveName(String ip){
        if(ip==null||tailnetName!=null||resolvingName)return;resolvingName=true;
        new Thread(()->{
            try{String name=java.net.InetAddress.getByName(ip).getCanonicalHostName();
                if(name.endsWith("."))name=name.substring(0,name.length()-1);
                if(!name.equals(ip)&&name.endsWith(".ts.net"))tailnetName=name;}
            catch(java.io.IOException|RuntimeException ignored){}
            resolvingName=false;
        },"casca-magicdns").start();
    }
    private String versionName(){
        try{return getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(PackageManager.NameNotFoundException|RuntimeException e){return "?";}
    }
    /** The phone's Tailscale IPv4 (100.64.0.0/10), when the VPN is up. */
    private static String tailnetAddress(){
        try{for(java.net.NetworkInterface nic:Collections.list(java.net.NetworkInterface.getNetworkInterfaces()))
            for(java.net.InetAddress address:Collections.list(nic.getInetAddresses())){
                byte[] b=address.getAddress();if(b.length==4&&(b[0]&0xff)==100&&(b[1]&0xc0)==0x40)return address.getHostAddress();}
        }catch(java.net.SocketException|RuntimeException ignored){}
        return null;
    }
}

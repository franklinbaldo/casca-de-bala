// SPDX-License-Identifier: AGPL-3.0-or-later
package org.workspace.band7.app;
import android.app.*;import android.bluetooth.BluetoothDevice;import android.content.*;import android.os.*;import android.text.*;import android.text.InputType;import android.view.*;import android.view.inputmethod.EditorInfo;import android.widget.*;
import java.util.function.Consumer;import org.workspace.band7.*;
/** Foreground-only explicit pilot. Secret never enters Intent/Bundle, fields,
 * preferences, logs or clipboard. The actual managed-runtime wipe is best effort. */
public class PilotActivity extends Activity {
 public static final int START=0x00cb0001,READ=0x00cb0002,CLOSE=0x00cb0003,SECRET=0x00cb0004,STATUS=0x00cb0005,BATTERY=0x00cb0006;
 private static BluetoothDevice pendingSelection;
 private BluetoothDevice selected;private PilotConnection session;private PilotConnection.State state;
 private long generation;private boolean foreground;private AlertDialog secretDialog;private SecretField secretField;
 private TextView status,battery,reason,time;private Button start,read,close;
 /** One-shot in-process transfer, no Bluetooth identifier in persisted Intent extras. */
 public static void open(Activity from,BluetoothDevice device){if(device==null)return;pendingSelection=device;try{from.startActivity(new Intent(from,PilotActivity.class));}catch(RuntimeException failed){pendingSelection=null;throw failed;}}
 protected boolean fixtureMode(){return false;}
 protected boolean selectionAvailable(){return selected!=null;}
 private PilotConnection.Origin origin(){return fixtureMode()?PilotConnection.Origin.SIMULATED_PEER:PilotConnection.Origin.DEVICE_UNVERIFIED;}
 private PilotConnection.State initial(){return new PilotConnection.State(PilotConnection.Stage.READY_TO_START,selectionAvailable()?PilotConnection.Reason.NONE:PilotConnection.Reason.SELECTION_REQUIRED,origin(),null,-1);}
 @Override public void onCreate(Bundle saved){super.onCreate(saved);selected=pendingSelection;pendingSelection=null;
  setTitle(R.string.pilot_title);ScrollView scroll=new ScrollView(this);scroll.setSaveEnabled(false);scroll.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);int pad=Math.round(20*getResources().getDisplayMetrics().density);root.setPadding(pad,pad,pad,pad);root.setSaveFromParentEnabled(false);scroll.addView(root);
  label(root,R.string.pilot_title,28).setAccessibilityHeading(true);label(root,fixtureMode()?R.string.pilot_fixture_intro:R.string.pilot_intro,17);label(root,R.string.pilot_consent,16);
  status=label(root,R.string.pilot_ready_start,22);status.setId(STATUS);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);reason=label(root,R.string.pilot_no_selection,17);battery=label(root,R.string.not_available,30);battery.setId(BATTERY);time=label(root,R.string.not_available,15);
  start=button(root,R.string.pilot_start,START);start.setOnClickListener(v->showSecret());read=button(root,R.string.pilot_read,READ);read.setOnClickListener(v->{if(session!=null&&state.stage==PilotConnection.Stage.READY){if(!session.requestBattery())showError(PilotConnection.Reason.TRANSPORT);}});close=button(root,R.string.pilot_close,CLOSE);close.setOnClickListener(v->stop(PilotConnection.Reason.CANCELLED));
  label(root,R.string.pilot_haptic_status,17);label(root,R.string.pilot_notification_status,17);label(root,fixtureMode()?R.string.pilot_origin_fixture:R.string.pilot_origin_device,16);setContentView(scroll);state=initial();render();
 }
 private TextView label(LinearLayout root,int text,int size){TextView t=new TextView(this);t.setText(text);t.setTextSize(size);t.setPadding(0,8,0,8);t.setSaveEnabled(false);root.addView(t,new LinearLayout.LayoutParams(-1,-2));return t;}
 private Button button(LinearLayout root,int text,int id){Button b=new Button(this);b.setText(text);b.setId(id);b.setAllCaps(false);b.setMinHeight(Math.round(56*getResources().getDisplayMetrics().density));root.addView(b,new LinearLayout.LayoutParams(-1,-2));return b;}
 private void showSecret(){if(!foreground||!selectionAvailable()||secretDialog!=null)return;
  secretField=new SecretField(this);secretField.setId(SECRET);secretField.setHint(R.string.pilot_secret_hint);secretField.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);secretField.setFilters(new InputFilter[]{new InputFilter.LengthFilter(32)});secretField.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING|EditorInfo.IME_FLAG_NO_EXTRACT_UI);secretField.setSaveEnabled(false);secretField.setFreezesText(false);secretField.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);secretField.setImportantForContentCapture(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS);
  LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(24,8,24,8);box.setSaveFromParentEnabled(false);label(box,R.string.pilot_secret_help,16);box.addView(secretField,new LinearLayout.LayoutParams(-1,-2));
  ScrollView secretScroll=new ScrollView(this);secretScroll.setSaveEnabled(false);secretScroll.addView(box);AlertDialog dialog=new AlertDialog.Builder(this).setTitle(R.string.pilot_secret_title).setView(secretScroll).setPositiveButton(R.string.pilot_connect,null).setNegativeButton(R.string.cancel,null).create();secretDialog=dialog;
  // Only this separate secret window is secure. Main/product windows stay printable.
  if(dialog.getWindow()!=null)dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
  SecretField ownField=secretField;dialog.setOnDismissListener(d->{ownField.getText().clear();ownField.setError(null);if(secretDialog==dialog){secretField=null;secretDialog=null;}});dialog.setOnShowListener(d->{dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v->{ownField.getText().clear();ownField.setError(null);dialog.dismiss();});dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{if(!foreground)return;SecretField field=secretField;if(field==null)return;
    try{LocalCredential.use(field.getText(),key->{field.getText().clear();dialog.dismiss();connect(key);});}
    catch(IllegalArgumentException invalid){if(secretField!=null){secretField.getText().clear();secretField.setError(getString(R.string.pilot_secret_invalid));}}
   });});dialog.show();
 }
 private void eraseSecret(){if(secretField!=null){secretField.getText().clear();secretField.setError(null);secretField=null;}}
 private void dismissSecret(){AlertDialog d=secretDialog;eraseSecret();secretDialog=null;if(d!=null)d.dismiss();}
 private void connect(byte[] key){if(!foreground||!selectionAvailable())return;stop(PilotConnection.Reason.CANCELLED);long attempt=++generation;
  state=new PilotConnection.State(PilotConnection.Stage.CONNECTING,PilotConnection.Reason.NONE,origin(),null,-1);render();
  try{session=createSession(key,value->{if(attempt!=generation||!foreground)return;state=value;render();});session.start();}
  catch(RuntimeException|LinkageError failed){if(session!=null){generation++;session.close();session=null;}showError(PilotConnection.Reason.TRANSPORT);}
 }
 protected PilotConnection createSession(byte[] key,Consumer<PilotConnection.State> observer){
  if(selected==null)throw new IllegalStateException("selection required");HuamiSessionCrypto crypto=HuamiSessionCrypto.open(key);final HuamiGattAdapter adapter;
  try{adapter=new HuamiGattAdapter(this,crypto,s->observer.accept(present(s)));}catch(RuntimeException failed){crypto.close();throw failed;}
  return new PilotConnection(){public void start(){adapter.start(selected);}public boolean requestBattery(){return adapter.requestBattery();}public void close(){adapter.close();}};
 }
 private static PilotConnection.State present(HuamiGattAdapter.Snapshot s){
  PilotConnection.Stage stage=PilotConnection.Stage.CONNECTING;PilotConnection.Reason why=PilotConnection.Reason.NONE;
  if(s.phase==HuamiGattAdapter.Phase.AUTH)stage=s.auth==HuamiAuthOwner.Status.PEER_ACCEPTED&&s.batteryCanRequest?PilotConnection.Stage.READY:PilotConnection.Stage.AUTHENTICATING;
  switch(s.batteryQuery){case DISCOVERY_WRITE:case DISCOVERY_WAIT:case BATTERY_WRITE:case BATTERY_WAIT:stage=PilotConnection.Stage.READING;break;case COMPLETE:stage=PilotConnection.Stage.COMPLETE;break;case UNSUPPORTED:stage=PilotConnection.Stage.ERROR;why=PilotConnection.Reason.UNSUPPORTED;break;case TIMEOUT:stage=PilotConnection.Stage.ERROR;why=PilotConnection.Reason.TIMEOUT;break;case ERROR:stage=PilotConnection.Stage.ERROR;why=PilotConnection.Reason.TRANSPORT;break;default:break;}
  if(s.phase==HuamiGattAdapter.Phase.STOPPED){stage=PilotConnection.Stage.STOPPED;switch(s.reason){case PERMISSION:why=PilotConnection.Reason.PERMISSION;break;case UNSUPPORTED:why=PilotConnection.Reason.UNSUPPORTED;break;case BACKGROUND:why=PilotConnection.Reason.BACKGROUND;break;case CANCELLED:why=PilotConnection.Reason.CANCELLED;break;case TIMEOUT:why=PilotConnection.Reason.TIMEOUT;break;default:if(why==PilotConnection.Reason.NONE)why=PilotConnection.Reason.TRANSPORT;}}
  if(s.auth==HuamiAuthOwner.Status.REJECTED){stage=PilotConnection.Stage.ERROR;why=PilotConnection.Reason.AUTH_REJECTED;}else if(s.failure==HuamiAuthOwner.Failure.TIMEOUT){stage=PilotConnection.Stage.ERROR;why=PilotConnection.Reason.TIMEOUT;}
  return new PilotConnection.State(stage,why,PilotConnection.Origin.DEVICE_UNVERIFIED,s.batteryPercent,s.batterySampleElapsed);
 }
 private void showError(PilotConnection.Reason why){state=new PilotConnection.State(PilotConnection.Stage.ERROR,why,origin(),state.battery,state.receivedElapsed);render();}
 private void stop(PilotConnection.Reason why){generation++;PilotConnection current=session;session=null;if(current!=null)current.close();if(state!=null){state=new PilotConnection.State(PilotConnection.Stage.STOPPED,why,origin(),state.battery,state.receivedElapsed);render();}}
 private void render(){if(state==null)return;int text;switch(state.stage){case CONNECTING:text=R.string.pilot_connecting;break;case AUTHENTICATING:text=R.string.pilot_authenticating;break;case READY:text=R.string.pilot_ready;break;case READING:text=R.string.pilot_reading;break;case COMPLETE:text=R.string.pilot_complete;break;case STOPPED:text=R.string.pilot_stopped;break;case ERROR:text=R.string.pilot_error;break;default:text=R.string.pilot_ready_start;}status.setText(text);
  int help=0;switch(state.reason){case SELECTION_REQUIRED:help=R.string.pilot_no_selection;break;case BACKGROUND:if(!selectionAvailable())help=R.string.pilot_no_selection;break;case PERMISSION:help=R.string.pilot_permission;break;case TIMEOUT:help=R.string.pilot_timeout;break;case AUTH_REJECTED:help=R.string.pilot_rejected;break;case UNSUPPORTED:help=R.string.pilot_unsupported;break;default:break;}reason.setVisibility(help==0?View.GONE:View.VISIBLE);if(help!=0)reason.setText(help);
  battery.setText(state.battery==null?getString(R.string.not_available):getString(R.string.battery_percent,state.battery));time.setVisibility(state.battery==null?View.GONE:View.VISIBLE);if(state.battery!=null)time.setText(getString(R.string.pilot_sample_time,state.receivedElapsed));
  boolean running=session!=null&&state.stage!=PilotConnection.Stage.STOPPED&&state.stage!=PilotConnection.Stage.ERROR;start.setEnabled(selectionAvailable()&&!running);read.setEnabled(foreground&&session!=null&&state.stage==PilotConnection.Stage.READY);close.setEnabled(session!=null);
 }
 @Override protected void onResume(){super.onResume();foreground=true;render();}
 @Override protected void onStop(){foreground=false;dismissSecret();selected=null;stop(PilotConnection.Reason.BACKGROUND);super.onStop();}
 @Override protected void onSaveInstanceState(Bundle out){dismissSecret();super.onSaveInstanceState(out);}
 @Override protected void onDestroy(){dismissSecret();stop(PilotConnection.Reason.BACKGROUND);selected=null;super.onDestroy();}
 private static final class SecretField extends EditText {
  SecretField(android.content.Context context){super(context);}
  @Override public boolean getFreezesText(){return false;}
  @Override public boolean onTextContextMenuItem(int id){if(id==android.R.id.copy||id==android.R.id.cut||id==android.R.id.shareText)return false;return super.onTextContextMenuItem(id);}
 }
}

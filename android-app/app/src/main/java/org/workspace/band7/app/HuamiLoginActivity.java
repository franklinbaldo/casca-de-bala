package org.workspace.band7.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shell for the Zepp sign-in: Zepp's own web login in a WebView, with
 * whatever method the owner uses there (Xiaomi included). The password is
 * typed into Zepp's or Xiaomi's page; we only watch for the session cookies
 * Zepp's page sets and hand them to Rust (BleNative), which fetches and
 * stores the key and signs that session out. */
public final class HuamiLoginActivity extends Activity {
    private final Handler poll=new Handler(Looper.getMainLooper());
    private WebView web;private TextView help;private String cookieUrl;private boolean done;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        CookieManager.getInstance().removeAllCookies(null);
        cookieUrl=BleNative.huamiCookieUrl();
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        help=new TextView(this);help.setText(R.string.huami_help);help.setPadding(32,24,32,24);root.addView(help);
        web=new WebView(this);root.addView(web,new LinearLayout.LayoutParams(-1,-1));
        WebSettings s=web.getSettings();
        s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setAllowFileAccess(false);s.setAllowContentAccess(false);
        web.setWebViewClient(new WebViewClient(){
            @Override public void onPageFinished(WebView view,String url){check();}
        });
        setContentView(root);
        web.loadUrl(BleNative.huamiLoginPage());
        poll.post(new Runnable(){@Override public void run(){if(!done){check();poll.postDelayed(this,1000);}}});
    }

    /** Zepp's page sets the cookies from script, without a navigation: look after each page and every second. */
    private void check(){
        String cookies=CookieManager.getInstance().getCookie(cookieUrl);
        if(done||cookies==null||!BleNative.huamiSignedIn(cookies))return;
        done=true;web.stopLoading();web.loadUrl("about:blank");help.setText(R.string.huami_working);
        String files=getFilesDir().getPath();
        new Thread(()->{
            String message;
            try{message=BleNative.huamiExchange(files,cookies);}catch(RuntimeException|LinkageError failed){message="ERRO: "+failed;}
            String text=message;
            runOnUiThread(()->{
                forgetSession();
                if(isFinishing())return;
                new AlertDialog.Builder(this).setTitle(R.string.huami_title).setMessage(text)
                    .setPositiveButton(R.string.close,(d,w)->finish()).setOnCancelListener(d->finish()).show();
            });
        },"huami-exchange").start();
    }

    /** No Zepp or Xiaomi session stays behind in the app's WebView. */
    private void forgetSession(){CookieManager.getInstance().removeAllCookies(null);web.clearHistory();web.clearCache(true);}

    @Override protected void onDestroy(){done=true;poll.removeCallbacksAndMessages(null);forgetSession();web.destroy();super.onDestroy();}
}

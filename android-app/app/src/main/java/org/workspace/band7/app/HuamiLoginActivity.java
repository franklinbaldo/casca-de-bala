package org.workspace.band7.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shell for the Xiaomi OAuth sign-in: Xiaomi's own page in a WebView. The
 * password is typed into Xiaomi's page; we only catch the redirect with the
 * code and hand it to Rust (BleNative), which fetches and stores the key. */
public final class HuamiLoginActivity extends Activity {
    private WebView web;private TextView help;private boolean done;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        CookieManager.getInstance().removeAllCookies(null);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        help=new TextView(this);help.setText(R.string.huami_help);help.setPadding(32,24,32,24);root.addView(help);
        web=new WebView(this);root.addView(web,new LinearLayout.LayoutParams(-1,-1));
        WebSettings s=web.getSettings();
        s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setAllowFileAccess(false);s.setAllowContentAccess(false);
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return intercept(request.getUrl().toString());}
            @Override public void onPageStarted(WebView view,String url,Bitmap favicon){intercept(url);}
        });
        setContentView(root);
        web.loadUrl(BleNative.huamiAuthorize());
    }

    /** True when url is the OAuth redirect: stop there and exchange the code. */
    private boolean intercept(String url){
        String code=BleNative.huamiCode(url);
        if(code==null||code.isEmpty()||done)return code!=null&&!code.isEmpty();
        done=true;web.stopLoading();web.loadUrl("about:blank");help.setText(R.string.huami_working);
        String files=getFilesDir().getPath();
        new Thread(()->{
            String message;
            try{message=BleNative.huamiExchange(files,code);}catch(RuntimeException|LinkageError failed){message="ERRO: "+failed;}
            String text=message;
            runOnUiThread(()->{
                forgetSession();
                if(isFinishing())return;
                new AlertDialog.Builder(this).setTitle(R.string.huami_title).setMessage(text)
                    .setPositiveButton(R.string.close,(d,w)->finish()).setOnCancelListener(d->finish()).show();
            });
        },"huami-exchange").start();
        return true;
    }

    /** No Xiaomi session stays behind in the app's WebView. */
    private void forgetSession(){CookieManager.getInstance().removeAllCookies(null);web.clearHistory();web.clearCache(true);}

    @Override protected void onDestroy(){forgetSession();web.destroy();super.onDestroy();}
}

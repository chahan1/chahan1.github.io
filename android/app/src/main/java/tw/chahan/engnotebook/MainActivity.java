package tw.chahan.engnotebook;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/** 外殼：用 WebView 載入 GitHub Pages 上的英語筆記本，網頁更新會自動生效；聲音與語音辨識交給原生程式。 */
public class MainActivity extends Activity {
    static final String HOME = "https://chahan1.github.io/";
    private static final int REQ_FILE = 1, REQ_MIC = 2, REQ_NOTIFY = 3;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static WeakReference<MainActivity> current = new WeakReference<>(null);

    private WebView web;
    private ValueCallback<Uri[]> fileCb;
    private final List<Runnable[]> micWaiters = new ArrayList<>();

    /** 在網頁裡執行一段 JavaScript（任何執行緒都可以呼叫） */
    static void js(String code) {
        main.post(() -> {
            MainActivity a = current.get();
            if (a != null && a.web != null) a.web.evaluateJavascript(code, null);
        });
    }

    /** 需要麥克風時呼叫：已有權限直接 ok，否則先問使用者 */
    static void needMic(Runnable ok, Runnable denied) {
        main.post(() -> {
            MainActivity a = current.get();
            if (a == null) { denied.run(); return; }
            if (a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) { ok.run(); return; }
            a.micWaiters.add(new Runnable[]{ok, denied});
            if (a.micWaiters.size() == 1) a.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        });
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        current = new WeakReference<>(this);
        Player.init(this);
        Speech.init(this);

        web = new NbWebView(this);   // 自訂選字選單（查單字、翻譯）
        web.setBackgroundColor(Color.rgb(248, 249, 250));
        setContentView(web);
        WebView.setWebContentsDebuggingEnabled(true);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(s.getUserAgentString() + " EngNotebookApp/" + Bridge.VERSION);

        web.addJavascriptInterface(new Bridge(getApplicationContext()), "NativeNB");
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try { startActivityForResult(p.createIntent(), REQ_FILE); }
                catch (Exception e) { fileCb = null; cb.onReceiveValue(null); return false; }
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest req) {
                boolean wantsMic = false;
                for (String r : req.getResources()) if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) wantsMic = true;
                if (!wantsMic) { req.deny(); return; }
                needMic(() -> req.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE}), req::deny);
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String path = u.getPath() == null ? "/" : u.getPath();
                if ("chahan1.github.io".equals(u.getHost()) && (path.equals("/") || path.equals("/index.html"))) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });

        if (saved != null) web.restoreState(saved);
        else web.loadUrl(HOME);

        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == REQ_FILE && fileCb != null) {
            fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
            fileCb = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req != REQ_MIC) return;
        boolean ok = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
        List<Runnable[]> list = new ArrayList<>(micWaiters);
        micWaiters.clear();
        for (Runnable[] w : list) (ok ? w[0] : w[1]).run();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else moveTaskToBack(true);   // 不關掉 App，播放中按返回也不會中斷
    }

    @Override
    protected void onDestroy() {
        if (current.get() == this) current = new WeakReference<>(null);
        super.onDestroy();
    }
}

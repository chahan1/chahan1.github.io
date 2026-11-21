package tw.chahan.engnotebook;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** 網頁呼叫的介面：window.NativeNB（網頁端的包裝在 native.js） */
public class Bridge {
    static final int VERSION = 1;
    private final Context ctx;

    Bridge(Context ctx) { this.ctx = ctx; }

    /** 原生 → 網頁的事件：ch = a（播放器）/ t（朗讀）/ r（語音辨識） */
    static void emit(String ch, int id, String type, String json) {
        MainActivity.js("window.__nbEvt&&__nbEvt('" + ch + "'," + id + ",'" + type + "'," + json + ")");
    }

    @JavascriptInterface public int version() { return VERSION; }

    // ---- 和其他 App 的聲音怎麼相處 ----
    @JavascriptInterface public void setMix(String m) { Mix.setMode(m); }
    @JavascriptInterface public String getMix() { return Mix.mode; }

    // ---- 播放器 ----
    @JavascriptInterface public void audioBegin(int id, int gen) { Player.begin(id, gen); }
    @JavascriptInterface public void audioChunk(int id, int gen, String b64) { Player.chunk(id, gen, b64); }
    @JavascriptInterface public void audioLoad(int id, int gen) { Player.load(id, gen); }
    @JavascriptInterface public void audioPlay(int id) { Player.play(id); }
    @JavascriptInterface public void audioPause(int id) { Player.pause(id); }
    @JavascriptInterface public void audioSeek(int id, double sec) { Player.seek(id, sec); }
    @JavascriptInterface public void audioRate(int id, double r) { Player.rate(id, r); }
    @JavascriptInterface public void audioRelease(int id) { Player.release(id); }

    // ---- 朗讀 ----
    @JavascriptInterface public void ttsSpeak(int id, String text, String lang, double rate) { Speech.speak(id, text, lang, rate); }
    @JavascriptInterface public void ttsStop() { Speech.stop(); }

    // ---- 語音辨識 ----
    @JavascriptInterface public void srStart(int id, String lang, boolean interim, int maxAlt) { Recognizer.start(ctx, id, lang, interim, maxAlt); }
    @JavascriptInterface public void srStop(int id) { Recognizer.stop(id); }
    @JavascriptInterface public void srAbort(int id) { Recognizer.abort(id); }

    // ---- 通知列／鎖定畫面的播放控制：json 空字串＝關閉播放視窗 ----
    @JavascriptInterface public void media(String json) {
        if (json == null || json.isEmpty()) { PlayService.stop(); return; }
        try {
            JSONObject o = new JSONObject(json);
            PlayService.update(ctx, o.optString("title", "英語筆記本"), o.optString("text", ""), o.optBoolean("playing", false));
        } catch (Exception ignored) { }
    }

    /** 存檔到手機的「下載」資料夾 */
    @JavascriptInterface public boolean saveFile(String name, String mime, String text) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, mime);
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (u == null) return false;
            try (OutputStream o = cr.openOutputStream(u)) {
                if (o == null) return false;
                o.write(text.getBytes(StandardCharsets.UTF_8));
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}

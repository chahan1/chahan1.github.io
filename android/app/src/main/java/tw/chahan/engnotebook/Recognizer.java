package tw.chahan.engnotebook;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 原生語音辨識（代替網頁的 SpeechRecognition）。
 * 指定使用 Google 的語音服務，效果和 Chrome 裡一樣；手機沒有才用系統預設的服務。
 * 一次辨識一段話，說完就結束（網頁那邊會自己再接著開始，和 Chrome 的行為一致）。
 */
final class Recognizer {
    private static final Handler h = new Handler(Looper.getMainLooper());
    private static SpeechRecognizer sr;
    private static int cur;

    static void start(Context c, int id, String lang, boolean interim, int maxAlt) {
        MainActivity.needMic(() -> h.post(() -> begin(c, id, lang, interim, maxAlt)), () -> {
            Bridge.emit("r", id, "error", "{\"error\":\"not-allowed\"}");
            Bridge.emit("r", id, "end", "{}");
        });
    }

    private static void begin(Context c, int id, String lang, boolean interim, int maxAlt) {
        if (cur != 0) {   // 上一次還沒結束：先結束它
            int old = cur;
            cur = 0;
            try { sr.cancel(); } catch (Exception ignored) { }
            Bridge.emit("r", old, "end", "{}");
        }
        if (sr == null) sr = create(c);
        if (sr == null) {
            Bridge.emit("r", id, "error", "{\"error\":\"service-not-allowed\"}");
            Bridge.emit("r", id, "end", "{}");
            return;
        }
        cur = id;
        sr.setRecognitionListener(new L(id, interim));
        Intent it = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        it.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        it.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang == null || lang.isEmpty() ? "en-US" : lang);
        it.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, interim);
        it.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, Math.max(1, maxAlt));
        it.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, c.getPackageName());
        try { sr.startListening(it); }
        catch (Exception e) {
            cur = 0;
            Bridge.emit("r", id, "error", "{\"error\":\"aborted\"}");
            Bridge.emit("r", id, "end", "{}");
        }
    }

    private static SpeechRecognizer create(Context c) {
        if (!SpeechRecognizer.isRecognitionAvailable(c)) return null;
        List<ResolveInfo> list = c.getPackageManager().queryIntentServices(new Intent(RecognitionService.SERVICE_INTERFACE), 0);
        for (String pkg : new String[]{"com.google.android.googlequicksearchbox", "com.google.android.tts"}) {
            for (ResolveInfo ri : list) {
                if (ri.serviceInfo != null && pkg.equals(ri.serviceInfo.packageName)) {
                    return SpeechRecognizer.createSpeechRecognizer(c, new ComponentName(ri.serviceInfo.packageName, ri.serviceInfo.name));
                }
            }
        }
        return SpeechRecognizer.createSpeechRecognizer(c);
    }

    static void stop(int id) { h.post(() -> { if (cur == id && sr != null) sr.stopListening(); }); }

    static void abort(int id) {
        h.post(() -> {
            if (cur != id) return;
            cur = 0;
            try { sr.cancel(); } catch (Exception ignored) { }
            Bridge.emit("r", id, "error", "{\"error\":\"aborted\"}");
            Bridge.emit("r", id, "end", "{}");
        });
    }

    private static String errName(int code) {
        switch (code) {
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
            case SpeechRecognizer.ERROR_NO_MATCH: return "no-speech";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "not-allowed";
            case SpeechRecognizer.ERROR_AUDIO: return "audio-capture";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
            case SpeechRecognizer.ERROR_SERVER:
            case 10:   // ERROR_TOO_MANY_REQUESTS
            case 11:   // ERROR_SERVER_DISCONNECTED
                return "network";
            case 12:   // ERROR_LANGUAGE_NOT_SUPPORTED
            case 13:   // ERROR_LANGUAGE_UNAVAILABLE
                return "language-not-supported";
            default: return "aborted";
        }
    }

    private static final class L implements RecognitionListener {
        final int id;
        final boolean interim;
        L(int id, boolean interim) { this.id = id; this.interim = interim; }

        private void result(ArrayList<String> alts, boolean fin) {
            if (alts == null || alts.isEmpty()) return;
            try {
                JSONObject o = new JSONObject();
                o.put("final", fin);
                o.put("alts", new JSONArray(alts));
                Bridge.emit("r", id, "result", o.toString());
            } catch (Exception ignored) { }
        }

        @Override public void onReadyForSpeech(Bundle b) { if (cur == id) Bridge.emit("r", id, "start", "{}"); }
        @Override public void onBeginningOfSpeech() { if (cur == id) Bridge.emit("r", id, "speechstart", "{}"); }
        @Override public void onRmsChanged(float v) { }
        @Override public void onBufferReceived(byte[] b) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onEvent(int t, Bundle b) { }

        @Override public void onPartialResults(Bundle b) {
            if (cur != id || !interim) return;
            ArrayList<String> l = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (l != null && !l.isEmpty() && !l.get(0).trim().isEmpty()) {
                ArrayList<String> one = new ArrayList<>();
                one.add(l.get(0));
                result(one, false);
            }
        }

        @Override public void onResults(Bundle b) {
            if (cur != id) return;
            cur = 0;
            ArrayList<String> l = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (l == null || l.isEmpty()) Bridge.emit("r", id, "error", "{\"error\":\"no-speech\"}");
            else result(l, true);
            Bridge.emit("r", id, "end", "{}");
        }

        @Override public void onError(int code) {
            if (cur != id) return;
            cur = 0;
            Bridge.emit("r", id, "error", "{\"error\":\"" + errName(code) + "\"}");
            Bridge.emit("r", id, "end", "{}");
        }
    }
}

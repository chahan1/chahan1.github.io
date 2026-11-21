package tw.chahan.engnotebook;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 手機語音朗讀（代替網頁的 speechSynthesis）。優先用 Google 的語音引擎，沒有就用手機預設的。 */
final class Speech {
    private static final Handler h = new Handler(Looper.getMainLooper());
    private static TextToSpeech tts;
    private static boolean ready, failed;
    private static int queued;
    private static final List<Object[]> waiting = new ArrayList<>();

    private static final Runnable release = new Runnable() {
        @Override public void run() {
            if (tts != null && tts.isSpeaking()) { h.postDelayed(this, 300); return; }
            if (queued <= 0) Mix.abandon();
        }
    };

    static void init(Context c) {
        if (tts != null) return;
        Mix.init(c);
        tts = new TextToSpeech(c.getApplicationContext(), st -> h.post(() -> {
            if (st != TextToSpeech.SUCCESS) {
                failed = true;
                for (Object[] w : waiting) Bridge.emit("t", (Integer) w[0], "error", "{\"error\":\"synthesis-unavailable\"}");
                waiting.clear();
                return;
            }
            ready = true;
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String uid) { Bridge.emit("t", num(uid), "start", "{}"); }
                @Override public void onDone(String uid) { h.post(() -> finished(uid, "end", "{}")); }
                @Override public void onError(String uid) { h.post(() -> finished(uid, "error", "{\"error\":\"synthesis-failed\"}")); }
                @Override public void onStop(String uid, boolean interrupted) { h.post(() -> finished(uid, "error", "{\"error\":\"interrupted\"}")); }
            });
            for (Object[] w : waiting) doSpeak((Integer) w[0], (String) w[1], (String) w[2], (Double) w[3]);
            waiting.clear();
        }), "com.google.android.tts");
    }

    private static int num(String uid) {
        try { return Integer.parseInt(uid.substring(1)); } catch (Exception e) { return 0; }
    }

    private static void finished(String uid, String type, String json) {
        queued = Math.max(0, queued - 1);
        Bridge.emit("t", num(uid), type, json);
        h.removeCallbacks(release);
        h.postDelayed(release, 300);
    }

    static void speak(int id, String text, String lang, double rate) {
        h.post(() -> {
            if (failed) { Bridge.emit("t", id, "error", "{\"error\":\"synthesis-unavailable\"}"); return; }
            if (!ready) { waiting.add(new Object[]{id, text, lang, rate}); return; }
            doSpeak(id, text, lang, rate);
        });
    }

    private static void doSpeak(int id, String text, String lang, double rate) {
        Locale loc = Locale.forLanguageTag(lang == null || lang.isEmpty() ? "en-US" : lang);
        if (tts.setLanguage(loc) < 0) tts.setLanguage(Locale.US);
        tts.setSpeechRate((float) Math.max(0.3, Math.min(3, rate)));
        tts.setAudioAttributes(Mix.attrs());
        h.removeCallbacks(release);
        Mix.acquire(() -> {   // 被打斷就停：其他 App 搶走聲音 → 停止朗讀並通知網頁
            stop();
            MainActivity.js("window.nativeApp&&nativeApp._interrupted()");
        });
        queued++;
        tts.speak(text, TextToSpeech.QUEUE_ADD, new Bundle(), "u" + id);
    }

    static void stop() {
        h.post(() -> {
            waiting.clear();
            queued = 0;
            if (tts != null && ready) tts.stop();
            h.removeCallbacks(release);
            h.postDelayed(release, 300);
        });
    }
}

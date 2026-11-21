package tw.chahan.engnotebook;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

/**
 * 和其他 App 的聲音怎麼相處：
 *  mix       同時播放：完全不向系統要求聲音，Facebook 等 App 播放時兩邊一起出聲，誰都不會被停掉
 *  duck      讓對方變小聲：念例句時請其他 App 暫時降低音量（像導航報路），不會把對方停掉
 *  exclusive 被打斷就停：和網頁版一樣，其他 App 開始播放時暫停
 */
final class Mix {
    static volatile String mode = "mix";

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static AudioManager am;
    private static AudioFocusRequest req;
    private static Runnable onLoss;

    static void init(Context c) {
        if (am == null) am = (AudioManager) c.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
    }

    static void setMode(String m) {
        if ("mix".equals(m) || "duck".equals(m) || "exclusive".equals(m)) mode = m;
    }

    static AudioAttributes attrs() {
        int usage = "duck".equals(mode) ? AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE : AudioAttributes.USAGE_MEDIA;
        return new AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
    }

    /** 開始出聲前呼叫；lossCb 只有在「被打斷就停」模式、被其他 App 搶走聲音時才會執行 */
    static synchronized void acquire(Runnable lossCb) {
        abandon();
        if ("mix".equals(mode) || am == null) return;
        boolean duck = "duck".equals(mode);
        onLoss = lossCb;
        req = new AudioFocusRequest.Builder(duck ? AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK : AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs())
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(f -> {
                    if (!"exclusive".equals(mode)) return;   // 同時播放／讓對方變小聲：被搶走也照樣念
                    if (f == AudioManager.AUDIOFOCUS_LOSS || f == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        Runnable r;
                        synchronized (Mix.class) { r = onLoss; }
                        if (r != null) r.run();
                    }
                }, main)
                .build();
        am.requestAudioFocus(req);
    }

    static synchronized void abandon() {
        if (req != null && am != null) am.abandonAudioFocusRequest(req);
        req = null;
        onLoss = null;
    }
}

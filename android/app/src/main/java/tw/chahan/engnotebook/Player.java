package tw.chahan.engnotebook;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 原生播放器：網頁把整份播放清單的 WAV 分段傳過來，存成暫存檔後用 MediaPlayer 播放。
 * 播放本身不依賴網頁，關螢幕、App 在背景都會繼續；時間進度每 0.25 秒回報給網頁。
 */
final class Player {
    private static final class P {
        final int id;
        volatile int gen;
        File file;
        FileOutputStream out;
        MediaPlayer mp;
        boolean prepared, wantPlay;
        double pendingSeek = -1;
        float rate = 1f;
        P(int id) { this.id = id; }
    }

    private static final Map<Integer, P> ps = new ConcurrentHashMap<>();
    private static final Handler h = new Handler(Looper.getMainLooper());
    private static Context app;
    private static boolean ticking;

    static void init(Context c) {
        app = c.getApplicationContext();
        Mix.init(app);
    }

    private static P get(int id) {
        P p = ps.get(id);
        if (p == null) { p = new P(id); ps.put(id, p); }
        return p;
    }

    // ---- 以下三個在網頁的執行緒上依序呼叫：開始一份新音檔 → 分段寫入 → 寫完載入 ----
    static synchronized void begin(int id, int gen) {
        P p = get(id);
        closeOut(p);
        p.gen = gen;
        p.file = new File(app.getCacheDir(), "nb_audio_" + id + "_" + gen + ".wav");
        try { p.out = new FileOutputStream(p.file, false); } catch (IOException e) { p.out = null; }
        h.post(() -> { releaseMp(p); p.prepared = false; p.wantPlay = false; p.pendingSeek = -1; });
    }

    static synchronized void chunk(int id, int gen, String b64) {
        P p = ps.get(id);
        if (p == null || p.gen != gen || p.out == null) return;
        try { p.out.write(Base64.decode(b64, Base64.DEFAULT)); }
        catch (IOException e) { closeOut(p); Bridge.emit("a", id, "error", "{\"code\":-2}"); }
    }

    static synchronized void load(int id, int gen) {
        P p = ps.get(id);
        if (p == null || p.gen != gen) return;
        closeOut(p);
        final File f = p.file;
        h.post(() -> { if (p.gen == gen) prepare(p, f); });
    }

    private static void closeOut(P p) {
        if (p.out != null) { try { p.out.close(); } catch (IOException ignored) { } p.out = null; }
    }

    private static void releaseMp(P p) {
        if (p.mp != null) {
            try { p.mp.release(); } catch (Exception ignored) { }
            p.mp = null;
        }
        p.prepared = false;
    }

    private static void prepare(P p, File f) {
        releaseMp(p);
        // 刪掉這個播放器之前的暫存音檔
        File[] old = app.getCacheDir().listFiles((d, n) -> n.startsWith("nb_audio_" + p.id + "_") && !n.equals(f.getName()));
        if (old != null) for (File o : old) o.delete();
        MediaPlayer mp = new MediaPlayer();
        p.mp = mp;
        try {
            mp.setAudioAttributes(Mix.attrs());
            mp.setDataSource(f.getAbsolutePath());
            mp.setOnPreparedListener(m -> {
                if (p.mp != m) return;
                p.prepared = true;
                Bridge.emit("a", p.id, "meta", "{\"dur\":" + (m.getDuration() / 1000.0) + "}");
                if (p.pendingSeek >= 0) { m.seekTo((long) (p.pendingSeek * 1000), MediaPlayer.SEEK_CLOSEST); p.pendingSeek = -1; }
                if (p.wantPlay) start(p);
            });
            mp.setOnCompletionListener(m -> {
                if (p.mp != m) return;
                p.wantPlay = false;
                Mix.abandon();
                Bridge.emit("a", p.id, "ended", "{\"t\":" + (m.getDuration() / 1000.0) + "}");
                PlayService.setPlaying(false);
            });
            mp.setOnErrorListener((m, what, extra) -> {
                if (p.mp == m) Bridge.emit("a", p.id, "error", "{\"code\":" + what + "}");
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            Bridge.emit("a", p.id, "error", "{\"code\":-1}");
        }
    }

    private static void start(P p) {
        if (p.mp == null || !p.prepared) return;
        Mix.acquire(() -> h.post(() -> lostFocus(p)));
        try {
            p.mp.start();
            if (Math.abs(p.rate - 1f) > 0.001f) p.mp.setPlaybackParams(p.mp.getPlaybackParams().setSpeed(p.rate));
        } catch (Exception e) {
            Bridge.emit("a", p.id, "error", "{\"code\":-3}");
            return;
        }
        PlayService.ensure(app);
        PlayService.setPlaying(true);
        tick();
    }

    /** 「被打斷就停」模式：其他 App 搶走聲音 → 暫停，並告訴網頁（網頁會記下被中斷，回到 App 時接著播） */
    private static void lostFocus(P p) {
        if (p.mp == null || !p.prepared || !p.mp.isPlaying()) return;
        p.mp.pause();
        p.wantPlay = false;
        Mix.abandon();
        Bridge.emit("a", p.id, "pause", "{\"t\":" + (p.mp.getCurrentPosition() / 1000.0) + "}");
        PlayService.setPlaying(false);
    }

    static void play(int id) {
        h.post(() -> {
            P p = get(id);
            p.wantPlay = true;
            if (p.prepared) start(p);
        });
    }

    static void pause(int id) {
        h.post(() -> {
            P p = ps.get(id);
            if (p == null) return;
            p.wantPlay = false;
            if (p.mp != null && p.prepared && p.mp.isPlaying()) {
                p.mp.pause();
                Bridge.emit("a", p.id, "time", "{\"t\":" + (p.mp.getCurrentPosition() / 1000.0) + "}");
            }
            Mix.abandon();
            PlayService.setPlaying(false);
        });
    }

    static void seek(int id, double sec) {
        h.post(() -> {
            P p = get(id);
            if (p.mp != null && p.prepared) p.mp.seekTo((long) (sec * 1000), MediaPlayer.SEEK_CLOSEST);
            else p.pendingSeek = sec;
        });
    }

    static void rate(int id, double r) {
        h.post(() -> {
            P p = get(id);
            p.rate = (float) Math.max(0.25, Math.min(4, r));
            if (p.mp != null && p.prepared && p.mp.isPlaying()) {
                try { p.mp.setPlaybackParams(p.mp.getPlaybackParams().setSpeed(p.rate)); } catch (Exception ignored) { }
            }
        });
    }

    static void release(int id) {
        h.post(() -> {
            P p = ps.remove(id);
            if (p == null) return;
            synchronized (Player.class) { closeOut(p); }
            releaseMp(p);
            if (p.file != null) p.file.delete();
        });
    }

    private static final Runnable tickR = new Runnable() {
        @Override public void run() {
            boolean any = false;
            for (P p : ps.values()) {
                if (p.mp != null && p.prepared && p.mp.isPlaying()) {
                    any = true;
                    Bridge.emit("a", p.id, "time", "{\"t\":" + (p.mp.getCurrentPosition() / 1000.0) + "}");
                }
            }
            if (any) h.postDelayed(this, 250);
            else ticking = false;
        }
    };

    private static void tick() {
        if (ticking) return;
        ticking = true;
        h.postDelayed(tickR, 250);
    }
}

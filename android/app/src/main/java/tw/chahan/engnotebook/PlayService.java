package tw.chahan.engnotebook;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * 播放視窗開著時的前景服務：讓 App 在背景、關螢幕時不被系統停掉，
 * 並在通知列與鎖定畫面顯示「上一句／播放暫停／下一句」。
 */
public class PlayService extends Service {
    private static final String CH = "play";
    private static final int NID = 1;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Context app;
    private static PlayService inst;
    private static String title = "英語筆記本", text = "";
    private static boolean playing;

    private MediaSession ms;

    static void ensure(Context c) {
        if (c != null) app = c.getApplicationContext();
        main.post(() -> {
            if (inst != null || app == null) return;
            try { app.startForegroundService(new Intent(app, PlayService.class)); } catch (Exception ignored) { }
        });
    }

    static void update(Context c, String t, String sub, boolean pl) {
        main.post(() -> {
            boolean changed = !t.equals(title) || !sub.equals(text) || pl != playing;
            title = t; text = sub; playing = pl;
            if (inst == null) ensure(c);
            else if (changed) inst.refresh();
        });
    }

    static void setPlaying(boolean pl) {
        main.post(() -> {
            if (pl == playing) return;
            playing = pl;
            if (inst != null) inst.refresh();
        });
    }

    static void stop() {
        main.post(() -> {
            playing = false;
            if (inst != null) {
                inst.stopForeground(STOP_FOREGROUND_REMOVE);
                inst.stopSelf();
            }
        });
    }

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        if (app == null) app = getApplicationContext();
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CH, "例句播放", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);

        ms = new MediaSession(this, "EngNotebook");
        ms.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { MainActivity.js("playerPlay()"); }
            @Override public void onPause() { MainActivity.js("playerPause()"); }
            @Override public void onStop() { MainActivity.js("playerPause()"); }
            @Override public void onSkipToNext() { MainActivity.js("playerJump(1)"); }
            @Override public void onSkipToPrevious() { MainActivity.js("playerJump(-1)"); }
        });
        ms.setActive(true);
        Notification n = build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(NID, n);
        refresh();
    }

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        String a = i == null ? null : i.getAction();
        if ("prev".equals(a)) MainActivity.js("playerJump(-1)");
        else if ("next".equals(a)) MainActivity.js("playerJump(1)");
        else if ("toggle".equals(a)) MainActivity.js("playerTogglePlay()");
        return START_NOT_STICKY;
    }

    private PendingIntent action(String a) {
        Intent i = new Intent(this, PlayService.class).setAction(a);
        return PendingIntent.getService(this, a.hashCode(), i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification build() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pOpen = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Action prev = new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_previous), "上一句", action("prev")).build();
        Notification.Action toggle = new Notification.Action.Builder(
                Icon.createWithResource(this, playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play),
                playing ? "暫停" : "播放", action("toggle")).build();
        Notification.Action next = new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_next), "下一句", action("next")).build();
        return new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pOpen)
                .setOngoing(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(prev).addAction(toggle).addAction(next)
                .setStyle(new Notification.MediaStyle().setMediaSession(ms.getSessionToken()).setShowActionsInCompactView(0, 1, 2))
                .build();
    }

    private void refresh() {
        ms.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, text)
                .build());
        ms.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build());
        getSystemService(NotificationManager.class).notify(NID, build());
    }

    @Override
    public void onDestroy() {
        inst = null;
        if (ms != null) { ms.setActive(false); ms.release(); }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) { return null; }
}

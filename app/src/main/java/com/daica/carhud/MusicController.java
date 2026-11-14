package com.daica.carhud;

import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.view.KeyEvent;

import java.util.List;

/**
 * Lấy tên bài / ca sĩ / trạng thái phát từ app Bluetooth của màn và điều khiển.
 * Nguồn 1: MediaSession (cần quyền truy cập thông báo).
 * Nguồn 2: broadcast kiểu "com.android.music.metachanged" mà nhiều màn Android phát ra.
 * Nguồn 3: nội dung thông báo của app nhạc/Bluetooth.
 * Điều khiển: qua MediaSession, nếu không có thì gửi phím media cho hệ thống.
 */
public class MusicController {
    public interface Listener { void onMusicChanged(); }

    public static MusicController instance;

    public String title = "", artist = "";
    public boolean playing = false;
    public long position = 0, duration = 0, posTime = 0;
    public Bitmap art;
    public boolean hasSession = false;

    private final Context ctx;
    private final Listener listener;
    private final Handler h = new Handler(Looper.getMainLooper());
    private MediaSessionManager msm;
    private MediaController mc;
    private long lastBroadcast = 0;

    private final MediaController.Callback cb = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata m) { readMeta(m); fire(); }
        @Override public void onPlaybackStateChanged(PlaybackState s) { readState(s); pickSession(); fire(); }
        @Override public void onSessionDestroyed() { h.post(new Runnable() { public void run() { pickSession(); } }); }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessListener =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                public void onActiveSessionsChanged(List<MediaController> l) { pickSession(); }
            };

    private final BroadcastReceiver metaReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            Bundle b = i.getExtras();
            if (b == null) return;
            String t = str(b, "track", "title", "TITLE", "songName", "name");
            String a = str(b, "artist", "ARTIST", "singer", "artistName");
            if (b.containsKey("playing")) playing = b.getBoolean("playing");
            else if (b.containsKey("isPlaying")) playing = b.getBoolean("isPlaying");
            if (b.containsKey("duration")) duration = numL(b.get("duration"));
            if (b.containsKey("position")) { position = numL(b.get("position")); posTime = SystemClock.elapsedRealtime(); }
            if (!hasSession && t != null) {
                title = t;
                artist = a == null ? "" : a;
                lastBroadcast = SystemClock.elapsedRealtime();
                fire();
            } else if (!hasSession) fire();
        }
    };

    public MusicController(Context c, Listener l) {
        ctx = c.getApplicationContext();
        listener = l;
        instance = this;
        msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
        IntentFilter f = new IntentFilter();
        String[] acts = {"com.android.music.metachanged", "com.android.music.playstatechanged",
                "com.android.music.playbackcomplete", "com.android.bt.metachanged",
                "com.spotify.music.metadatachanged", "com.nwd.action.ACTION_BT_MUSIC_INFO",
                "com.syu.bt.music.info", "com.microntek.bt.music"};
        for (String a : acts) f.addAction(a);
        ctx.registerReceiver(metaReceiver, f);
        start();
    }

    public static boolean hasPermission(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(c.getPackageName());
    }

    public void start() {
        if (msm == null || !hasPermission(ctx)) return;
        try {
            ComponentName cn = new ComponentName(ctx, MediaListenerService.class);
            msm.removeOnActiveSessionsChangedListener(sessListener);
            msm.addOnActiveSessionsChangedListener(sessListener, cn, h);
            pickSession();
        } catch (SecurityException ignored) {
        }
    }

    static void onListenerReady() {
        final MusicController m = instance;
        if (m != null) m.h.post(new Runnable() { public void run() { m.start(); } });
    }

    static void onNotification(final StatusBarNotification sbn) {
        final MusicController m = instance;
        if (m == null) return;
        m.h.post(new Runnable() { public void run() { m.fromNotification(sbn); } });
    }

    private void fromNotification(StatusBarNotification sbn) {
        if (hasSession || sbn == null) return;
        String pkg = sbn.getPackageName().toLowerCase();
        if (pkg.equals(ctx.getPackageName())) return;
        if (!(pkg.contains("bt") || pkg.contains("blue") || pkg.contains("music") || pkg.contains("media") || pkg.contains("a2dp"))) return;
        if (SystemClock.elapsedRealtime() - lastBroadcast < 5000) return;
        Notification n = sbn.getNotification();
        if (n == null || n.extras == null) return;
        CharSequence t = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence a = n.extras.getCharSequence(Notification.EXTRA_TEXT);
        if (t == null) return;
        title = t.toString();
        artist = a == null ? "" : a.toString();
        fire();
    }

    private void pickSession() {
        if (msm == null) return;
        List<MediaController> list;
        try {
            list = msm.getActiveSessions(new ComponentName(ctx, MediaListenerService.class));
        } catch (SecurityException e) {
            return;
        }
        MediaController best = null;
        for (MediaController c : list) {
            if (c.getPackageName().equals(ctx.getPackageName())) continue;
            PlaybackState s = c.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) { best = c; break; }
            if (best == null) best = c;
        }
        if (best == null) {
            if (mc != null) mc.unregisterCallback(cb);
            mc = null;
            hasSession = false;
            fire();
            return;
        }
        if (mc != null && mc.getSessionToken().equals(best.getSessionToken())) return;
        if (mc != null) mc.unregisterCallback(cb);
        mc = best;
        hasSession = true;
        mc.registerCallback(cb, h);
        readMeta(mc.getMetadata());
        readState(mc.getPlaybackState());
        fire();
    }

    private void readMeta(MediaMetadata m) {
        if (m == null) return;
        String t = m.getString(MediaMetadata.METADATA_KEY_TITLE);
        String a = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (a == null) a = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        title = t == null ? "" : t;
        artist = a == null ? "" : a;
        duration = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        Bitmap b = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        art = b;
    }

    private void readState(PlaybackState s) {
        if (s == null) return;
        playing = s.getState() == PlaybackState.STATE_PLAYING;
        position = s.getPosition();
        posTime = s.getLastPositionUpdateTime();
        if (posTime <= 0) posTime = SystemClock.elapsedRealtime();
    }

    /** Vị trí hiện tại (ms), có cộng thời gian đã trôi khi đang phát. */
    public long currentPos() {
        if (duration <= 0) return 0;
        long p = position;
        if (playing && posTime > 0) p += SystemClock.elapsedRealtime() - posTime;
        return Math.max(0, Math.min(duration, p));
    }

    public void playPause() {
        if (mc != null) {
            if (playing) mc.getTransportControls().pause(); else mc.getTransportControls().play();
        } else key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        if (mc == null) { playing = !playing; fire(); }
    }

    public void next() {
        if (mc != null) mc.getTransportControls().skipToNext(); else key(KeyEvent.KEYCODE_MEDIA_NEXT);
    }

    public void prev() {
        if (mc != null) mc.getTransportControls().skipToPrevious(); else key(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
    }

    private void key(int code) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
    }

    private void fire() {
        if (listener != null) listener.onMusicChanged();
    }

    public void release() {
        try { ctx.unregisterReceiver(metaReceiver); } catch (Exception ignored) {}
        try { if (msm != null) msm.removeOnActiveSessionsChangedListener(sessListener); } catch (Exception ignored) {}
        if (mc != null) mc.unregisterCallback(cb);
        if (instance == this) instance = null;
    }

    private static String str(Bundle b, String... keys) {
        for (String k : keys) {
            Object o = b.get(k);
            if (o != null && o.toString().length() > 0) return o.toString();
        }
        return null;
    }

    private static long numL(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(String.valueOf(o)); } catch (Exception e) { return 0; }
    }
}

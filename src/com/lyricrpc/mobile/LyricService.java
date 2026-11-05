package com.lyricrpc.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;

import com.lyricrpc.mobile.core.Decorations;
import com.lyricrpc.mobile.core.Track;

/**
 * The foreground service that keeps the lyric alive: it owns the engine, the ongoing notification and the
 * overlay, and it is what the notification's buttons talk back to.
 */
public final class LyricService extends Service {
    public static final String ACTION_START = "com.lyricrpc.mobile.START";
    public static final String ACTION_STOP = "com.lyricrpc.mobile.STOP";
    public static final String ACTION_COPY = "com.lyricrpc.mobile.COPY";
    public static final String ACTION_OFFSET = "com.lyricrpc.mobile.OFFSET";
    public static final String ACTION_DECORATION = "com.lyricrpc.mobile.DECORATION";
    public static final String ACTION_RESTYLE = "com.lyricrpc.mobile.RESTYLE";
    public static final String EXTRA_OFFSET_MS = "offsetMs";

    private static final String CHANNEL = "lyrics";
    private static final int NOTIFICATION_ID = 4711;
    /** Notification updates are cheap but not free; the line only changes a few times per song anyway. */
    private static final long NOTIFY_MIN_MS = 400;

    private static volatile boolean running;
    /** The screen reads through this so the UI and the service never hold two views of the same state. */
    private static volatile LyricService self;

    private final Handler main = new Handler(Looper.getMainLooper());
    private LyricEngine engine;
    private Overlay overlay;
    private Prefs prefs;

    private String lastNotified = "";
    private long lastNotifiedAt;

    public static boolean isRunning() {
        return running;
    }

    public static LyricService current() {
        return self;
    }

    /** The live snapshot the UI paints, or null until the service exists. */
    public LyricEngine.State state() {
        return engine == null ? null : engine.snapshot();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        self = this;
        prefs = new Prefs(this);
        channel();
        engine = new LyricEngine(this);
        overlay = new Overlay(this);
        running = true;
        // The service has to be foreground within seconds of starting, so it announces itself first and
        // fills in the lyric as soon as the engine has one.
        startForeground(NOTIFICATION_ID, build("LyricRPC", "waiting for a track...", null));
        engine.start(new LyricEngine.Listener() {
            @Override
            public void onUpdate(final LyricEngine.State state) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        render(state);
                    }
                });
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null) handle(intent);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        // Only our own handle: a restart that is already onCreate would otherwise be erased here.
        if (self == this) self = null;
        if (engine != null) engine.stop();
        if (overlay != null) overlay.hide();
        super.onDestroy();
    }

    private void handle(Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        if (ACTION_COPY.equals(action)) {
            // A notification tap is the one route Android allows for the hop into Discord.
            Handoff.copy(this, engine == null ? "" : engine.snapshot().text, prefs, true, "lyric");
            return;
        }
        if (ACTION_OFFSET.equals(action)) {
            long delta = intent.getLongExtra(EXTRA_OFFSET_MS, 0);
            prefs.addOffset(delta);
            if (engine != null) engine.restyle();
            return;
        }
        if (ACTION_DECORATION.equals(action)) {
            prefs.setDecoration(Decorations.sortedValues().get(
                    (Decorations.sortedValues().indexOf(prefs.decoration()) + 1)
                            % Decorations.sortedValues().size()));
            if (engine != null) engine.restyle();
            return;
        }
        if (ACTION_RESTYLE.equals(action)) {
            if (overlay != null) overlay.applyStyle(prefs);
            if (engine != null) engine.restyle();
        }
    }

    private void render(LyricEngine.State state) {
        overlay.show(state, prefs);

        String title = state.track == null ? "LyricRPC" : header(state.track);
        String body = TextUtils.isEmpty(state.text)
                ? (state.searching ? "finding the lyric..." : state.status)
                : state.text;
        long now = System.currentTimeMillis();
        String signature = title + "\n" + body + "\n" + state.upcoming;
        if (signature.equals(lastNotified) || now - lastNotifiedAt < NOTIFY_MIN_MS) return;
        lastNotified = signature;
        lastNotifiedAt = now;
        notify(build(title, body, state));
    }

    private static String header(Track track) {
        String artist = TextUtils.isEmpty(track.artist) ? track.title : track.artist + " - " + track.title;
        return artist.length() > 70 ? artist.substring(0, 69) + "..." : artist;
    }

    private void notify(Notification notification) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, notification);
    }

    private Notification build(String title, String body, LyricEngine.State state) {
        String upcoming = state == null ? "" : state.upcoming;
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(R.drawable.ic_lyric)
                .setOngoing(true)
                .setContentIntent(tap(ACTION_START))
                .setColor(Theme.accent(this, prefs));

        // The expanded view carries the next lines, so the user can read ahead without opening anything.
        Notification.BigTextStyle style = new Notification.BigTextStyle();
        style.bigText(TextUtils.isEmpty(upcoming) ? body : body + "\n\n" + upcoming);
        builder.setStyle(style);

        if (state != null) {
            if (prefs.artwork() && state.art != null) builder.setLargeIcon(state.art);
            int percent = percent(state.track);
            if (percent >= 0) builder.setProgress(100, percent, false);
        }

        builder.addAction(action("Copy", ACTION_COPY, null));
        builder.addAction(action("-50ms", ACTION_OFFSET, -50L));
        builder.addAction(action("+50ms", ACTION_OFFSET, 50L));
        builder.addAction(action("Style", ACTION_DECORATION, null));
        return builder.build();
    }

    /** The bar is decoration, not a control, so a coarse percentage is enough - and -1 means no bar. */
    private static int percent(Track track) {
        if (track == null || track.durationMs <= 0) return -1;
        long done = Math.max(0, Math.min(track.durationMs, track.progressMs));
        return (int) (done * 100 / track.durationMs);
    }

    private Notification.Action action(String label, String action, Long extra) {
        Intent intent = new Intent(this, LyricService.class).setAction(action);
        if (extra != null) intent.putExtra(EXTRA_OFFSET_MS, extra);
        // Service actions have to go through a broadcast-free explicit component: startService carries it.
        PendingIntent pending = PendingIntent.getService(this, action.hashCode(), intent, flag());
        return new Notification.Action(0, label, pending);
    }

    private PendingIntent tap(String action) {
        Intent intent = new Intent(this, MainActivity.class).setAction(action);
        return PendingIntent.getActivity(this, 0, intent, flag());
    }

    private static int flag() {
        // FLAG_IMMUTABLE is mandatory from API 31 and accepted from 23, so one flag serves the whole range.
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
    }

    private void channel() {
        NotificationChannel existing = channelManager().getNotificationChannel(CHANNEL);
        if (existing != null) return;
        // Low importance on purpose: a lyric must not make a sound or pop above the music app's own card.
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Lyrics", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        channel.setSound(null, null);
        channelManager().createNotificationChannel(channel);
    }

    private NotificationManager channelManager() {
        return (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    }
}

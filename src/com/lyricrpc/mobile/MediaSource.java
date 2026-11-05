package com.lyricrpc.mobile;

import android.content.ComponentName;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;

import com.lyricrpc.mobile.core.Text;
import com.lyricrpc.mobile.core.Track;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads whatever the music app is playing through Android's media sessions. This is the same public API a
 * watch face or a car dashboards uses: no hooking, no root, no token, and nothing of the user's is stored.
 *
 * <p>It only works once the user has granted notification access, because that is what
 * {@link MediaSessionManager#getActiveSessions} requires. The framework then hands back live controllers;
 * nothing is read out of the music app's own process.</p>
 */
public final class MediaSource {
    public static final String SPOTIFY = "com.spotify.music";

    /** A notification tile never needs more than this, and a shade does not need a full-size cover. */
    private static final int ART_SIDE = 320;

    /** Ordered by "the app whose lyric the user most likely means", with the others as fallbacks. */
    private static final String[] PREFERRED = {
            SPOTIFY,
            "com.spotify.lite",
            "com.apple.android.music",
            "com.google.android.apps.youtube.music",
            "deezer.android.app",
            "com.amazon.mp3",
            "com.pandora.android",
            "soundcloud.player",
    };

    private final Context context;

    MediaSource(Context context) {
        this.context = context.getApplicationContext();
    }

    /** True when the user has given this app notification access, which the session list needs. */
    public boolean hasAccess() {
        // The Settings.Secure.ENABLED_NOTIFICATION_LISTENERS constant is hidden from the public stub,
        // so the key is spelled out; it has been the same string since the API was added.
        String listeners = Settings.Secure.getString(context.getContentResolver(),
                "enabled_notification_listeners");
        return !TextUtils.isEmpty(listeners) && listeners.contains(context.getPackageName());
    }

    /**
     * The track currently playing, or null when nothing recognisable is. A paused session still answers,
     * which is what the caller wants: the lyric stays on screen while the song is paused, exactly as it
     * does on the desktop.
     */
    public Track read() {
        List<MediaController> sessions = activeSessions();
        MediaController best = null;
        int bestRank = Integer.MAX_VALUE;

        for (MediaController controller : sessions) {
            android.media.MediaMetadata metadata = controller.getMetadata();
            if (metadata == null) continue;
            if (Text.clean(metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)).isEmpty()) continue;
            String packageName = controller.getPackageName();
            if (context.getPackageName().equals(packageName)) continue;

            int rank = rank(packageName, isPlaying(controller));
            if (rank < bestRank) {
                bestRank = rank;
                best = controller;
            }
        }
        return best == null ? null : toTrack(best);
    }

    /**
     * The artwork of the session that {@link #read()} picked, or null when the player publishes none.
     * Called once per song rather than per poll: the bitmap is only ever meant for the notification.
     */
    public android.graphics.Bitmap artwork(String packageName) {
        if (TextUtils.isEmpty(packageName)) return null;
        for (MediaController controller : activeSessions()) {
            if (!packageName.equals(controller.getPackageName())) continue;
            return artworkOf(controller.getMetadata());
        }
        return null;
    }

    private static android.graphics.Bitmap artworkOf(android.media.MediaMetadata metadata) {
        if (metadata == null) return null;
        android.graphics.Bitmap art = metadata.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = metadata.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART);
        if (art == null || art.getWidth() <= 0 || art.getHeight() <= 0) return null;
        // A 2000x2000 cover would be a heap spike inside a service that is meant to be invisible.
        return android.media.ThumbnailUtils.extractThumbnail(art, ART_SIDE, ART_SIDE);
    }

    private static int rank(String packageName, boolean playing) {
        for (int i = 0; i < PREFERRED.length; i++)
            if (PREFERRED[i].equals(packageName)) return i * 2 + (playing ? 0 : 1);
        // An unknown player is still a player, but it must not outrank a paused Spotify.
        return playing ? PREFERRED.length * 2 : PREFERRED.length * 2 + 1;
    }

    private List<MediaController> activeSessions() {
        List<MediaController> out = new ArrayList<MediaController>();
        try {
            MediaSessionManager manager = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (manager == null) return out;
            ComponentName listener = new ComponentName(context, LyricListenerService.class);
            List<MediaController> sessions = manager.getActiveSessions(listener);
            if (sessions != null) out.addAll(sessions);
        } catch (SecurityException accessNotGranted) {
            // Expected before the user grants notification access; the UI shows that as a permission tile.
        } catch (Exception anythingElse) {
            // A vendor ROM that throws on the session list must not take the app down with it.
        }
        return out;
    }

    private static Track toTrack(MediaController controller) {
        android.media.MediaMetadata metadata = controller.getMetadata();
        PlaybackState state = controller.getPlaybackState();
        Track track = new Track();
        track.title = Text.clean(metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE));
        track.artist = firstNonEmpty(
                Text.clean(metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)),
                Text.clean(metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST)));
        track.album = Text.clean(metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM));
        track.durationMs = Math.max(0, metadata.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION));
        track.packageName = controller.getPackageName();
        track.playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;
        track.progressMs = position(state, track);
        return track;
    }

    /**
     * Media sessions report the position as of the last seek or state change, not as of now, so a song
     * that has been playing since then has to be advanced by the wall clock.
     * {@link PlaybackState#getLastPositionUpdateTime()} is stamped on the monotonic clock precisely for
     * this, so a network time jump or a changed system clock cannot move the lyric.
     */
    private static long position(PlaybackState state, Track track) {
        if (state == null) return 0;
        long position = Math.max(0, state.getPosition());
        long since = SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime();
        if (!track.playing || since <= 0) return position;
        float speed = state.getPlaybackSpeed();
        long advanced = position + (long) (since * (speed > 0f ? speed : 1f));
        return track.durationMs > 0 ? Math.min(advanced, track.durationMs) : advanced;
    }

    private static boolean isPlaying(MediaController controller) {
        PlaybackState state = controller.getPlaybackState();
        return state != null && (state.getState() == PlaybackState.STATE_PLAYING
                || state.getState() == PlaybackState.STATE_BUFFERING);
    }

    private static String firstNonEmpty(String a, String b) {
        return TextUtils.isEmpty(a) ? b : a;
    }
}

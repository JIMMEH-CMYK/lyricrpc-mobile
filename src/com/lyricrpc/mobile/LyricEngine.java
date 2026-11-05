package com.lyricrpc.mobile;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import com.lyricrpc.mobile.core.Http;
import com.lyricrpc.mobile.core.Line;
import com.lyricrpc.mobile.core.Lyrics;
import com.lyricrpc.mobile.core.Presentation;
import com.lyricrpc.mobile.core.Providers;
import com.lyricrpc.mobile.core.Track;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The loop: read what is playing, get its lyric once per song, then work out which line is being sung.
 *
 * <p>State lives on one engine thread, so a callback never sees a half-updated track. The network runs on
 * a single worker, so one slow provider cannot stop the display and only one request per song is ever in
 * flight.</p>
 */
public final class LyricEngine {
    /** The lyric is shown at quarter-second resolution; the media session is polled slower than that. */
    private static final long TICK_MS = 250;
    private static final long POLL_MS = 700;
    private static final int CACHE_SONGS = 12;
    /** A song with no lyric anywhere must not be re-queried every time it comes back on. */
    private static final long EMPTY_RETRY_MS = 10 * 60 * 1000;
    /** How many following lines the expanded notification shows. */
    private static final int UPCOMING_LINES = 5;

    public interface Listener {
        /** Called on the engine thread whenever the line, the track or the status text changed. */
        void onUpdate(State state);
    }

    /** A snapshot the notification and the overlay render without touching any of our internals. */
    public static final class State {
        public Track track;
        public Lyrics.Result lyrics;
        public Line line;
        public String text = "";
        public String upcoming = "";
        public String status = "";
        public boolean searching;
        /** The cover of the current song, already downscaled, or null. Only the notification uses it. */
        public android.graphics.Bitmap art;

        public boolean isPlaying() {
            return track != null && track.playing;
        }

        State copy() {
            State out = new State();
            out.track = track;
            out.lyrics = lyrics;
            out.line = line;
            out.text = text;
            out.upcoming = upcoming;
            out.status = status;
            out.searching = searching;
            out.art = art;
            return out;
        }
    }

    private final Prefs prefs;
    private final MediaSource source;
    private final Providers providers;
    private final Map<String, Lyrics.Result> cache = new HashMap<String, Lyrics.Result>();
    private final Map<String, Long> emptySince = new HashMap<String, Long>();

    /** Mutated only on the engine thread; readers get {@link #published} instead. */
    private final State state = new State();
    /** The last snapshot handed out, swapped as one reference so a reader never sees a half-updated track. */
    private volatile State published = new State();
    private HandlerThread thread;
    private ExecutorService network;
    private Handler handler;
    private Listener listener;
    private String requestKey;
    private long lastPollAt;
    private volatile boolean running;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            pollIfNeeded();
            publish();
            if (handler != null) handler.postDelayed(this, TICK_MS);
        }
    };

    public LyricEngine(Context context) {
        this.prefs = new Prefs(context);
        this.source = new MediaSource(context);
        this.providers = new Providers(new Http(9000));
    }

    public State snapshot() {
        return published;
    }

    public void start(Listener listener) {
        if (running) return;
        this.listener = listener;
        running = true;
        thread = new HandlerThread("lyric-engine");
        thread.start();
        network = Executors.newSingleThreadExecutor();
        handler = new Handler(thread.getLooper());
        handler.post(tick);
    }

    public void stop() {
        running = false;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (thread != null) thread.quitSafely();
        if (network != null) network.shutdownNow();
        handler = null;
        thread = null;
        network = null;
        requestKey = null;
        listener = null;
    }

    /** A setting changed: re-render at once instead of waiting for the next line. */
    public void restyle() {
        Handler target = handler;
        if (target != null) target.post(new Runnable() {
            @Override
            public void run() {
                // Artwork is only taken once per song, so switching it back on mid-song asks for it now.
                if (state.art == null && prefs.artwork() && state.track != null)
                    state.art = source.artwork(state.track.packageName);
                publish();
            }
        });
    }

    /** True once the app can see the music app at all; the UI shows it as a permission state. */
    public boolean hasMediaAccess() {
        return source.hasAccess();
    }

    // --- internals --------------------------------------------------------------------------

    private void pollIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastPollAt < POLL_MS) return;
        lastPollAt = now;

        Track track = source.read();
        if (track == null) {
            state.track = null;
            state.lyrics = null;
            state.art = null;
            state.status = "";
            requestKey = null;
            return;
        }
        state.track = track;

        if (!prefs.enabled()) {
            state.status = "paused from the app";
            return;
        }
        if (requestKey == null || !requestKey.equals(track.songId())) request(track);
    }

    private void request(final Track track) {
        final String key = track.songId();
        requestKey = key;
        state.art = prefs.artwork() ? source.artwork(track.packageName) : null;

        Lyrics.Result cached = cache.get(key);
        if (cached != null) {
            state.lyrics = cached;
            state.searching = false;
            state.status = "from " + cached.source;
            return;
        }
        Long refusedAt = emptySince.get(key);
        if (refusedAt != null && System.currentTimeMillis() - refusedAt < EMPTY_RETRY_MS) {
            state.lyrics = null;
            state.searching = false;
            state.status = "no synced lyric found for this track";
            return;
        }

        state.lyrics = null;
        state.searching = true;
        state.status = "looking up the lyric...";

        final ExecutorService worker = network;
        if (worker == null || worker.isShutdown()) return;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                Lyrics.Result found = null;
                String reason = null;
                try {
                    found = providers.fetch(track, prefs.providers());
                } catch (Exception failure) {
                    reason = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
                }
                deliver(track, key, found, reason);
            }
        });
    }

    private void deliver(final Track track, final String key, final Lyrics.Result found, final String reason) {
        Handler target = handler;
        if (target == null) return;
        target.post(new Runnable() {
            @Override
            public void run() {
                state.searching = false;
                // Only answer for the song that is still playing: a fast skip must not paste the
                // previous track's lyric onto the new one.
                boolean stillCurrent = key.equals(requestKey) && track.isSameSongAs(state.track);
                if (found != null && !found.isEmpty()) {
                    if (cache.size() >= CACHE_SONGS) cache.clear();
                    cache.put(key, found);
                    if (stillCurrent) {
                        state.lyrics = found;
                        state.status = "from " + found.source;
                    }
                } else {
                    emptySince.put(key, System.currentTimeMillis());
                    if (stillCurrent) {
                        state.lyrics = null;
                        state.status = reason == null ? "no synced lyric found" : reason;
                    }
                }
                publish();
            }
        });
    }

    private void publish() {
        Presentation.Style style = prefs.style();
        long progress = progress();
        state.line = state.lyrics == null ? null : Presentation.currentLine(state.lyrics.lines, progress, style);
        state.text = state.lyrics == null ? "" : Presentation.at(state.lyrics.lines, progress, style);
        state.upcoming = upcoming(state.lyrics, progress, style);
        published = state.copy();
        if (listener != null) listener.onUpdate(published);
    }

    /**
     * The engine polls at up to {@link #POLL_MS}, so the position read out of the media session is already
     * that old when it is shown. Interpolating with the local clock is what stops the line stepping once
     * per poll instead of flowing.
     */
    private long progress() {
        if (state.track == null) return 0;
        if (!state.track.playing) return state.track.progressMs;
        long since = System.currentTimeMillis() - lastPollAt;
        return state.track.progressMs + Math.max(0, Math.min(POLL_MS, since));
    }

    private static String upcoming(Lyrics.Result lyrics, long progress, Presentation.Style style) {
        if (lyrics == null) return "";
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (Line line : lyrics.lines) {
            if (line.timeMs <= progress - style.offsetMs) continue;
            if (out.length() > 0) out.append('\n');
            out.append(Presentation.line(line, progress, style));
            if (++shown >= UPCOMING_LINES) break;
        }
        return out.toString();
    }
}

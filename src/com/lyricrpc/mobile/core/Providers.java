package com.lyricrpc.mobile.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The community lyric sources, tried in order, each with one job: turn a track into timed lines. The table
 * and the query shapes are carried over from the desktop plugin so the same song resolves to the same
 * lyric on both. "Spicy Lyrics" is missing on purpose - that provider is a bridge into the Spicetify
 * desktop client, which a phone does not have.
 */
public final class Providers {
    public static final class Entry {
        public final String key;
        public final String label;
        public final String blurb;

        Entry(String key, String label, String blurb) {
            this.key = key;
            this.label = label;
            this.blurb = blurb;
        }
    }

    /** The order the chain tries them in: most specific and best-timed first, LRCLIB last as the reliable floor. */
    public static final Entry[] ALL = {
            new Entry("rmm", "RMM Revival", "Apple Music lyrics by catalog id."),
            new Entry("amll", "AMLL TTML DB", "Community Apple Music word timing."),
            new Entry("lrcRed", "lrc.red", "Word-synced TTML by ISRC."),
            new Entry("unison", "Unison", "Apple Music timing with word spans."),
            new Entry("lrcmux", "LRCMux", "Several lyric sites in one call."),
            new Entry("lrclib", "LRCLIB", "Synced LRC with a duration-aware search."),
    };

    private final Http http;

    public Providers(Http http) {
        this.http = http;
    }

    public static List<String> defaultEnabled() {
        List<String> keys = new ArrayList<String>();
        for (Entry entry : ALL) keys.add(entry.key);
        return keys;
    }

    /**
     * Walks the enabled providers and returns the first answer with synced lines. A provider that simply
     * has nothing for this song is not an error, so its reason is collected and reported by the one that
     * got furthest - the same "why did I get nothing" trail the desktop diagnostics show.
     */
    public Lyrics.Result fetch(Track track, List<String> enabled) {
        List<String> reasons = new ArrayList<String>();
        for (Entry entry : ALL) {
            if (enabled != null && !enabled.contains(entry.key)) continue;
            try {
                Lyrics.Result result = fetch(entry.key, track);
                if (result != null && !result.isEmpty()) return result;
                reasons.add(entry.label + ": no synced lyrics");
            } catch (Exception error) {
                reasons.add(entry.label + ": " + message(error));
            }
        }
        throw new IllegalStateException(
                reasons.isEmpty() ? "every lyric source is turned off" : join(reasons));
    }

    public Lyrics.Result fetch(String key, Track track) throws IOException {
        Lyrics.Result result;
        if ("lrclib".equals(key)) result = lrclib(track);
        else if ("lrcmux".equals(key)) result = lrcmux(track);
        else if ("amll".equals(key)) result = amll(track);
        else if ("lrcRed".equals(key)) result = lrcRed(track);
        else if ("rmm".equals(key)) result = rmm(track);
        else if ("unison".equals(key)) result = unison(track);
        else throw new IllegalArgumentException("unknown lyric source " + key);
        return result == null ? null : new Lyrics.Result(sorted(result.lines), result.source, result.trackName, result.artistName);
    }

    /** Providers list lines in response order, which is not always time order. */
    private static List<Line> sorted(List<Line> lines) {
        List<Line> copy = new ArrayList<Line>(lines);
        Collections.sort(copy, new Comparator<Line>() {
            public int compare(Line a, Line b) {
                return Long.compare(a.timeMs, b.timeMs);
            }
        });
        return copy;
    }

    private static String message(Exception error) {
        String text = error.getMessage();
        return text == null || text.isEmpty() ? error.getClass().getSimpleName() : text;
    }

    private static String join(List<String> parts) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (out.length() > 0) out.append(" | ");
            out.append(part);
        }
        return out.toString();
    }

    // --- LRCLIB -----------------------------------------------------------------------------

    private Lyrics.Result lrclib(Track track) throws IOException {
        List<String[]> variants = Query.variants(track);

        for (String[] variant : variants) {
            StringBuilder url = new StringBuilder("https://lrclib.net/api/get?")
                    .append(Query.params("track_name", variant[0], "artist_name", variant[1]));
            if (!Query.title(track.album).isEmpty())
                url.append('&').append(Query.params("album_name", Query.title(track.album)));
            if (track.durationMs > 0)
                url.append('&').append(Query.params("duration", String.valueOf(Math.round(track.durationMs / 1000.0))));

            Http.Response response = http.get(url.toString());
            if (!response.ok()) continue;
            Json data = Json.parse(response.body);
            String synced = usable(data);
            if (synced != null)
                return new Lyrics.Result(Parsers.lrc(synced), "lrclib", data.get("songName").asText(track.title),
                        data.get("artistName").asText(track.artist));
        }

        List<Json> results = new ArrayList<Json>();
        int usableCount = 0;
        for (String[] variant : variants) {
            Http.Response response = http.get("https://lrclib.net/api/search?"
                    + Query.params("track_name", variant[0], "artist_name", variant[1]));
            if (!response.ok()) continue;
            Json found = Json.parse(response.body);
            if (found.isArray())
                for (Json item : found.items()) {
                    results.add(item);
                    if (usable(item) != null) usableCount++;
                }
            if (usableCount > 0) break;
        }

        if (usableCount == 0) {
            Http.Response retry = http.get("https://lrclib.net/api/search?" + Query.params("track_name", variants.get(0)[0]));
            if (retry.ok()) {
                Json found = Json.parse(retry.body);
                if (found.isArray())
                    for (Json item : found.items()) {
                        results.add(item);
                        if (usable(item) != null) usableCount++;
                    }
            }
        }

        if (results.isEmpty()) throw new IOException("search returned no results");
        if (usableCount == 0) throw new IOException("no synced lyrics for this track");

        double targetSeconds = track.durationMs > 0 ? track.durationMs / 1000.0 : 0;
        String targetArtist = Text.comparable(Query.artist(track.artist));
        Json best = null;
        double bestScore = 0;
        for (Json item : results) {
            if (usable(item) == null) continue;
            double delta = targetSeconds > 0 ? Math.abs(item.get("duration").asNumber(0) - targetSeconds) : 0;
            boolean artistMatch = !targetArtist.isEmpty()
                    && Text.comparable(item.get("artistName").asText("")).contains(targetArtist);
            double score = delta + (artistMatch ? 0 : 15);
            if (best == null || score < bestScore) {
                best = item;
                bestScore = score;
            }
        }

        return new Lyrics.Result(Parsers.lrc(usable(best)), "lrclib",
                best.get("songName").asText(track.title), best.get("artistName").asText(track.artist));
    }

    /**
     * LRCLIB holds test submissions like "[00:00.00]probe"; a real lyric has something to say after the
     * first line. Returns the text when usable, null when not.
     */
    private static String usable(Json item) {
        String raw = item.get("syncedLyrics").asText(item.get("synced_lyrics").asText(""));
        if (raw.isEmpty()) return null;
        return Parsers.lrc(raw).size() > 1 ? raw : null;
    }

    // --- LRCMux -----------------------------------------------------------------------------

    private Lyrics.Result lrcmux(Track track) throws IOException {
        IOException lastError = null;

        for (String[] variant : Query.variants(track)) {
            StringBuilder url = new StringBuilder("https://api.lrcmux.dev/get?")
                    .append(Query.params("artist", variant[1], "title", variant[0], "format", "json", "level", "word"));
            if (track.durationMs > 0)
                url.append('&').append(Query.params("duration", String.valueOf(Math.round(track.durationMs / 1000.0))));

            try {
                Json data = http.json(url.toString());
                // The answer is relayed from another site, so check it is actually the recording that was asked for.
                if (!Text.namesMatch(Text.comparable(data.path("track", "title").asText("")), Text.comparable(variant[0])))
                    throw new IOException("answered with a different recording");

                List<Line> lines = muxLines(data.get("lines"));
                if (lines.isEmpty()) throw new IOException("no synced lyrics for this track");
                return new Lyrics.Result(lines, "lrcmux", data.path("track", "title").asText(track.title),
                        data.path("track", "artist").asText(track.artist));
            } catch (IOException error) {
                lastError = error;
            }
        }

        throw lastError != null ? lastError : new IOException("no lyrics for this track");
    }

    /**
     * LRCMux relays other sites, so a response is either all milliseconds or all seconds. The last line of
     * a song never starts inside the first second, so a small trailing timestamp means the whole list is
     * seconds.
     */
    private static List<Line> muxLines(Json lines) {
        List<Json> source = lines.items();
        double lastRaw = source.isEmpty() ? 0 : source.get(source.size() - 1).get("start").asNumber(0);
        double scale = lastRaw <= 600 ? 1000 : 1;
        List<Line> out = new ArrayList<Line>();

        for (Json line : source) {
            double raw = line.get("start").asNumber(Double.NaN);
            String text = Text.cleanLyric(line.get("text").asText(""));
            if (Double.isNaN(raw) || text.isEmpty()) continue;
            long timeMs = Math.round(raw * scale);

            List<Line.Word> words = new ArrayList<Line.Word>();
            for (Json word : line.get("words").items()) {
                double wordRaw = word.get("start").asNumber(Double.NaN);
                String wordText = Text.clean(word.get("text").asText(""));
                if (Double.isNaN(wordRaw) || wordText.isEmpty()) continue;
                words.add(new Line.Word(Math.round(wordRaw * scale), wordText));
            }
            out.add(words.size() > 1 ? new Line(timeMs, text, words) : new Line(timeMs, text));
        }
        return out;
    }

    // --- AMLL TTML DB -----------------------------------------------------------------------

    private Lyrics.Result amll(Track track) throws IOException {
        for (String[] variant : Query.variants(track)) {
            Lyrics.Result found = amllVariant(track, variant[0], variant[1]);
            if (found != null) return found;
        }
        throw new IOException("no synced lyrics for this track");
    }

    private Lyrics.Result amllVariant(Track track, String title, String artist) throws IOException {
        Json search = http.json("https://api.amll.dev/v1/lyrics/search?" + Query.params("trackName", title, "artistName", artist));
        Json hit = Query.bestRecording(search.path("data", "items").items(), title, artist, track, new Query.Reader() {
            public String title(Json item) {
                return item.get("musicNames").at(0).asText("");
            }

            public String artist(Json item) {
                return item.get("artistNames").at(0).asText("");
            }

            public double seconds(Json item) {
                return 0;
            }
        });
        if (hit == null || hit.isNull()) return null;

        // The id stays text all the way through: AMLL ids are 64-bit and a double would silently round
        // anything above 2^53 into a 404 on the follow-up request.
        String id = hit.get("id").asText("");
        if (id.isEmpty()) return null;
        Json detail = http.json("https://api.amll.dev/v1/lyrics/get?id=" + Query.urlEncode(id));
        List<Line> lines = Parsers.ttml(detail.path("data", "lyrics").asText(""));
        if (lines.isEmpty()) return null;

        return new Lyrics.Result(lines, "amll", hit.get("musicNames").at(0).asText(track.title),
                hit.get("artistNames").at(0).asText(track.artist));
    }

    // --- lrc.red ----------------------------------------------------------------------------

    private Lyrics.Result lrcRed(Track track) throws IOException {
        for (String[] variant : Query.variants(track)) {
            Lyrics.Result found = lrcRedVariant(track, variant[0], variant[1]);
            if (found != null) return found;
        }
        throw new IOException("no synced lyrics for this track");
    }

    private Lyrics.Result lrcRedVariant(Track track, String title, String artist) throws IOException {
        Json search = http.json("https://lrc.red/search.json?" + Query.params("q", title + " " + artist));
        Json hit = Query.bestRecording(search.get("hits").items(), title, artist, track, new Query.Reader() {
            public String title(Json item) {
                return item.get("title").asText("");
            }

            public String artist(Json item) {
                return item.get("artist").asText("");
            }

            public double seconds(Json item) {
                return item.get("duration").asNumber(0);
            }
        });
        String isrc = hit == null ? "" : hit.get("isrc").asText("");
        if (isrc.isEmpty()) return null;

        Json song = http.json("https://lrc.red/s/" + Query.urlEncode(isrc) + ".song.json");
        List<Line> lines = structuredLines(song.path("lyrics", "lines"));
        if (lines.isEmpty()) lines = Parsers.ttml(song.get("source").asText(""));
        if (lines.isEmpty()) return null;

        return new Lyrics.Result(lines, "lrcRed", song.get("title").asText(track.title),
                song.get("artist").asText(track.artist));
    }

    /** lrc.red hands back its own line/word objects rather than TTML. */
    private static List<Line> structuredLines(Json lines) {
        List<Line> out = new ArrayList<Line>();
        for (Json line : lines.items()) {
            // parseTiming reads a missing timestamp as zero, so an untimed line has to be dropped before it
            // is given the top of the song.
            if (line.get("begin").isNull()) continue;
            Long timeMs = Parsers.parseTiming(line.get("begin").asText(null));
            String text = Text.cleanLyric(line.get("text").asText(""));
            if (timeMs == null) continue;

            List<Line.Word> words = new ArrayList<Line.Word>();
            for (Json word : flattenWordGroups(line.get("words"))) {
                if (word.get("begin").isNull()) continue;
                Long wordMs = Parsers.parseTiming(word.get("begin").asText(null));
                String wordText = Text.clean(word.get("text").asText(""));
                if (wordMs != null && !wordText.isEmpty()) words.add(new Line.Word(wordMs, wordText));
            }
            if (text.isEmpty()) continue;
            out.add(words.size() > 1 ? new Line(timeMs, text, words) : new Line(timeMs, text));
        }
        return out;
    }

    /** lrc.red may nest word runs a level deeper, so unwrap one level before reading them. */
    private static List<Json> flattenWordGroups(Json words) {
        List<Json> out = new ArrayList<Json>();
        for (Json word : words.items()) {
            if (word.isArray())
                for (Json inner : word.items()) out.add(inner);
            else
                out.add(word);
        }
        return out;
    }

    // --- RMM Revival ------------------------------------------------------------------------

    private Lyrics.Result rmm(Track track) throws IOException {
        for (String[] variant : Query.variants(track)) {
            try {
                Lyrics.Result found = rmmVariant(track, variant[0], variant[1]);
                if (found != null) return found;
            } catch (IOException ignored) {
                // try the next query
            }
        }
        throw new IOException("no synced lyrics for this track");
    }

    private Lyrics.Result rmmVariant(Track track, String title, String artist) throws IOException {
        String appleId = itunesCatalogId(title, artist, track);
        if (appleId.isEmpty()) throw new IOException("not in the Apple catalog");

        Json data = http.json("https://lyrics.rmmreviv.al/lyrics?id=" + Query.urlEncode(appleId));
        String ttml = data.get("ttml").asText("");
        List<Line> lines = ttml.contains("<tt") ? Parsers.ttml(ttml) : new ArrayList<Line>();
        if (lines.isEmpty()) lines = rmmSyncedLines(data);
        if (lines.isEmpty()) throw new IOException("no synced lyrics for this track");

        return new Lyrics.Result(lines, "rmm", data.get("name").asText(track.title),
                data.get("artist").asText(track.artist));
    }

    private static List<Line> rmmSyncedLines(Json data) {
        String raw = firstNonEmpty(data.get("syncedLyrics").asText(""), data.get("content").asText(""),
                data.get("lyrics").asText(""));
        if (raw.trim().isEmpty()) return new ArrayList<Line>();
        // Every line is prefixed with a [Singer 1] tag, which the LRC parser would read as a second timestamp.
        return Parsers.lrc(raw.replaceAll("\\]\\s*\\[[^\\]]{1,24}\\]", "]"));
    }

    private String itunesCatalogId(String title, String artist, Track track) throws IOException {
        Json data = http.json("https://itunes.apple.com/search?"
                + Query.params("term", artist + " " + title, "entity", "song", "limit", "12"));
        Json hit = Query.bestRecording(data.get("results").items(), title, artist, track, new Query.Reader() {
            public String title(Json item) {
                return item.get("trackName").asText("");
            }

            public String artist(Json item) {
                return item.get("artistName").asText("");
            }

            public double seconds(Json item) {
                return item.get("trackTimeMillis").asNumber(0) / 1000.0;
            }
        });
        return hit == null ? "" : hit.get("trackId").asText("");
    }

    // --- Unison -----------------------------------------------------------------------------

    private Lyrics.Result unison(Track track) throws IOException {
        IOException lastError = null;
        for (String[] variant : Query.variants(track)) {
            try {
                return unisonVariant(track, variant[0], variant[1]);
            } catch (IOException error) {
                lastError = error;
            }
        }
        throw lastError != null ? lastError : new IOException("no lyrics for this track");
    }

    private Lyrics.Result unisonVariant(Track track, String title, String artist) throws IOException {
        StringBuilder url = new StringBuilder("https://unison.boidu.dev/lyrics?")
                .append(Query.params("song", title, "artist", artist));
        if (!Query.title(track.album).isEmpty())
            url.append('&').append(Query.params("album", Query.title(track.album)));
        if (track.durationMs > 0)
            url.append('&').append(Query.params("duration", String.valueOf(Math.round(track.durationMs / 1000.0))));

        Json data = http.json(url.toString());
        String lyrics = data.path("data", "lyrics").asText("");
        if (!data.get("success").asBoolean(false) || lyrics.trim().isEmpty())
            throw new IOException("no lyrics for this track");

        List<Line> lines = Parsers.ttml(lyrics);
        if (lines.isEmpty()) throw new IOException("only unsynced lyrics available");
        return new Lyrics.Result(lines, "unison", data.path("data", "song").asText(track.title),
                data.path("data", "artist").asText(track.artist));
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values)
            if (value != null && !value.trim().isEmpty()) return value;
        return "";
    }
}

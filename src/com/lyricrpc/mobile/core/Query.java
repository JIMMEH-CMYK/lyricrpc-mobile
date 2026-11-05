package com.lyricrpc.mobile.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Turns a media-session title like "Title (Live at Wembley) [2011 Remaster]" into the strings the lyric
 * APIs actually index, and picks the best hit out of a search result. Mirrors the desktop plugin.
 */
public final class Query {
    private static final Pattern TITLE_JUNK = Pattern.compile(
            "\\s*[(\\[][^)\\]]*\\b(feat\\.?|ft\\.?|with|remaster(ed)?|version|edit|live|mono|stereo|deluxe|explicit|bonus)\\b[^)\\]]*[)\\]]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE_DASH_JUNK = Pattern.compile(
            "\\s+-\\s+.*\\b(remaster(ed)?|version|edit|live|mono|stereo|deluxe|explicit|bonus)\\b.*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE_FEAT_TAIL = Pattern.compile("\\s+(?:feat\\.?|ft\\.)\\s+[^,;]+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ARTIST_JUNK = Pattern.compile(
            "\\s*[(\\[][^)\\]]*\\b(feat\\.?|ft\\.?|with)\\b[^)\\]]*[)\\]]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ARTIST_FEAT_TAIL = Pattern.compile("\\s+(?:feat\\.?|ft\\.|with)\\s+.*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private Query() {
    }

    public static String title(String value) {
        String original = Text.clean(value);
        String cleaned = WHITESPACE.matcher(
                TITLE_FEAT_TAIL.matcher(
                        TITLE_DASH_JUNK.matcher(
                                TITLE_JUNK.matcher(original).replaceAll(" ")).replaceAll(" ")).replaceAll(" "))
                .replaceAll(" ").trim();
        return cleaned.isEmpty() ? original : cleaned;
    }

    public static String artist(String value) {
        String original = Text.clean(value);
        String cleaned = WHITESPACE.matcher(
                ARTIST_FEAT_TAIL.matcher(
                        ARTIST_JUNK.matcher(original).replaceAll(" ")).replaceAll(" "))
                .replaceAll(" ").trim();
        return cleaned.isEmpty() ? original : cleaned;
    }

    /**
     * The cleaned pair first, then the untouched one only when cleaning actually changed something - a
     * second identical request is a wasted round trip on a phone network.
     */
    public static List<String[]> variants(Track track) {
        String cleanedTitle = title(track.title);
        String cleanedArtist = artist(track.artist);
        String rawTitle = Text.clean(track.title);
        String rawArtist = Text.clean(track.artist);

        List<String[]> variants = new ArrayList<String[]>();
        variants.add(new String[] { cleanedTitle, cleanedArtist });
        if (!cleanedTitle.equals(rawTitle) || !cleanedArtist.equals(rawArtist))
            variants.add(new String[] { rawTitle, rawArtist });
        return variants;
    }

    /** Reads the three fields a hit from an arbitrary API exposes. */
    public interface Reader {
        String title(Json item);

        String artist(Json item);

        double seconds(Json item);
    }

    /**
     * Chooses the hit that is the same recording: the name has to match, the artist has to overlap, and of
     * the survivors the closest duration wins. Duration is the tiebreaker that matters because a remaster
     * and an edit share a title.
     */
    public static Json bestRecording(List<Json> hits, String wantedTitle, String wantedArtist, Track track, Reader reader) {
        String title = Text.comparable(wantedTitle);
        String artist = Text.comparable(wantedArtist);
        double seconds = track != null && track.durationMs > 0 ? track.durationMs / 1000.0 : 0;

        Json best = null;
        double bestScore = 0;
        for (Json item : hits) {
            if (item == null || item.isNull()) continue;
            String name = Text.comparable(reader.title(item));
            if (!Text.namesMatch(name, title)) continue;

            String who = Text.comparable(reader.artist(item));
            if (!artist.isEmpty() && !who.isEmpty() && !who.contains(artist) && !artist.contains(who)) continue;

            double timing = reader.seconds(item);
            double delta = seconds != 0 && timing != 0 ? Math.abs(timing - seconds) : 0;
            double score = delta + (name.equals(title) ? 0 : 4);
            if (best == null || score < bestScore) {
                best = item;
                bestScore = score;
            }
        }
        return best != null ? best : Json.empty();
    }

    public static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException impossible) {
            return value;
        }
    }

    /** Builds an application/x-www-form-urlencoded query, where a space is a plus, not %20. */
    public static String params(String... keyValues) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i + 1] == null) continue;
            if (out.length() > 0) out.append('&');
            out.append(urlEncode(keyValues[i])).append('=').append(urlEncode(keyValues[i + 1]));
        }
        return out.toString();
    }
}

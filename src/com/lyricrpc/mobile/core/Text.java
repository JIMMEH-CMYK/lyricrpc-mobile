package com.lyricrpc.mobile.core;

import java.util.Locale;
import java.util.regex.Pattern;

/** The text helpers the desktop plugin uses, ported so a line looks the same on both. */
public final class Text {
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern NOT_WORDY =
            Pattern.compile("[^\\p{L}\\p{N}]+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern ANNOTATION = Pattern.compile("\\s*\\[[^\\]]+\\]\\s*");
    private static final Pattern INSTRUMENTAL_BRACKET =
            Pattern.compile("\\s*\\([^)]*instrumental[^)]*\\)\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern INSTRUMENTAL_ONLY = Pattern.compile(
            "^[\\[(]?\\s*(instrumental|music only|no vocals)\\s*[\\])]?$",
            Pattern.CASE_INSENSITIVE);

    private Text() {
    }

    public static String clean(Object value) {
        if (value == null) return "";
        return SPACES.matcher(String.valueOf(value)).replaceAll(" ").trim();
    }

    /** Lowercased letters and numbers only, so "Feeling (Remastered) 2015" matches "feeling". */
    public static String comparable(Object value) {
        return NOT_WORDY.matcher(clean(value).toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }

    public static String firstArtist(String value) {
        String clean = clean(value);
        int comma = clean.indexOf(',');
        return comma < 0 ? clean : clean.substring(0, comma).trim();
    }

    /**
     * A lyric line as it should be read: bracketed annotations go, and an empty or purely instrumental
     * line becomes a music note instead of blank space, which is what the desktop plugin shows.
     */
    public static String cleanLyric(Object value) {
        String raw = clean(value);
        if (INSTRUMENTAL_ONLY.matcher(raw).matches()) return "♪";
        String text = INSTRUMENTAL_BRACKET.matcher(ANNOTATION.matcher(raw).replaceAll(" ")).replaceAll(" ").trim();
        return text.isEmpty() ? "♪" : text;
    }

    /**
     * "Bohemian Rhapsody" and "Bohemian Rhapsody 2011 Remaster" are the same recording, but a one-word
     * title is a whole title - "Money" must not match "Money Longer".
     */
    public static boolean namesMatch(String found, String wanted) {
        if (found == null || wanted == null) return false;
        if (found.isEmpty() || wanted.isEmpty()) return false;
        if (found.equals(wanted)) return true;

        String shorter = found.length() <= wanted.length() ? found : wanted;
        String longer = shorter.equals(found) ? wanted : found;
        return longer.startsWith(shorter + " ") && shorter.matches(".*\\s.*");
    }

    /** Whole-line HTML entity and tag cleanup for TTML fragments. */
    public static String decodeEntities(String fragment) {
        if (fragment == null) return "";
        return fragment
                .replaceAll("(?i)<br\\s*/?>", " ")
                .replaceAll("<[^>]*>", "")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&nbsp;", " ");
    }
}

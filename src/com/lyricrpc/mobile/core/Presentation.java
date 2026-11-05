package com.lyricrpc.mobile.core;

import java.util.List;

/**
 * Turns the line being sung into the string that ends up on screen, in the notification and on the
 * overlay. The order matters and is the desktop plugin's order: karaoke text, then case, then the
 * decoration around it, then the user's prefix and suffix, then bold last because bold is a font and
 * must not eat the glyphs.
 */
public final class Presentation {
    /**
     * Discord's own ceiling for a custom status. The counter in Discord's dialog counts the way JavaScript
     * does - one per UTF-16 unit - so an emoji and every letter of a bold line eat two of the 128. Fitting
     * to that same measure is what guarantees the paste is accepted rather than greyed out.
     */
    public static final int STATUS_LIMIT = 128;

    public static final class Style {
        public String decoration = "none";
        public String prefix = "";
        public String suffix = "";
        public String caseMode = "none";
        public boolean bold;
        public boolean karaoke;
        /** The user nudged the timing: a positive offset makes the lyric arrive later. */
        public long offsetMs;
    }

    private Presentation() {
    }

    /** The line being sung at this progress, honouring the offset, or null during an intro. */
    public static Line currentLine(List<Line> lines, long progressMs, Style style) {
        long shifted = progressMs - (style == null ? 0 : style.offsetMs);
        return Lyrics.lineAt(lines, shifted);
    }

    /** The line as it should be shown right now, or an empty string while nothing is being sung. */
    public static String at(List<Line> lines, long progressMs, Style style) {
        return line(currentLine(lines, progressMs, style), progressMs, style);
    }

    public static String line(Line line, long progressMs, Style style) {
        if (line == null || style == null) return "";
        long shifted = progressMs - style.offsetMs;
        return clean(apply(style.karaoke ? line.textAt(shifted) : line.text, style));
    }

    public static String apply(String text, Style style) {
        if (text == null || text.isEmpty()) return "";
        String cased = Decorations.applyCase(text, style.caseMode);
        String decorated = Decorations.wrap(style.decoration, cased);
        // Cleaned as one string, after joining: trimming the prefix on its own would throw away the
        // space in "♪ ".
        String wrapped = (style.prefix == null ? "" : style.prefix) + decorated + (style.suffix == null ? "" : style.suffix);
        // Bold last: it maps ASCII onto a mathematical font and never touches the decoration glyphs.
        return clean(style.bold ? Decorations.bold(wrapped) : wrapped);
    }

    /** The desktop does the same collapse: one space between words, nothing at the edges. */
    public static String clean(String value) {
        return Text.clean(value);
    }

    /**
     * Shortens a line so a status box that holds {@code maxUnits} UTF-16 units will take it. The cut backs
     * off to a whole character rather than leaving half of an emoji on the clipboard, prefers the last
     * space before it, and reserves one unit for the ellipsis so the result is always within the limit.
     */
    public static String fitTo(String value, int maxUnits) {
        if (value == null || value.isEmpty() || maxUnits <= 0) return "";
        if (value.length() <= maxUnits) return value;
        if (maxUnits == 1) return "…";

        int room = maxUnits - 1;
        int cut = room;
        if (Character.isHighSurrogate(value.charAt(cut - 1)) && Character.isLowSurrogate(value.charAt(cut))) cut--;
        int space = value.lastIndexOf(' ', cut - 1);
        // A word boundary only helps when it has not run half way back up the line.
        int end = space >= room / 2 ? space : cut;
        return clean(value.substring(0, end)) + "…";
    }
}

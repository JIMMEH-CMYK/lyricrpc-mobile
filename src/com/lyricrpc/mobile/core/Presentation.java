package com.lyricrpc.mobile.core;

import java.util.List;

/**
 * Turns the line being sung into the string that ends up on screen, in the notification and on the
 * overlay. The order matters and is the desktop plugin's order: karaoke text, then case, then the
 * decoration around it, then the user's prefix and suffix, then bold last because bold is a font and
 * must not eat the glyphs.
 */
public final class Presentation {
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
}

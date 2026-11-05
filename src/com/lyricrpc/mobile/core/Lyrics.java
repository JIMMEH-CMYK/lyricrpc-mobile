package com.lyricrpc.mobile.core;

import java.util.ArrayList;
import java.util.List;

/** The fetched lyric and the two lookups the notification and the overlay need. */
public final class Lyrics {
    public static final class Result {
        public final List<Line> lines;
        public final String source;
        public final String trackName;
        public final String artistName;

        public Result(List<Line> lines, String source, String trackName, String artistName) {
            this.lines = lines == null ? new ArrayList<Line>() : lines;
            this.source = source;
            this.trackName = trackName;
            this.artistName = artistName;
        }

        public boolean isEmpty() {
            return lines.isEmpty();
        }

        public String toLrc() {
            return Parsers.toLrc(lines);
        }
    }

    private Lyrics() {
    }

    /** The line being sung right now, or null while the song is still in its intro. */
    public static Line lineAt(List<Line> lines, long progressMs) {
        Line current = null;
        for (Line line : lines) {
            if (line.timeMs > progressMs) break;
            current = line;
        }
        return current;
    }

    public static int indexOf(List<Line> lines, Line line) {
        return line == null ? -1 : lines.indexOf(line);
    }

    /**
     * A line plus the words already reached, so the caller can render karaoke progress without knowing
     * whether the provider sent word timing at all.
     */
    public static String displayAt(List<Line> lines, long progressMs) {
        Line line = lineAt(lines, progressMs);
        return line == null ? "" : line.textAt(progressMs);
    }
}

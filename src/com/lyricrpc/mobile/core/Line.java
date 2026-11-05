package com.lyricrpc.mobile.core;

import java.util.ArrayList;
import java.util.List;

/** One timed lyric line, with word timing when the source carries it. */
public final class Line {
    public static final class Word {
        public final long timeMs;
        public final String text;

        public Word(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }
    }

    public final long timeMs;
    public final String text;
    public final List<Word> words;

    public Line(long timeMs, String text) {
        this(timeMs, text, null);
    }

    public Line(long timeMs, String text, List<Word> words) {
        this.timeMs = timeMs;
        this.text = text;
        this.words = words == null ? new ArrayList<Word>() : words;
    }

    /**
     * The line as it should read at this moment: the whole line, or only the words sung so far when the
     * source gives word timing. Karaoke-style fill makes the overlay readable without a second widget.
     */
    public String textAt(long progressMs) {
        if (words.size() < 2) return text;
        StringBuilder built = new StringBuilder();
        for (Word word : words) {
            if (word.timeMs > progressMs) break;
            if (built.length() > 0) built.append(' ');
            built.append(word.text);
        }
        return built.length() == 0 ? text : built.toString();
    }

    @Override
    public String toString() {
        return timeMs + "=" + text;
    }
}

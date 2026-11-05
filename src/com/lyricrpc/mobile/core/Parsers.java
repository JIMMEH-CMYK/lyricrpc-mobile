package com.lyricrpc.mobile.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** LRC and TTML readers, matching the desktop plugin line for line. */
public final class Parsers {
    private static final Pattern LRC_LINE = Pattern.compile(
            "^\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?\\]\\s*(.*)$");
    private static final Pattern LRC_WORD = Pattern.compile(
            "<(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?>");
    private static final Pattern TTML_PARAGRAPH = Pattern.compile(
            "<p\\b([^>]*)>([\\s\\S]*?)</p>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TTML_BACKGROUND = Pattern.compile(
            "<span\\b[^>]*ttm:role=\"x-bg\"[^>]*>[\\s\\S]*?</span>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TTML_TOKEN = Pattern.compile(
            "<span\\b([^>]*)>([\\s\\S]*?)</span>|<[^>]*>|([^<]+)");
    private static final Pattern BEGIN_ATTR = Pattern.compile("\\bbegin=\"([^\"]+)\"");
    /** `h:mm:ss.mmm`, `mm:ss.cs` or bare seconds - Apple dumps the first form, LRCLIB the last two. */
    private static final Pattern CLOCK = Pattern.compile("^(?:(\\d+):)?(\\d+):(\\d+(?:\\.\\d+)?)$");

    private Parsers() {
    }

    public static List<Line> lrc(String raw) {
        List<Line> out = new ArrayList<Line>();
        if (raw == null) return out;
        for (String row : raw.split("\\r?\\n")) {
            Matcher match = LRC_LINE.matcher(row.trim());
            if (!match.matches()) continue;
            long timeMs = lrcTimeMs(match.group(1), match.group(2), match.group(3));
            String body = match.group(4);
            List<Line.Word> words = enhancedWords(body);
            String text = Text.cleanLyric(words == null ? body : LRC_WORD.matcher(body).replaceAll(" "));
            out.add(words == null ? new Line(timeMs, text) : new Line(timeMs, text, words));
        }
        sort(out);
        return out;
    }

    /** Word timings inside an LRC line: `[00:12.40]<00:12.40>Hello <00:12.80>world`. */
    public static List<Line.Word> enhancedWords(String text) {
        if (text == null) return null;
        List<int[]> marks = new ArrayList<int[]>();
        List<String> parts = new ArrayList<String>();
        Matcher matcher = LRC_WORD.matcher(text);
        while (matcher.find()) {
            marks.add(new int[] { matcher.start(), matcher.end() });
            parts.add(matcher.group(1) + ":" + matcher.group(2) + ":" + (matcher.group(3) == null ? "" : matcher.group(3)));
        }
        if (marks.isEmpty()) return null;

        List<Line.Word> words = new ArrayList<Line.Word>();
        for (int i = 0; i < marks.size(); i++) {
            int start = marks.get(i)[1];
            int end = i + 1 < marks.size() ? marks.get(i + 1)[0] : text.length();
            String word = Text.clean(text.substring(start, Math.max(start, end)));
            if (word.isEmpty()) continue;
            String[] stamp = parts.get(i).split(":", 3);
            words.add(new Line.Word(lrcTimeMs(stamp[0], stamp[1], stamp[2]), word));
        }
        return words.size() > 1 ? words : null;
    }

    public static long lrcTimeMs(String minutes, String seconds, String fraction) {
        String millis = (fraction == null || fraction.isEmpty() ? "0" : fraction);
        StringBuilder padded = new StringBuilder(millis);
        while (padded.length() < 3) padded.append('0');
        long ms = Long.parseLong(padded.substring(0, 3));
        return Long.parseLong(minutes) * 60000L + Long.parseLong(seconds) * 1000L + ms;
    }

    /** TTML, the word-synced format Apple Music dumps use: `<p begin="0:12.40"><span begin=...>word</span></p>`. */
    public static List<Line> ttml(String text) {
        List<Line> out = new ArrayList<Line>();
        String ttml = text == null ? "" : text;
        if (!ttml.trim().startsWith("<") || !ttml.contains("<p")) return out;

        String withoutBackground = TTML_BACKGROUND.matcher(ttml).replaceAll(" ");
        Matcher paragraphs = TTML_PARAGRAPH.matcher(withoutBackground);
        while (paragraphs.find()) {
            Matcher begin = BEGIN_ATTR.matcher(paragraphs.group(1));
            if (!begin.find()) continue;
            Long timeMs = parseTiming(begin.group(1));
            String body = Text.cleanLyric(Text.decodeEntities(paragraphs.group(2)));
            if (timeMs == null || body.isEmpty()) continue;
            List<Line.Word> words = ttmlWords(paragraphs.group(2));
            out.add(words.isEmpty() ? new Line(timeMs, body) : new Line(timeMs, body, words));
        }
        sort(out);
        return out;
    }

    private static List<Line.Word> ttmlWords(String fragment) {
        List<Line.Word> words = new ArrayList<Line.Word>();
        StringBuilder buffer = new StringBuilder();
        Long wordTime = null;

        Matcher tokens = TTML_TOKEN.matcher(fragment.replaceFirst("(?i)<br\\s*/?>", " "));
        while (tokens.find()) {
            String plain = tokens.group(3);
            if (plain != null) {
                if (plain.trim().isEmpty() && !plain.isEmpty()) {
                    if (buffer.length() > 0 && wordTime != null) {
                        words.add(new Line.Word(wordTime, Text.clean(buffer.toString())));
                    }
                    buffer.setLength(0);
                    wordTime = null;
                } else if (!plain.trim().isEmpty()) {
                    if (wordTime == null) return Collections.emptyList();
                    buffer.append(plain);
                }
                continue;
            }

            String attributes = tokens.group(1);
            if (attributes == null) continue;
            if (attributes.contains("ttm:role=\"x-bg\"")) continue;
            Matcher begin = BEGIN_ATTR.matcher(attributes);
            if (!begin.find()) return Collections.emptyList();
            Long timeMs = parseTiming(begin.group(1));
            if (timeMs == null) return Collections.emptyList();

            String inner = Text.decodeEntities(tokens.group(2));
            if (inner.trim().isEmpty()) continue;
            if (wordTime == null) wordTime = timeMs;
            buffer.append(inner);
        }

        if (buffer.length() > 0 && wordTime != null) {
            words.add(new Line.Word(wordTime, Text.clean(buffer.toString())));
        }
        return words;
    }

    /** Seconds, or a `0:12.40` / `0:01:05.250` clock, into milliseconds. */
    public static Long parseTiming(String value) {
        String text = value == null ? "" : value.trim();
        Matcher clock = CLOCK.matcher(text);
        if (clock.matches()) {
            String hours = clock.group(1);
            double seconds = Double.parseDouble(clock.group(3));
            double minutes = Long.parseLong(clock.group(2));
            return Math.round(((hours == null ? 0 : Long.parseLong(hours)) * 3600
                    + minutes * 60 + seconds) * 1000d);
        }
        try {
            return Math.round(Double.parseDouble(text) * 1000d);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /** Writes lines back out as LRC, which is what the cache and the tests round-trip through. */
    public static String toLrc(List<Line> lines) {
        StringBuilder out = new StringBuilder();
        for (Line line : lines) {
            long minutes = line.timeMs / 60000;
            long seconds = (line.timeMs % 60000) / 1000;
            long hundredths = (line.timeMs % 1000) / 10;
            out.append(String.format("[%02d:%02d.%02d]%s%n", minutes, seconds, hundredths, line.text));
        }
        return out.toString();
    }

    private static void sort(List<Line> lines) {
        Collections.sort(lines, new Comparator<Line>() {
            @Override
            public int compare(Line a, Line b) {
                return Long.compare(a.timeMs, b.timeMs);
            }
        });
    }
}

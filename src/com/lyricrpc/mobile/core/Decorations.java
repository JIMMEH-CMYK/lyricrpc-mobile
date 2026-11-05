package com.lyricrpc.mobile.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The same decoration table the desktop plugin ships in 2.8.2, so a style picked on the phone reads
 * identically on Discord. One row is the pair of glyphs wrapped around the line; the panel, the lookup
 * and the persisted-value check all come from this list, which is why the desktop version could not
 * drift out of sync any more.
 */
public final class Decorations {
    public static final class Decor {
        public final String value;
        public final String group;
        public final String label;
        public final String before;
        public final String after;

        Decor(String value, String group, String label, String before, String after) {
            this.value = value;
            this.group = group;
            this.label = label;
            this.before = before;
            this.after = after;
        }

        public String wrap(String text) {
            if (text == null || text.isEmpty()) return text;
            if (before.isEmpty() && after.isEmpty()) return text;
            return before + text + after;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final List<Decor> ALL = new ArrayList<Decor>();
    private static final Map<String, Decor> BY_VALUE = new HashMap<String, Decor>();

    private static void add(String value, String group, String label, String before, String after) {
        Decor decor = new Decor(value, group, label, before, after);
        ALL.add(decor);
        BY_VALUE.put(value, decor);
    }

    static {
        add("none", "Plain", "None", "", "");

        add("note", "Notes", "♪ line", "♪ ", "");
        add("note_both", "Notes", "♪ line ♪", "♪ ", " ♪");
        add("note_pair", "Notes", "♪ line ♫", "♪ ", " ♫");
        add("beamed", "Notes", "♫ line", "♫ ", "");
        add("flourish", "Notes", "♬ line", "♬ ", "");
        add("headphones", "Notes", "\uD83C\uDFA7 line", "\uD83C\uDFA7 ", "");
        add("song_both", "Notes", "\uD83C\uDFB5 line \uD83C\uDFB5", "\uD83C\uDFB5 ", " \uD83C\uDFB5");

        add("quotes", "Frames", "“line”", "“", "”");
        add("quotes_single", "Frames", "‘line’", "‘", "’");
        add("quotes_straight", "Frames", "\"line\"", "\"", "\"");
        add("brackets", "Frames", "[line]", "[", "]");
        add("parentheses", "Frames", "(line)", "(", ")");
        add("braces", "Frames", "{line}", "{", "}");
        add("pipes", "Frames", "|line|", "|", "|");
        add("guillemets", "Frames", "« line »", "« ", " »");
        add("angle", "Frames", "‹line›", "‹", "›");
        add("corner", "Frames", "「line」", "「", "」");
        add("white_corner", "Frames", "『line』", "『", "』");
        add("black_bracket", "Frames", "【line】", "【", "】");

        add("bullet", "Marks", "• line", "• ", "");
        add("middot", "Marks", "· line", "· ", "");
        add("dash", "Marks", "— line", "— ", "");
        add("star", "Marks", "★ line", "★ ", "");
        add("star_open", "Marks", "☆ line", "☆ ", "");
        add("sparkle", "Marks", "✦ line", "✦ ", "");
        add("sparkle_soft", "Marks", "✧ line", "✧ ", "");
        add("arrow", "Marks", "▸ line", "▸ ", "");
        add("arrow_bold", "Marks", "➜ line", "➜ ", "");
        add("chevron", "Marks", "» line", "» ", "");
        add("tilde", "Marks", "~ line", "~ ", "");
        add("slash", "Marks", "// line", "// ", "");
        add("crown", "Marks", "♛ line", "♛ ", "");
        add("flag", "Marks", "⚑ line", "⚑ ", "");

        add("stars", "Paired", "✦ line ✦", "✦ ", " ✦");
        add("sparkle_soft_both", "Paired", "✧ line ✧", "✧ ", " ✧");
        add("star_both", "Paired", "★ line ★", "★ ", " ★");
        add("wave_both", "Paired", "~ line ~", "~ ", " ~");
        add("dot_both", "Paired", "· line ·", "· ", " ·");
        add("dash_both", "Paired", "— line —", "— ", " —");
    }

    private Decorations() {
    }

    public static List<Decor> all() {
        return Collections.unmodifiableList(ALL);
    }

    /** The presets in panel order: group headings appear once, in declaration order. */
    public static List<String> groups() {
        List<String> groups = new ArrayList<String>();
        for (Decor decor : ALL) {
            if (!groups.contains(decor.group)) groups.add(decor.group);
        }
        return groups;
    }

    public static List<Decor> inGroup(String group) {
        List<Decor> out = new ArrayList<Decor>();
        for (Decor decor : ALL) {
            if (decor.group.equals(group)) out.add(decor);
        }
        return out;
    }

    public static boolean isKnown(String value) {
        return value != null && BY_VALUE.containsKey(value);
    }

    public static String normalize(String value) {
        return isKnown(value) ? value : "none";
    }

    public static String wrap(String value, String text) {
        Decor decor = BY_VALUE.get(normalize(value));
        return decor.wrap(text);
    }

    /**
     * Discord bold, done with mathematical alphanumeric code points because a status line has no
     * styling of its own. Only ASCII letters and digits have bold forms, so everything else passes
     * through untouched - which is why the decoration glyphs survive a bold line.
     */
    public static String bold(String text) {
        if (text == null || text.isEmpty()) return text;
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            int code = ch;
            if (code >= 'A' && code <= 'Z') {
                out.appendCodePoint(0x1D5D4 + code - 'A');
            } else if (code >= 'a' && code <= 'z') {
                out.appendCodePoint(0x1D5EE + code - 'a');
            } else if (code >= '0' && code <= '9') {
                out.appendCodePoint(0x1D7EC + code - '0');
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }

    /** Case transform from the desktop "Lyric case" setting, upper/lower/sentence. */
    public static String applyCase(String text, String mode) {
        if (text == null || text.isEmpty()) return text;
        if ("upper".equals(mode)) return text.toUpperCase(Locale.ROOT);
        if ("lower".equals(mode)) return text.toLowerCase(Locale.ROOT);
        if ("sentence".equals(mode)) {
            StringBuilder out = new StringBuilder(text.toLowerCase(Locale.ROOT));
            for (int i = 0; i < out.length(); i++) {
                char ch = out.charAt(i);
                if (Character.isLetter(ch)) {
                    out.setCharAt(i, Character.toUpperCase(ch));
                    break;
                }
            }
            return out.toString();
        }
        return text;
    }

    /** Groups sorted the way the panel lists them, for tests that assert the table did not shrink. */
    public static List<String> sortedValues() {
        List<String> values = new ArrayList<String>();
        for (Decor decor : ALL) values.add(decor.value);
        Collections.sort(values, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return a.compareTo(b);
            }
        });
        return values;
    }
}

package com.lyricrpc.mobile.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Runs the lyric core on a plain JVM - no Android, no emulator. The offline half asserts the parsing and
 * matching rules; the live half asks each real provider for a known song, so a broken URL or a changed
 * response shape is caught here rather than on a phone. Run with --offline to skip the network.
 */
public final class CoreTest {
    private static int passed;
    private static final List<String> failures = new ArrayList<String>();

    public static void main(String[] args) throws Exception {
        boolean offline = args.length > 0 && "--offline".equals(args[0]);

        json();
        parsers();
        query();
        decorations();
        presentation();

        if (!offline) live();

        System.out.println();
        for (String failure : failures) System.out.println("FAIL " + failure);
        System.out.println((failures.isEmpty() ? "ALL PASS - " : failures.size() + " FAILED - ")
                + passed + " checks passed");
        if (!failures.isEmpty()) System.exit(1);
    }

    // --- checks -----------------------------------------------------------------------------

    private static void check(String label, boolean condition, String detail) {
        if (condition) {
            passed++;
            System.out.println("ok   " + label);
        } else {
            failures.add(label + (detail == null || detail.isEmpty() ? "" : " (" + detail + ")"));
            System.out.println("FAIL " + label + (detail == null || detail.isEmpty() ? "" : " - " + detail));
        }
    }

    private static void equal(String label, Object expected, Object actual) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        check(label, same, same ? "" : "expected <" + expected + "> got <" + actual + ">");
    }

    private static void json() {
        Json data = Json.parse("{\"a\":[1,2.5,true,null,\"x\\u00e9\"],\"id\":7317581063811421999,\"n\":{\"m\":\"p\"}}");
        equal("json: nested array size", 5, data.get("a").size());
        equal("json: unicode escape", "xé", data.get("a").at(4).asText(""));
        equal("json: boolean", true, data.get("a").at(2).asBoolean(false));
        equal("json: null element is null", true, data.get("a").at(3).isNull());
        equal("json: missing key is null", true, data.get("nothing").isNull());
        equal("json: path walks objects", "p", data.path("n", "m").asText(""));
        // AMLL ids are 64-bit: read as a double they round, and the rounded id 404s on the follow-up call.
        equal("json: 64-bit id keeps every digit", "7317581063811421999", data.get("id").asText(""));
        equal("json: 64-bit id as long", 7317581063811421999L, data.get("id").asLong(0));
        equal("json: number as text survives", "1", data.get("a").at(0).asText(""));
    }

    private static void parsers() {
        List<Line> lrc = Parsers.lrc("[00:12.00]First line\n[01:05.25]Second line\n[02:00.00]");
        equal("lrc: line count", 3, lrc.size());
        equal("lrc: mm:ss.cs time", 65250L, lrc.get(1).timeMs);
        equal("lrc: text", "Second line", lrc.get(1).text);
        equal("lrc: an empty body becomes a note", "♪", lrc.get(2).text);

        List<Line> timed = Parsers.lrc("[00:10.00]la la [00:20.00]de de");
        check("lrc: a line with two timestamps keeps its own text", !timed.isEmpty(), "");

        List<Line> words = Parsers.lrc("[00:10.00]<00:10.10>hello <00:10.60>world");
        equal("enhanced lrc: words parsed", 2, words.get(0).words.size());
        equal("enhanced lrc: word time", 10600L, words.get(0).words.get(1).timeMs);
        equal("enhanced lrc: karaoke text", "hello",
                Parsers.lrc("[00:10.00]<00:10.10>hello <00:10.60>world").get(0).textAt(10300));

        List<Line> ttml = Parsers.ttml("<tt><body>"
                + "<div><p begin=\"0:10.00\" end=\"0:12.00\">Line one</p>"
                + "<p begin=\"0:01:05.250\">Line two</p></div></body></tt>");
        equal("ttml: line count", 2, ttml.size());
        equal("ttml: h:mm:ss.mmm", 65250L, ttml.get(1).timeMs);
        equal("ttml: text", "Line one", ttml.get(0).text);

        // The desktop drops background vocals at span level, so the port must do exactly that.
        List<Line> background = Parsers.ttml("<tt><body><p begin=\"0:10.00\">Lead "
                + "<span ttm:role=\"x-bg\" begin=\"0:10.50\">(ooh)</span></p></body></tt>");
        equal("ttml: background vocals dropped", 1, background.size());
        equal("ttml: only the lead line survives", "Lead", background.get(0).text);

        List<Line> entities = Parsers.ttml("<tt><body><p begin=\"0:05.00\">rock &amp; roll &lt;loud&gt;</p></body></tt>");
        equal("ttml: entities decoded", "rock & roll <loud>", entities.get(0).text);

        equal("lrc writer: round trip", "[00:12.00]First line",
                Parsers.toLrc(Arrays.asList(new Line(12000, "First line"))).trim());

        equal("timing: plain seconds", Long.valueOf(4500L), Parsers.parseTiming("4.5"));
        equal("timing: colon form", Long.valueOf(75400L), Parsers.parseTiming("1:15.4"));
        equal("timing: hours", Long.valueOf(3705250L), Parsers.parseTiming("1:01:45.250"));
        equal("timing: zero-padded hours", Long.valueOf(65250L), Parsers.parseTiming("00:01:05.250"));
        equal("timing: garbage", null, Parsers.parseTiming("not a time"));

        Track a = new Track();
        a.title = "Hello";
        a.artist = "Adele";
        a.durationMs = 200000;
        Track b = new Track();
        b.title = "Hello ";
        b.artist = "Adele, XL";
        b.durationMs = 202000;
        check("track: the same song across two sources", a.isSameSongAs(b), "");
        Track c = new Track();
        c.title = "Rolling in the Deep";
        c.artist = "Adele";
        c.durationMs = 200000;
        check("track: a different title is a different song", !a.isSameSongAs(c), "");
    }

    private static void query() {
        equal("query: strips a remaster bracket", "Bohemian Rhapsody",
                Query.title("Bohemian Rhapsody - 2011 Remaster"));
        equal("query: strips a featured artist", "Slow Dancing",
                Query.title("Slow Dancing (feat. Jeremih)"));
        equal("query: leaves a clean title alone", "Clair de Lune", Query.title("Clair de Lune"));
        equal("query: artist feat tail", "Drake", Query.artist("Drake feat. Someone"));

        List<String[]> variants = Query.variants(track("Any Name", "Someone"));
        equal("query: one variant when nothing to clean", 1, variants.size());
        List<String[]> two = Query.variants(track("Any Name (Live)", "Someone"));
        equal("query: two variants when cleaning changed the title", 2, two.size());
        equal("query: the raw title is still available", "Any Name (Live)", two.get(1)[0]);

        check("names: exact", Text.namesMatch("money", "money"), "");
        check("names: single word is a whole title", !Text.namesMatch("money", "money longer"), "");
        check("names: multi-word prefix", Text.namesMatch("bohemian rhapsody", "bohemian rhapsody 2011 remaster"), "");
        check("names: never match on empty", !Text.namesMatch("", "anything"), "");

        List<Json> hits = Json.parse("[{\"trackName\":\"Song B\",\"artistName\":\"X\",\"trackTimeMillis\":300000},"
                + "{\"trackName\":\"Song A\",\"artistName\":\"X\",\"trackTimeMillis\":180000},"
                + "{\"trackName\":\"Song A\",\"artistName\":\"Y\",\"trackTimeMillis\":200000}]")
                .items();
        Json best = Query.bestRecording(hits, "Song A", "X", track("Song A", "X", 200000), new Query.Reader() {
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
        equal("best recording: duration wins over the wrong artist", 180000.0, best == null ? -1 : best.get("trackTimeMillis").asNumber(-1));

        Json none = Query.bestRecording(Json.parse("[{\"trackName\":\"Completely Other\",\"artistName\":\"Z\"}]").items(),
                "Song A", "X", track("Song A", "X"), new Query.Reader() {
                    public String title(Json item) {
                        return item.get("trackName").asText("");
                    }

                    public String artist(Json item) {
                        return item.get("artistName").asText("");
                    }

                    public double seconds(Json item) {
                        return 0;
                    }
                });
        check("best recording: rejects a different song", none.isNull(), "");

        equal("params: spaces become plus", "track_name=Slow+Dancing&artist_name=Adele",
                Query.params("track_name", "Slow Dancing", "artist_name", "Adele"));
        equal("params: symbols escaped", "q=a%26b", Query.params("q", "a&b"));
    }

    private static void decorations() {
        equal("decorations: count matches desktop", 40, Decorations.all().size());
        check("decorations: every value known", Decorations.isKnown("guillemets") && Decorations.isKnown("none"), "");
        check("decorations: unknown value normalises to none", "none".equals(Decorations.normalize("nonsense")), "");
        equal("decorations: wraps both sides", "\u00ab line \u00bb", Decorations.wrap("guillemets", "line"));
        equal("decorations: none leaves the line alone", "line", Decorations.wrap("none", "line"));
        equal("decorations: bold is Discord bold", "\uD835\uDDEE\uD835\uDDEF", Decorations.bold("ab"));
        equal("decorations: bold leaves a glyph alone", "♪", Decorations.bold("♪"));
        equal("decorations: upper case", "LINE", Decorations.applyCase("line", "upper"));
        equal("decorations: sentence case", "Line here", Decorations.applyCase("LINE HERE", "sentence"));
        List<String> groups = Decorations.groups();
        check("decorations: grouped for the settings grid", groups.size() >= 4, groups.toString());
        check("decorations: values are unique",
                new java.util.HashSet<String>(Decorations.sortedValues()).size() == Decorations.all().size(), "");
    }

    private static void presentation() {
        List<Line> lines = Parsers.lrc("[00:10.00]<00:10.00>Hel <00:10.50>lo\n[00:20.00]Second line");
        Presentation.Style style = new Presentation.Style();

        equal("presentation: nothing before the first line", "", Presentation.at(lines, 5000, style));
        equal("presentation: plain line", "Second line", Presentation.at(lines, 25000, style));

        style.decoration = "note";
        equal("presentation: decoration wraps", "♪ Second line", Presentation.at(lines, 25000, style));
        style.prefix = ">>";
        style.suffix = "<<";
        equal("presentation: prefix and suffix survive the trim", ">>♪ Second line<<",
                Presentation.at(lines, 25000, style));

        style = new Presentation.Style();
        style.decoration = "note_pair";
        style.prefix = "♪ ";
        equal("presentation: a spaced prefix keeps its space", "♪ ♪ Second line ♫",
                Presentation.at(lines, 25000, style));

        style = new Presentation.Style();
        style.karaoke = true;
        equal("presentation: karaoke fills word by word", "Hel", Presentation.at(lines, 10200, style));
        equal("presentation: karaoke completes", "Hel lo", Presentation.at(lines, 10700, style));
        style.karaoke = false;
        equal("presentation: without karaoke the whole line shows at once", "Hel lo", Presentation.at(lines, 10200, style));

        style = new Presentation.Style();
        equal("presentation: no offset is on time", "Second line", Presentation.at(lines, 22000, style));
        style.offsetMs = 5000;
        equal("presentation: a positive offset delays the lyric", "Hel lo", Presentation.at(lines, 22000, style));
        equal("presentation: and still catches up", "Second line", Presentation.at(lines, 26000, style));

        style = new Presentation.Style();
        style.decoration = "guillemets";
        style.bold = true;
        String bolded = Presentation.at(lines, 25000, style);
        check("presentation: bold keeps the decoration glyphs",
                bolded.contains("\u00ab") && bolded.contains("\u00bb"), bolded);
        check("presentation: bold re-fonts the letters",
                !bolded.contains("Second"), bolded);
    }

    private static void live() {
        Providers providers = new Providers(new Http(9000));

        // A song every community source indexes, so a null answer means the code broke, not the catalogue.
        Track song = track("Blinding Lights", "The Weeknd", 200000);
        int withLyrics = 0;

        for (Providers.Entry entry : Providers.ALL) {
            try {
                Lyrics.Result result = providers.fetch(entry.key, song);
                boolean ok = result != null && result.lines.size() > 3;
                check("live: " + entry.label + " either answers or refuses cleanly", ok,
                        result == null ? "null" : result.lines.size() + " lines, source " + result.source);
                if (ok) {
                    withLyrics++;
                    long middle = result.lines.get(result.lines.size() / 2).timeMs;
                    Line line = Lyrics.lineAt(result.lines, middle + 500);
                    check("live: " + entry.label + " line lookup follows the timing",
                            line != null && !line.text.isEmpty(), "");
                    System.out.println("     " + entry.key + " -> \"" + line.text + "\" [" + line.timeMs + "ms]");
                }
            } catch (IOException noLyricFromThisSource) {
                // A provider that has nothing, or that has stopped serving anything at all, is an answer:
                // the chain moves on. Unison currently returns NOT_FOUND for every track.
                check("live: " + entry.label + " either answers or refuses cleanly", true, "");
                System.out.println("     " + entry.key + " -> refused: " + noLyricFromThisSource.getMessage());
            } catch (Exception broke) {
                // Anything else is our bug: a bad URL, a shape we cannot parse, a null we did not expect.
                check("live: " + entry.label + " either answers or refuses cleanly", false, broke.toString());
            }
        }

        check("live: most sources still have the song", withLyrics >= 4, withLyrics + " of " + Providers.ALL.length + " returned lyrics");

        Lyrics.Result chain = null;
        try {
            chain = providers.fetch(song, Providers.defaultEnabled());
            check("live: the whole chain resolves a song", chain != null && !chain.isEmpty(), "");
            System.out.println("     chain -> " + (chain == null ? "nothing" : chain.source + ", " + chain.lines.size() + " lines"));
        } catch (Exception error) {
            check("live: the whole chain resolves a song", false, error.toString());
        }

        // An impossible track must fail with a reason, never hang or hand back an empty lyric.
        try {
            providers.fetch(track("zzqx not a real song 98765", "zzqx not an artist", 123000), Providers.defaultEnabled());
            check("live: an unknown song is refused", false, "a lyric came back for a made-up track");
        } catch (Exception error) {
            String text = String.valueOf(error.getMessage());
            check("live: an unknown song is refused", !text.contains("null"), text);
            System.out.println("     refusal -> " + text);
        }
    }

    private static Track track(String title, String artist) {
        return track(title, artist, 0);
    }

    private static Track track(String title, String artist, long durationMs) {
        Track track = new Track();
        track.title = title;
        track.artist = artist;
        track.durationMs = durationMs;
        track.playing = true;
        return track;
    }
}

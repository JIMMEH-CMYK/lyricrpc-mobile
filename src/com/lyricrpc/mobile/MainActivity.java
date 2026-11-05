package com.lyricrpc.mobile;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.lyricrpc.mobile.core.Decorations;
import com.lyricrpc.mobile.core.Line;
import com.lyricrpc.mobile.core.Lyrics;
import com.lyricrpc.mobile.core.Presentation;
import com.lyricrpc.mobile.core.Providers;
import com.lyricrpc.mobile.core.Track;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The settings screen, split into tabs: Now, Style, Overlay, Sources and Settings. Every tab owns a colour
 * from {@code res/values/colors.xml}, which is the same palette the launcher icon is painted with, so the
 * app and its icon read as one thing.
 *
 * <p>Built in code, because a Gradle-free APK is easier to keep honest without layout XML.</p>
 */
public final class MainActivity extends Activity {
    private static final int REQUEST_NOTIFICATIONS = 71;
    private static final int OVERLAY_MIN_SP = 11;
    private static final int OVERLAY_MAX_SP = 34;
    /** The dimmest the overlay will go; anything lower is invisible against a bright video. */
    private static final int ALPHA_MIN = 25;
    private static final String REPO_URL = "https://github.com/JIMMEH-CMYK/lyricrpc-mobile";

    private static final String[] TABS = { "Now", "Style", "Overlay", "Sources", "Settings" };
    private static final int[] TAB_COLORS = { R.color.tab_now, R.color.tab_style, R.color.tab_overlay,
            R.color.tab_sources, R.color.tab_settings };

    private final Handler main = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private MediaSource source;
    private Ui ui;
    /** Resolved once per build: the phone's accent on Android 12 and later, the brand green otherwise. */
    private int accent;
    private int[] tints;
    private int tab;
    /** Set when the settings screen itself threw: the crash screen that replaced it must be left alone. */
    private boolean brokenScreen;

    // Rebuilt with every tab, so each one is checked before it is written to.
    private TextView statusLine;
    private TextView accessLine;
    private Button startStop;
    private TextView preview;
    private TextView offsetLabel;
    private TextView sizeReadout;
    private TextView alphaReadout;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            drawState();
            main.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Crash.showPending(this);
        prefs = new Prefs(this);
        source = new MediaSource(this);
        try {
            setContentView(build());
        } catch (RuntimeException brokenScreen) {
            // Whatever it was, the screen that shows the stack trace is the one screen that must not throw.
            Crash.record(this, brokenScreen);
            setContentView(Crash.fallback(this, brokenScreen));
            this.brokenScreen = true;
            return;
        }
        drawState();
        askForNotificationsOnce();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (brokenScreen) return;
        // Coming back from a system settings page can change what the permission pills say.
        rebuild();
        main.postDelayed(refresh, 200);
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(refresh);
        super.onPause();
    }

    /**
     * Colours are baked into the views at build time, so a change of accent - or of tab - means building
     * them again. A settings screen losing its scroll position is the cheaper half of that trade: a tab
     * swap is a deliberate act, and the new tab starts at the top anyway.
     */
    private void rebuild() {
        main.removeCallbacks(refresh);
        setContentView(build());
        drawState();
    }

    private void selectTab(int index) {
        if (index == tab) return;
        tab = index;
        rebuild();
    }

    // --- layout -----------------------------------------------------------------------------

    private View build() {
        ui = new Ui(this);
        accent = Theme.accent(this, prefs);
        tints = new int[TAB_COLORS.length];
        for (int i = 0; i < TAB_COLORS.length; i++) tints[i] = ui.color(TAB_COLORS[i]);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = ui.dp(14);
        root.setPadding(pad, ui.dp(10), pad, pad);

        TextView title = ui.label("LyricRPC", 22, ui.color(R.color.ink), true);
        title.setLetterSpacing(0.03f);
        root.addView(title);

        statusLine = ui.label("", 14, ui.color(R.color.ink), false);
        root.addView(statusLine);
        accessLine = ui.note("");
        root.addView(accessLine);

        root.addView(grow(ui.button("Start lyrics", accent, Ui.contrast(accent), new Ui.Tap() {
            public void run() {
                toggleService();
            }
        })));

        root.addView(ui.tabStrip(TABS, tints, tab, new Ui.Pick() {
            public void pick(int index) {
                selectTab(index);
            }
        }));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0);
        scroll.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams fill = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, fill);

        showTab(body);
        return ui.backdrop(root);
    }

    private void showTab(LinearLayout body) {
        // Only the tabs that own them set these; a stale reference would keep the refresh writing to a
        // view that is no longer on screen.
        preview = null;
        offsetLabel = null;
        sizeReadout = null;
        alphaReadout = null;
        int tint = tints[tab];
        if (tab == 0) nowTab(body, tint);
        else if (tab == 1) styleTab(body, tint);
        else if (tab == 2) overlayTab(body, tint);
        else if (tab == 3) sourcesTab(body, tint);
        else settingsTab(body, tint);
    }

    /** A heading in the tab's colour above a card of the same colour, then breathing room. */
    private void section(LinearLayout column, String name, int tint, View... rows) {
        View[] all = new View[rows.length + 1];
        all[0] = ui.heading(name, tint);
        System.arraycopy(rows, 0, all, 1, rows.length);
        column.addView(ui.card(tint, all));
        column.addView(ui.gap());
    }

    // --- Now ---------------------------------------------------------------------------------

    private void nowTab(LinearLayout column, int tint) {
        boolean listening = source.hasAccess();
        boolean drawing = Overlay.canDraw(this);
        column.addView(ui.gap());
        section(column, "Permissions", tint,
                ui.row(ui.pill("notification access " + yesNo(listening), listening),
                        ui.pill("draw over apps " + yesNo(drawing), drawing)),
                ui.note("Notification access is how the app reads what Spotify is playing. Draw over apps is "
                        + "only needed for the floating lyric - the notification works without it."),
                ui.row(
                        ui.button("Notification access", ui.color(R.color.card_soft),
                                ui.color(R.color.ink), new Ui.Tap() {
                                    public void run() {
                                        open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                                    }
                                }),
                        ui.button("Draw over apps", ui.color(R.color.card_soft),
                                ui.color(R.color.ink), new Ui.Tap() {
                                    public void run() {
                                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                Uri.parse("package:" + getPackageName())));
                                    }
                                })));

        offsetLabel = ui.note("");
        section(column, "Timing", tint, offsetLabel,
                ui.row(
                        ui.button("-50 ms", ui.color(R.color.card_soft), ui.color(R.color.ink), shift(-50)),
                        ui.button("+50 ms", ui.color(R.color.card_soft), ui.color(R.color.ink), shift(50)),
                        ui.button("Reset", ui.color(R.color.card_soft), ui.color(R.color.ink),
                                new Ui.Tap() {
                                    public void run() {
                                        prefs.resetOffset();
                                        restyle();
                                    }
                                })),
                ui.note("A lyric that lands before it is sung wants a negative offset."));

        section(column, "To Discord", tint,
                ui.row(
                        ui.button("Copy line", tint, Ui.contrast(tint), new Ui.Tap() {
                            public void run() {
                                handoff(currentText(), false);
                            }
                        }),
                        ui.button("Copy and open", ui.color(R.color.card_soft), ui.color(R.color.ink),
                                new Ui.Tap() {
                                    public void run() {
                                        handoff(currentText(), true);
                                    }
                                }),
                        ui.button("Whole song", ui.color(R.color.card_soft), ui.color(R.color.ink),
                                new Ui.Tap() {
                                    public void run() {
                                        copySong();
                                    }
                                })),
                ui.note("Discord lets no other app write a status, so this fills the clipboard and hands "
                        + "you the app: tap your avatar, Set Status, paste. The tap that pastes stays yours."));
    }

    // --- Style -------------------------------------------------------------------------------

    private void styleTab(LinearLayout column, int tint) {
        String chosen = prefs.decoration();
        List<View> decorRows = new ArrayList<View>();
        for (final String group : Decorations.groups()) {
            final List<Decorations.Decor> decors = Decorations.inGroup(group);
            List<String> labels = new ArrayList<String>();
            for (Decorations.Decor decor : decors) labels.add(decor.label);
            decorRows.add(ui.label(group, 11, ui.color(R.color.ink_dim), true));
            int selected = indexOfValue(decors, chosen);
            decorRows.add(ui.chipRow(labels, selected, tint, new Ui.Pick() {
                public void pick(int index) {
                    prefs.setDecoration(decors.get(index).value);
                    restyle();
                    rebuild();
                }
            }));
        }
        column.addView(ui.gap());
        section(column, "Decoration", tint, decorRows.toArray(new View[decorRows.size()]));

        final List<String> modes = Arrays.asList("none", "upper", "lower", "sentence");
        section(column, "Letters", tint,
                ui.chipRow(Arrays.asList("as written", "UPPER", "lower", "Sentence"),
                        modes.indexOf(prefs.caseMode()), tint, new Ui.Pick() {
                            public void pick(int index) {
                                prefs.setCaseMode(modes.get(index));
                                restyle();
                                rebuild();
                            }
                        }),
                ui.note("Bold rewrites the letters into a mathematical font, so it works in a status line "
                        + "where there is no real formatting. Karaoke shows the line being sung inside the verse."),
                ui.toggle("Bold letters", prefs.bold(), tint, new Ui.Toggle() {
                    public void set(boolean on) {
                        prefs.setBold(on);
                        restyle();
                    }
                }),
                ui.toggle("Karaoke timing", prefs.karaoke(), tint, new Ui.Toggle() {
                    public void set(boolean on) {
                        prefs.setKaraoke(on);
                        restyle();
                    }
                }));

        section(column, "Wrap the line", tint,
                ui.row(
                        ui.field(prefs.prefix(), "prefix", new Ui.Typed() {
                            public void to(String value) {
                                prefs.setAffixes(value, prefs.suffix());
                                restyle();
                            }
                        }),
                        ui.field(prefs.suffix(), "suffix", new Ui.Typed() {
                            public void to(String value) {
                                prefs.setAffixes(prefs.prefix(), value);
                                restyle();
                            }
                        })),
                ui.note("Goes around the whole line, decoration and all - \"🎵 \" in front, \" ✦\" behind."));

        preview = ui.label("", 17, ui.color(R.color.ink), false);
        preview.setGravity(Gravity.CENTER);
        preview.setTextIsSelectable(true);
        preview.setPadding(ui.dp(12), ui.dp(18), ui.dp(12), ui.dp(18));
        section(column, "Preview", tint, ui.inset(ui.color(R.color.card_soft), preview));
    }

    private static int indexOfValue(List<Decorations.Decor> decors, String value) {
        for (int i = 0; i < decors.size(); i++) if (decors.get(i).value.equals(value)) return i;
        return -1;
    }

    // --- Overlay -----------------------------------------------------------------------------

    private void overlayTab(LinearLayout column, int tint) {
        sizeReadout = ui.note(spLabel(prefs.overlayTextSp()));
        alphaReadout = ui.note(percentLabel(prefs.overlayAlpha()));
        column.addView(ui.gap());
        section(column, "The floating lyric", tint,
                ui.toggle("Show it on screen", prefs.overlay(), tint, new Ui.Toggle() {
                    public void set(boolean on) {
                        prefs.setOverlay(on);
                        if (on && !Overlay.canDraw(MainActivity.this))
                            toast("Allow \"draw over apps\" for the floating lyric");
                        service(LyricService.ACTION_RESTYLE);
                    }
                }),
                ui.slider("Size", (int) (prefs.overlayTextSp() - OVERLAY_MIN_SP),
                        OVERLAY_MAX_SP - OVERLAY_MIN_SP, tint, sizeReadout, new Ui.Slide() {
                            public void to(int value) {
                                float sp = OVERLAY_MIN_SP + value;
                                prefs.setOverlayTextSp(sp);
                                if (sizeReadout != null) sizeReadout.setText(spLabel(sp));
                                service(LyricService.ACTION_RESTYLE);
                            }
                        }),
                ui.slider("Opacity", alphaProgress(prefs.overlayAlpha()), 100 - ALPHA_MIN, tint, alphaReadout,
                        new Ui.Slide() {
                            public void to(int value) {
                                float alpha = (ALPHA_MIN + value) / 100f;
                                prefs.setOverlayAlpha(alpha);
                                if (alphaReadout != null) alphaReadout.setText(percentLabel(alpha));
                                service(LyricService.ACTION_RESTYLE);
                            }
                        }),
                ui.toggle("True black behind it", prefs.overlayBlack(), tint, new Ui.Toggle() {
                    public void set(boolean on) {
                        prefs.setOverlayBlack(on);
                        service(LyricService.ACTION_RESTYLE);
                    }
                }),
                ui.note("True black switches those pixels off on an OLED, but it overrides the opacity "
                        + "slider - a dimmed panel is never fully black."));

        section(column, "Tap it", tint,
                ui.note("A tap on the floating lyric copies the line and the panel flashes. It does not open "
                        + "Discord from there: Android only allows that hop from a notification button, so "
                        + "the notification's Copy does it and the overlay keeps to the clipboard."));
    }

    private static String spLabel(float sp) {
        return ((int) sp) + " sp";
    }

    private static String percentLabel(float alpha) {
        return Math.round(alpha * 100) + "% opaque";
    }

    private static int alphaProgress(float alpha) {
        return Math.round(alpha * 100) - ALPHA_MIN;
    }

    // --- Sources -----------------------------------------------------------------------------

    private void sourcesTab(LinearLayout column, int tint) {
        List<View> rows = new ArrayList<View>();
        for (final Providers.Entry entry : Providers.ALL) {
            rows.add(ui.toggle(entry.label, prefs.providerEnabled(entry.key), tint, new Ui.Toggle() {
                public void set(boolean on) {
                    setProvider(entry.key, on);
                }
            }));
            rows.add(ui.note(entry.blurb));
        }
        rows.add(ui.note("They are tried in this order and the first one that answers wins. A song already on "
                + "screen keeps its lyric; the new source is used from the next song."));
        column.addView(ui.gap());
        section(column, "Where the lyrics come from", tint, rows.toArray(new View[rows.size()]));
    }

    /**
     * Turning the last source off would look like a broken app rather than an empty one, so the switch bounces
     * back - and it bounces back on the next build, because the toggle is recreated from the preference.
     */
    private void setProvider(String key, boolean on) {
        if (!on && prefs.providers().size() <= 1) {
            toast("Keep at least one source on");
            rebuild();
            return;
        }
        prefs.setProvider(key, on);
        restyle();
    }

    // --- Settings ----------------------------------------------------------------------------

    private void settingsTab(LinearLayout column, int tint) {
        column.addView(ui.gap());
        section(column, "Colour", tint,
                ui.toggle("Use the phone's accent for the buttons", prefs.deviceAccent(), tint,
                        new Ui.Toggle() {
                            public void set(boolean on) {
                                prefs.setDeviceAccent(on);
                                rebuild();
                            }
                        }),
                ui.note("The tabs keep their own colours either way; this drives the start button, the "
                        + "notification and the overlay flash."));

        section(column, "Notification", tint,
                ui.toggle("Album art in the notification", prefs.artwork(), tint, new Ui.Toggle() {
                    public void set(boolean on) {
                        prefs.setArtwork(on);
                        service(LyricService.ACTION_RESTYLE);
                    }
                }));

        section(column, "To Discord", tint,
                ui.toggle("Open Discord after Copy on the notification", prefs.openDiscord(), tint,
                        new Ui.Toggle() {
                            public void set(boolean on) {
                                prefs.setOpenDiscord(on);
                            }
                        }),
                ui.toggle("Cut the line to what a status holds", prefs.fitStatus(), tint, new Ui.Toggle() {
                    public void set(boolean on) {
                        prefs.setFitStatus(on);
                    }
                }),
                ui.note("A custom status holds " + Presentation.STATUS_LIMIT + " characters, and an emoji or "
                        + "a bold letter costs two of them. Cutting at a word with an ellipsis is what stops "
                        + "Discord grey-ing the paste out."));

        List<View> diagnostics = new ArrayList<View>();
        final String report = Crash.read(this);
        if (!TextUtils.isEmpty(report)) {
            diagnostics.add(ui.note("LyricRPC left a crash report on this phone."));
            diagnostics.add(ui.button("Read the crash report", ui.color(R.color.danger),
                    Ui.contrast(ui.color(R.color.danger)), new Ui.Tap() {
                        public void run() {
                            startActivity(new Intent(MainActivity.this, CrashActivity.class));
                        }
                    }));
        }
        diagnostics.add(ui.button("LyricRPC on GitHub", ui.color(R.color.card_soft),
                ui.color(R.color.ink), new Ui.Tap() {
                    public void run() {
                        openUrl(REPO_URL);
                    }
                }));
        diagnostics.add(ui.note(versionName() + " - the source is on GitHub, and the app keeps no account, "
                + "no token and no lyric of yours anywhere."));
        section(column, "About", tint, diagnostics.toArray(new View[diagnostics.size()]));
    }

    private String versionName() {
        try {
            return "LyricRPC " + getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception noSelfInfo) {
            return "LyricRPC";
        }
    }

    // --- state ------------------------------------------------------------------------------

    private void toggleService() {
        if (LyricService.isRunning()) {
            stopService(new Intent(this, LyricService.class));
            return;
        }
        if (!source.hasAccess())
            toast("Grant notification access so the app can see what Spotify is playing");
        if (!Overlay.canDraw(this))
            toast("Allow draw-over-apps for the floating lyric");
        prefs.setEnabled(true);
        startService(new Intent(this, LyricService.class).setAction(LyricService.ACTION_START));
    }

    private Ui.Tap shift(final long deltaMs) {
        return new Ui.Tap() {
            public void run() {
                prefs.addOffset(deltaMs);
                restyle();
            }
        };
    }

    private void restyle() {
        service(LyricService.ACTION_RESTYLE);
    }

    private void service(String action) {
        if (!LyricService.isRunning()) return;
        startService(new Intent(this, LyricService.class).setAction(action));
    }

    private String currentText() {
        LyricEngine.State state = engineState();
        return state == null ? "" : state.text;
    }

    private Lyrics.Result lyrics() {
        LyricEngine.State state = engineState();
        return state == null ? null : state.lyrics;
    }

    private LyricEngine.State engineState() {
        LyricService service = LyricService.current();
        return service == null ? null : service.state();
    }

    private void handoff(String text, boolean openDiscord) {
        if (!Handoff.copy(this, text, prefs, false, "lyric")) {
            toast("Nothing to copy yet - play something first");
            return;
        }
        toast(prefs.fitStatus() ? "Copied, cut to fit a Discord status" : "Copied");
        if (openDiscord && prefs.openDiscord() && !Handoff.openDiscord(this))
            toast("Discord is not installed on this phone");
    }

    private void copySong() {
        Lyrics.Result song = lyrics();
        if (song == null || song.isEmpty()) {
            toast("No lyric loaded yet");
            return;
        }
        // The whole song is never a status, so it goes on the clipboard untrimmed.
        Handoff.put(this, song.toLrc(), "lrc");
        toast("Copied the whole song as LRC");
    }

    private void drawState() {
        if (ui == null) return;
        LyricEngine.State state = engineState();
        Track track = state == null ? null : state.track;
        boolean running = LyricService.isRunning();

        set(statusLine, track == null
                ? (running ? "Waiting for a track - play something in Spotify" : "Stopped")
                : (track.playing ? "Playing  " : "Paused  ") + track.title
                        + (TextUtils.isEmpty(track.artist) ? "" : "  -  " + track.artist));

        String sources = state == null || state.lyrics == null ? "" : "  from " + state.lyrics.source;
        set(accessLine, "notification access " + yesNo(source.hasAccess())
                + "   overlay " + yesNo(Overlay.canDraw(this))
                + (state == null ? "" : "   " + state.status) + sources);
        if (startStop != null) startStop.setText(running ? "Stop lyrics" : "Start lyrics");
        set(offsetLabel, "Offset " + prefs.offsetMs() + " ms");
        set(preview, Presentation.apply(sample(state), prefs.style()));
    }

    private static void set(TextView view, String value) {
        if (view != null) view.setText(value);
    }

    /** The line being sung, or a stand-in so the preview shows the chosen style before anything plays. */
    private static String sample(LyricEngine.State state) {
        if (state != null) {
            // The undecorated text: state.text already carries the style, and applying it twice would
            // stack the decoration ("♪ ♪ line").
            if (state.line != null && !TextUtils.isEmpty(state.line.text)) return state.line.text;
            if (state.lyrics != null)
                for (Line line : state.lyrics.lines)
                    if (!TextUtils.isEmpty(line.text)) return line.text;
        }
        return "In the morning we are both as free as day";
    }

    private static String yesNo(boolean value) {
        return value ? "granted" : "missing";
    }

    private void askForNotificationsOnce() {
        // POST_NOTIFICATIONS only became a runtime permission on 33; before that the channel is enough.
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[] { android.Manifest.permission.POST_NOTIFICATIONS }, REQUEST_NOTIFICATIONS);
    }

    private void open(String action) {
        try {
            startActivity(new Intent(action));
        } catch (Exception noSuchSettingsScreen) {
            toast("This phone has no settings screen for that");
        }
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception nothingHandlesIt) {
            toast("No browser on this phone");
        }
    }

    // --- small builders ---------------------------------------------------------------------

    /** A view that should fill the width of the column it sits in. */
    private static View grow(View view) {
        view.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return view;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}

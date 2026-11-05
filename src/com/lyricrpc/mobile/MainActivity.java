package com.lyricrpc.mobile;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
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
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.lyricrpc.mobile.core.Decorations;
import com.lyricrpc.mobile.core.Line;
import com.lyricrpc.mobile.core.Lyrics;
import com.lyricrpc.mobile.core.Presentation;
import com.lyricrpc.mobile.core.Providers;
import com.lyricrpc.mobile.core.Track;

import java.util.Locale;

/**
 * The whole UI: the permission tiles, the start switch, the decoration grid, the lyric sources and the
 * timing nudge. Built in code, because a Gradle-free APK is easier to keep honest without layout XML.
 */
public final class MainActivity extends Activity {
    private static final int REQUEST_NOTIFICATIONS = 71;
    private static final int OVERLAY_MIN_SP = 11;
    private static final int OVERLAY_MAX_SP = 34;
    private static final int CHIPS_PER_ROW = 3;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private MediaSource source;
    /** Resolved once per build: the phone's accent on Android 12 and later, the brand green otherwise. */
    private int accent;

    private TextView statusLine;
    private TextView accessLine;
    private Button startStop;
    private LinearLayout decorationBox;
    private TextView preview;
    private TextView offsetLabel;

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
        prefs = new Prefs(this);
        source = new MediaSource(this);
        accent = Theme.accent(this, prefs);
        setContentView(build());
        askForNotificationsOnce();
    }

    /**
     * Colours are baked into the views at build time, so changing the accent means building them again.
     * A settings screen losing its scroll position for one tap is the cheaper half of that trade.
     */
    private void rebuild() {
        main.removeCallbacks(refresh);
        setContentView(build());
        drawState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        main.postDelayed(refresh, 200);
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(refresh);
        super.onPause();
    }

    // --- layout -----------------------------------------------------------------------------

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 48);

        statusLine = text(18, true);
        statusLine.setText("LyricRPC");
        accessLine = text(13, false);
        accessLine.setTextColor(Color.DKGRAY);
        root.addView(statusLine);
        root.addView(accessLine);

        startStop = button("Start lyrics", new Runnable() {
            public void run() {
                toggleService();
            }
        });
        startStop.setBackgroundColor(accent);
        startStop.setTextColor(Color.WHITE);
        root.addView(startStop);

        root.addView(section("Permissions"));
        root.addView(buttonRow(
                button("Notification access", new Runnable() {
                    public void run() {
                        open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                    }
                }),
                button("Draw over apps", new Runnable() {
                    public void run() {
                        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    }
                })));

        root.addView(section("Look"));
        decorationBox = new LinearLayout(this);
        decorationBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(decorationBox);
        buildDecorations();

        preview = text(17, false);
        preview.setGravity(Gravity.CENTER);
        preview.setPadding(16, 24, 16, 24);
        preview.setBackgroundColor(0xFF202020);
        preview.setTextColor(Color.WHITE);
        root.addView(preview);

        root.addView(toggle("Floating lyric on screen", prefs.overlay(), new Toggle() {
            public void set(boolean on) {
                prefs.setOverlay(on);
                if (on && !Overlay.canDraw(MainActivity.this))
                    toast("Allow \"draw over other apps\" for the floating lyric");
                service(LyricService.ACTION_RESTYLE);
            }
        }));

        LinearLayout sizeRow = new LinearLayout(this);
        sizeRow.setOrientation(LinearLayout.HORIZONTAL);
        sizeRow.addView(label("Overlay size"));
        SeekBar size = new SeekBar(this);
        size.setMax(OVERLAY_MAX_SP - OVERLAY_MIN_SP);
        size.setProgress((int) (prefs.overlayTextSp() - OVERLAY_MIN_SP));
        LinearLayout.LayoutParams grow = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        size.setLayoutParams(grow);
        size.setOnSeekBarChangeListener(new Drag() {
            @Override
            void to(int progress) {
                prefs.setOverlayTextSp(OVERLAY_MIN_SP + progress);
                service(LyricService.ACTION_RESTYLE);
            }
        });
        sizeRow.addView(size);
        root.addView(sizeRow);

        root.addView(section("Theme"));
        root.addView(toggle("Use the phone's accent colour", prefs.deviceAccent(), new Toggle() {
            public void set(boolean on) {
                prefs.setDeviceAccent(on);
                accent = Theme.accent(MainActivity.this, prefs);
                rebuild();
            }
        }));
        root.addView(toggle("True-black overlay for OLED screens", prefs.overlayBlack(), new Toggle() {
            public void set(boolean on) {
                prefs.setOverlayBlack(on);
                service(LyricService.ACTION_RESTYLE);
            }
        }));
        root.addView(toggle("Album art in the notification", prefs.artwork(), new Toggle() {
            public void set(boolean on) {
                prefs.setArtwork(on);
                service(LyricService.ACTION_RESTYLE);
            }
        }));

        root.addView(section("Timing"));
        offsetLabel = text(14, false);
        root.addView(offsetLabel);
        root.addView(buttonRow(
                button("-50 ms", offset(-50)),
                button("+50 ms", offset(50)),
                button("Reset", new Runnable() {
                    public void run() {
                        prefs.resetOffset();
                        restyle();
                    }
                })));

        root.addView(section("Lyric sources"));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        for (Providers.Entry entry : Providers.ALL) {
            Switch toggle = new Switch(this);
            toggle.setText(entry.label);
            toggle.setChecked(prefs.providerEnabled(entry.key));
            final String key = entry.key;
            toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton button, boolean checked) {
                    setProvider(key, checked, button);
                }
            });
            box.addView(toggle);
        }
        root.addView(box);

        root.addView(section("Handoff to Discord"));
        TextView howto = text(13, false);
        howto.setTextColor(Color.DKGRAY);
        howto.setText("Tap the floating lyric, or Copy on the notification, to put the current line on the clipboard. "
                + "Discord's mobile app lets no other app write its status, so paste it into Set Status yourself.");
        root.addView(howto);
        root.addView(buttonRow(
                button("Copy current lyric", new Runnable() {
                    public void run() {
                        copy(currentText(), "lyric", "Copied - paste it into Discord's status");
                    }
                }),
                button("Copy whole song", new Runnable() {
                    public void run() {
                        Lyrics.Result lyrics = lyrics();
                        if (lyrics == null) {
                            toast("No lyric loaded yet");
                            return;
                        }
                        copy(lyrics.toLrc(), "lrc", "Copied the whole song as LRC");
                    }
                })));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private void buildDecorations() {
        decorationBox.removeAllViews();
        String chosen = prefs.decoration();
        for (String group : Decorations.groups()) {
            TextView caption = text(10, true);
            caption.setText(group.toUpperCase(Locale.ROOT));
            caption.setTextColor(Color.DKGRAY);
            decorationBox.addView(caption);

            // A horizontal LinearLayout has no wrapping, so the chips are laid out in rows of three with
            // equal weight; forty names in one row would be squeezed to nothing.
            LinearLayout row = null;
            for (Decorations.Decor decor : Decorations.inGroup(group)) {
                if (row == null || row.getChildCount() >= CHIPS_PER_ROW) {
                    row = new LinearLayout(this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    decorationBox.addView(row);
                }
                final String value = decor.value;
                Button chip = button(decor.label, new Runnable() {
                    public void run() {
                        prefs.setDecoration(value);
                        restyle();
                        buildDecorations();
                    }
                });
                chip.setTextSize(9f);
                chip.setPadding(6, 4, 6, 4);
                chip.setSingleLine(true);
                chip.setBackgroundColor(value.equals(chosen) ? accent : 0xFFEFEFEF);
                chip.setTextColor(value.equals(chosen) ? Color.WHITE : 0xFF303030);
                row.addView(chip, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
        }
    }

    /**
     * Turning the last source off would look like a broken app rather than an empty one, so the switch
     * bounces back. {@code setChecked} has to wait for the current callback to finish, or it would re-enter
     * this listener mid-flight.
     */
    private void setProvider(String key, boolean on, final CompoundButton button) {
        if (!on && prefs.providers().size() <= 1) {
            toast("Keep at least one source on");
            button.post(new Runnable() {
                @Override
                public void run() {
                    button.setChecked(true);
                }
            });
            return;
        }
        prefs.setProvider(key, on);
        // A song already on screen keeps its lyric; the new source is used from the next song.
        restyle();
    }

    private Runnable offset(final long delta) {
        return new Runnable() {
            public void run() {
                prefs.addOffset(delta);
                restyle();
            }
        };
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

    private void drawState() {
        LyricEngine.State state = engineState();
        Track track = state == null ? null : state.track;

        if (track == null) {
            statusLine.setText(LyricService.isRunning() ? "Running - waiting for a track" : "Stopped");
        } else {
            statusLine.setText((track.playing ? "Playing  " : "Paused  ") + track.title
                    + (TextUtils.isEmpty(track.artist) ? "" : "  -  " + track.artist));
        }

        String sources = state == null || state.lyrics == null ? "" : "  from " + state.lyrics.source;
        accessLine.setText("notification access " + yesNo(source.hasAccess())
                + "   overlay " + yesNo(Overlay.canDraw(this))
                + (state == null ? "" : "   " + state.status) + sources);
        startStop.setText(LyricService.isRunning() ? "Stop lyrics" : "Start lyrics");
        offsetLabel.setText("Offset " + prefs.offsetMs() + " ms - a lyric that lands early wants a negative offset");
        preview.setText(Presentation.apply(sample(state), prefs.style()));
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

    private void copy(String text, String label, String feedback) {
        if (TextUtils.isEmpty(text)) {
            toast("Nothing to copy yet - play something first");
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
        toast(feedback);
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

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    // --- small builders ---------------------------------------------------------------------

    private TextView text(float size, boolean bold) {
        TextView view = new TextView(this);
        view.setTextSize(size);
        if (bold) view.getPaint().setFakeBoldText(true);
        view.setPadding(0, 8, 0, 8);
        return view;
    }

    private TextView label(String value) {
        TextView view = text(13, false);
        view.setText(value);
        return view;
    }

    private TextView section(String title) {
        TextView view = text(12, true);
        view.setText(title.toUpperCase(Locale.ROOT));
        view.setTextColor(accent);
        view.setPadding(0, 40, 0, 8);
        return view;
    }

    private Button button(String label, Runnable onClick) {
        Button view = new Button(this);
        view.setText(label);
        view.setAllCaps(false);
        view.setOnClickListener(new Click(onClick));
        return view;
    }

    /** What a settings switch does when the user moves it. */
    private interface Toggle {
        void set(boolean on);
    }

    private Switch toggle(String label, boolean checked, final Toggle onChange) {
        Switch view = new Switch(this);
        view.setText(label);
        view.setChecked(checked);
        view.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean value) {
                onChange.set(value);
            }
        });
        return view;
    }

    private LinearLayout buttonRow(View... views) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (View view : views) {
            LinearLayout.LayoutParams grow = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            view.setLayoutParams(grow);
            row.addView(view);
        }
        return row;
    }

    private static final class Click implements View.OnClickListener {
        private final Runnable action;

        Click(Runnable action) {
            this.action = action;
        }

        @Override
        public void onClick(View v) {
            action.run();
        }
    }

    /** Only the change we care about, without the two tracking callbacks. */
    private abstract static class Drag implements SeekBar.OnSeekBarChangeListener {
        abstract void to(int progress);

        @Override
        public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
            if (fromUser) to(progress);
        }

        @Override
        public void onStartTrackingTouch(SeekBar bar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar bar) {
        }
    }
}

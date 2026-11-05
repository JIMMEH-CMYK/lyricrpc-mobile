package com.lyricrpc.mobile;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Every view the settings screen is made of, in one place, so the colour lives in the palette instead of
 * scattered through a hundred setters. No XML and no layout editor: the whole screen is built here from
 * the same numbers the launcher icon uses.
 */
final class Ui {
    /** What a control does when the user taps it. */
    interface Tap {
        void run();
    }

    /** Which chip, tab or option was picked. */
    interface Pick {
        void pick(int index);
    }

    /** A value as the user drags it. */
    interface Slide {
        void to(int value);
    }

    /** A switch, with the state the user moved it to. */
    interface Toggle {
        void set(boolean on);
    }

    /** Free text, as it changes. */
    interface Typed {
        void to(String value);
    }

    private final Context context;
    private final float density;

    Ui(Context context) {
        this.context = context;
        this.density = context.getResources().getDisplayMetrics().density;
    }

    int color(int resId) {
        return context.getColor(resId);
    }

    /** dp to pixels, because a hand-built screen still has to look right on every phone. */
    int dp(int value) {
        return Math.round(value * density);
    }

    // --- surfaces -------------------------------------------------------------------------

    /** The window background: the same violet-to-green plate the icon is painted on. */
    View backdrop(View content) {
        FrameLayout frame = new FrameLayout(context);
        frame.setBackground(plate());
        frame.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return frame;
    }

    private android.graphics.drawable.GradientDrawable plate() {
        android.graphics.drawable.GradientDrawable out = new android.graphics.drawable.GradientDrawable();
        out.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM);
        out.setColors(new int[] { color(R.color.icon_plate_a), color(R.color.icon_plate_b),
                color(R.color.icon_plate_c) });
        return out;
    }

    private android.graphics.drawable.GradientDrawable panel(int background, int stroke, int radiusDp) {
        android.graphics.drawable.GradientDrawable out = new android.graphics.drawable.GradientDrawable();
        out.setColor(background);
        out.setCornerRadius(dp(radiusDp));
        if (stroke != 0) out.setStroke(dp(1), stroke);
        return out;
    }

    /** A rounded card of stacked rows: the unit every section of the screen is built from. */
    View card(View... rows) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(12));
        box.setBackground(panel(color(R.color.card), 0x22FFFFFF, 18));
        for (View row : rows) box.addView(row);
        return box;
    }

    /** The same card with a coloured rail down the left, which is how a tab shows its own identity. */
    View card(int tint, View... rows) {
        LinearLayout box = (LinearLayout) card(rows);
        box.setBackground(panel(color(R.color.card), tint & 0x00FFFFFF | 0x40000000, 18));
        return box;
    }

    /** A soft translucent panel for things that sit inside a card, like the lyric preview. */
    View inset(int background, View... rows) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        box.setBackground(panel(background, 0, 14));
        for (View row : rows) box.addView(row);
        return box;
    }

    // --- text -------------------------------------------------------------------------------

    TextView label(String value, float sizeSp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setTextSize(sizeSp);
        view.setText(value);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(4), 0, dp(4));
        return view;
    }

    /** A section heading in the tab's own colour. */
    TextView heading(String value, int tint) {
        TextView view = new TextView(context);
        view.setTextSize(11);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setText(value.toUpperCase(java.util.Locale.ROOT));
        view.setTextColor(tint);
        view.setLetterSpacing(0.12f);
        view.setPadding(0, dp(2), 0, dp(6));
        return view;
    }

    /** A short caption under a control, saying what it actually does. */
    TextView note(String value) {
        return label(value, 11, color(R.color.ink_dim), false);
    }

    // --- controls -------------------------------------------------------------------------

    Button button(String value, int background, int foreground, Tap onTap) {
        Button view = new Button(context);
        view.setText(value);
        view.setAllCaps(false);
        view.setTextSize(13);
        view.setTextColor(foreground);
        view.setBackground(panel(background, 0, 14));
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        view.setOnClickListener(new Click(onTap));
        return view;
    }

    /** A pill-shaped option in a chip row; the tint only appears when it is the chosen one. */
    View chip(String value, boolean selected, int tint, Tap onTap) {
        TextView view = new TextView(context);
        view.setTextSize(11);
        view.setText(value);
        view.setSingleLine(true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(10), dp(7), dp(10), dp(7));
        view.setBackground(panel(selected ? tint : color(R.color.card_soft),
                selected ? 0 : 0x33FFFFFF, 999));
        // A bright chip needs dark ink and a dim one needs light ink, or the text vanishes either way.
        view.setTextColor(selected ? contrast(tint) : color(R.color.ink_dim));
        if (selected) view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setOnClickListener(new Click(onTap));
        return view;
    }

    /** Chips laid out in wrapped rows, because a horizontal LinearLayout has no wrapping of its own. */
    View chipRow(java.util.List<String> values, int selectedIndex, int tint, Pick onPick) {
        LinearLayout rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = null;
        for (int i = 0; i < values.size(); i++) {
            if (row == null || row.getChildCount() >= 3) {
                row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                gap.topMargin = dp(4);
                gap.bottomMargin = dp(4);
                rows.addView(row, gap);
            }
            final int at = i;
            View chip = chip(values.get(i), i == selectedIndex, tint, new Tap() {
                public void run() {
                    onPick.pick(at);
                }
            });
            LinearLayout.LayoutParams grow = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            grow.leftMargin = dp(2);
            grow.rightMargin = dp(2);
            row.addView(chip, grow);
        }
        return rows;
    }

    Switch toggle(String value, boolean checked, int tint, Toggle onChange) {
        Switch view = new Switch(context);
        view.setText(value);
        view.setTextSize(13);
        view.setTextColor(color(R.color.ink));
        view.setChecked(checked);
        view.setPadding(0, dp(8), 0, dp(8));
        ColorStateList paint = new ColorStateList(
                new int[][] { new int[] { android.R.attr.state_checked }, new int[] {} },
                new int[] { tint, 0x33FFFFFF });
        view.setThumbTintList(paint);
        view.setTrackTintList(paint);
        view.setOnCheckedChangeListener(new Flip(onChange));
        return view;
    }

    /**
     * A labelled slider. {@code readout} is the caller's own TextView, added underneath and left there for
     * the drag callback to rewrite - which is why it is passed in rather than created here.
     */
    View slider(String value, int progress, int max, int tint, TextView readout, Slide onSlide) {
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(6), 0, dp(6));
        box.addView(label(value, 13, color(R.color.ink), false));

        SeekBar bar = new SeekBar(context);
        bar.setMax(max);
        bar.setProgress(progress);
        bar.setPadding(dp(4), dp(10), dp(4), dp(10));
        bar.setProgressTintList(ColorStateList.valueOf(tint));
        bar.setThumbTintList(ColorStateList.valueOf(tint));
        bar.setOnSeekBarChangeListener(new Drag(onSlide));
        box.addView(bar);

        if (readout != null) box.addView(readout);
        return box;
    }

    EditText field(String value, String hint, Typed onType) {
        EditText view = new EditText(context);
        view.setText(value == null ? "" : value);
        view.setHint(hint);
        view.setTextSize(14);
        view.setTextColor(color(R.color.ink));
        view.setHintTextColor(color(R.color.ink_dim));
        view.setSingleLine(true);
        view.setBackground(panel(color(R.color.card_soft), 0, 12));
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        view.addTextChangedListener(new Watcher(onType));
        return view;
    }

    /** A small coloured status pill, so a permission reads as granted or missing at a glance. */
    View pill(String value, boolean good) {
        TextView view = new TextView(context);
        view.setTextSize(11);
        view.setText(value);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(10), dp(5), dp(10), dp(5));
        int tint = good ? color(R.color.tab_now) : color(R.color.danger);
        view.setBackground(panel((tint & 0x00FFFFFF) | 0x33000000, 0, 999));
        view.setTextColor(tint);
        return view;
    }

    /** The tab bar: one coloured pill per tab, the selected one filled in its own colour. */
    View tabStrip(String[] names, int[] tints, int selected, Pick onPick) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(6), 0, dp(10));
        for (int i = 0; i < names.length; i++) {
            final int at = i;
            View tab = chip(names[i], i == selected, tints[i], new Tap() {
                public void run() {
                    onPick.pick(at);
                }
            });
            LinearLayout.LayoutParams grow = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            grow.leftMargin = dp(3);
            grow.rightMargin = dp(3);
            row.addView(tab, grow);
        }
        return row;
    }

    /** Any number of views side by side, each taking an equal share of the width. */
    View row(View... views) {
        LinearLayout line = new LinearLayout(context);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        for (View view : views) {
            LinearLayout.LayoutParams grow = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            grow.leftMargin = dp(3);
            grow.rightMargin = dp(3);
            line.addView(view, grow);
        }
        return line;
    }

    /** Space that pushes the rows below it down the card. */
    View gap() {
        View view = new View(context);
        view.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(8)));
        return view;
    }

    static int contrast(int background) {
        // The relative-luminosity test, so a chip never paints its text the same colour as itself.
        double luminance = (0.299 * Color.red(background) + 0.587 * Color.green(background)
                + 0.114 * Color.blue(background)) / 255.0;
        return luminance > 0.55 ? 0xFF12101A : Color.WHITE;
    }

    // --- listeners ------------------------------------------------------------------------

    private final class Click implements View.OnClickListener {
        private final Tap action;

        Click(Tap action) {
            this.action = action;
        }

        @Override
        public void onClick(View view) {
            action.run();
        }
    }

    private final class Flip implements CompoundButton.OnCheckedChangeListener {
        private final Toggle action;

        Flip(Toggle action) {
            this.action = action;
        }

        @Override
        public void onCheckedChanged(CompoundButton view, boolean checked) {
            action.set(checked);
        }
    }

    private final class Drag implements SeekBar.OnSeekBarChangeListener {
        private final Slide action;

        Drag(Slide action) {
            this.action = action;
        }

        @Override
        public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
            if (fromUser) action.to(progress);
        }

        @Override
        public void onStartTrackingTouch(SeekBar bar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar bar) {
        }
    }

    private static final class Watcher implements android.text.TextWatcher {
        private final Typed action;

        Watcher(Typed action) {
            this.action = action;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            action.to(s.toString());
        }

        @Override
        public void afterTextChanged(android.text.Editable s) {
        }
    }
}

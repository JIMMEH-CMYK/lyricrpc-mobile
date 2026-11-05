package com.lyricrpc.mobile;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * The floating lyric: one transparent window that sits over whatever is on screen, so the lyric is
 * readable while Spotify, a video or a game is on top.
 *
 * <p>It needs the "display over other apps" permission, which is the only way Android lets an app put
 * pixels on another app's screen. Without it the notification still works and this quietly stays off.</p>
 */
public final class Overlay {
    private final Context context;
    private final WindowManager windows;
    private final Prefs prefs;

    private TextView view;
    private WindowManager.LayoutParams params;

    private float dragStartX;
    private float dragStartY;
    private int windowStartX;
    private int windowStartY;
    private float touchedAtX;
    private float touchedAtY;

    Overlay(Context context) {
        this.context = context.getApplicationContext();
        this.windows = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        this.prefs = new Prefs(this.context);
    }

    /** The permission the user has to grant in settings; the app cannot do it for them. */
    public static boolean canDraw(Context context) {
        return Settings.canDrawOverlays(context);
    }

    void show(LyricEngine.State state, Prefs prefs) {
        if (!prefs.overlay() || !canDraw(context)) {
            hide();
            return;
        }
        ensure(prefs);
        // ensure() drops the window when the permission is revoked between the check and the call, and a
        // service that dies over a floating label would take the notification down with it.
        if (view == null) return;
        String text = TextUtils.isEmpty(state.text) ? placeholder(state) : state.text;
        if (!text.equals(view.getText())) view.setText(text);
        view.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
    }

    private static String placeholder(LyricEngine.State state) {
        if (state.searching) return "finding the lyric...";
        return TextUtils.isEmpty(state.status) ? "" : state.status;
    }

    void applyStyle(Prefs prefs) {
        if (view == null) return;
        style(prefs);
    }

    void hide() {
        if (view == null || windows == null) return;
        try {
            windows.removeView(view);
        } catch (IllegalArgumentException alreadyGone) {
            // The window can outlive our reference after the user revokes the permission.
        }
        view = null;
        params = null;
    }

    private void ensure(Prefs prefs) {
        if (view != null || windows == null) return;
        TextView label = new TextView(context);
        label.setGravity(Gravity.CENTER);
        label.setPadding(28, 16, 28, 16);
        label.setBackgroundColor(Theme.surface(prefs));
        label.setTextColor(Color.WHITE);
        label.setMaxWidth(1400);

        WindowManager.LayoutParams next = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // Not focusable, so a tap never steals input from the app underneath.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        next.gravity = Gravity.TOP | Gravity.START;
        next.x = 40;
        // Low on the screen: that is where a subtitle belongs and where it hides nothing important.
        next.y = 1400;

        label.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View target, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dragStartX = event.getRawX();
                        dragStartY = event.getRawY();
                        touchedAtX = event.getRawX();
                        touchedAtY = event.getRawY();
                        windowStartX = params.x;
                        windowStartY = params.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = windowStartX + (int) (event.getRawX() - dragStartX);
                        params.y = windowStartY + (int) (event.getRawY() - dragStartY);
                        windows.updateViewLayout(target, params);
                        return true;
                    case MotionEvent.ACTION_UP:
                        // A tap, not a drag: copy the line so the user can paste it into Discord's status.
                        // No attempt to open Discord from here - this window grants no background launch.
                        if (Math.abs(event.getRawX() - touchedAtX) < 12
                                && Math.abs(event.getRawY() - touchedAtY) < 12) {
                            Handoff.copy(context, label.getText().toString(), prefs, false, "lyric");
                            flash(label);
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });

        view = label;
        params = next;
        style(prefs);
        try {
            windows.addView(view, params);
        } catch (RuntimeException refused) {
            // The permission can be revoked between the check and the call; the notification must survive it.
            view = null;
            params = null;
        }
    }

    private void style(Prefs prefs) {
        view.setTextSize(prefs.overlayTextSp());
        // True black is only true black at full opacity; any alpha would blend the pixels back up.
        view.setAlpha(prefs.overlayBlack() ? 1f : prefs.overlayAlpha());
        view.setBackgroundColor(Theme.surface(prefs));
    }

    /** The line visibly reacts, which is the only feedback a focusless window can give. */
    private void flash(final TextView label) {
        label.setBackgroundColor(Theme.flash(Theme.accent(context, prefs)));
        label.postDelayed(new Runnable() {
            @Override
            public void run() {
                label.setBackgroundColor(Theme.surface(prefs));
            }
        }, 260);
    }
}

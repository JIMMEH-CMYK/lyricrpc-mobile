package com.lyricrpc.mobile;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;

/**
 * The one place a colour is decided, so the settings screen, the notification shade and the floating
 * lyric all agree. Borrowed from the way Kizzey handles it: the app should wear the phone's palette,
 * not ship its own, and a dark surface should be able to go fully black on an OLED panel.
 */
public final class Theme {
    /** The brand green, used when the user turns the dynamic palette off or the ROM has none. */
    public static final int GREEN = Color.parseColor("#1DB954");

    private Theme() {
    }

    public static int accent(Context context, Prefs prefs) {
        if (prefs.deviceAccent() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                return context.getColor(android.R.color.system_accent1_400);
            } catch (RuntimeException noDynamicPalette) {
                // A vendor build without the dynamic colours still has to show something.
            }
        }
        return GREEN;
    }

    /** Translucent by default; fully black on request, so an OLED pixel is switched off, not dimmed. */
    public static int surface(Prefs prefs) {
        return prefs.overlayBlack() ? 0xFF000000 : 0xB0000000;
    }

    /** The accent made fully opaque, for the tap flash on the overlay. */
    public static int flash(int accent) {
        return 0xFF000000 | (accent & 0x00FFFFFF);
    }
}

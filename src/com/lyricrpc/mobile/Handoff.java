package com.lyricrpc.mobile;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

import com.lyricrpc.mobile.core.Presentation;

/**
 * The last half of the loop: the line onto the clipboard, then Discord in front of the user so the paste
 * is one tap away.
 *
 * <p>Discord offers no API for a mobile custom status and no other app is allowed to write one, so this
 * stops exactly where the honest version of the feature stops - it copies the text and opens the app.
 * Everything that used to be a manual "find Discord, open it, tap your avatar" becomes a single tap.</p>
 */
final class Handoff {
    private static final String DISCORD_PACKAGE = "com.discord";

    private Handoff() {
    }

    /** The line as it will actually land in the status box, trimmed if the user asked for that. */
    static String prepare(String text, Prefs prefs) {
        if (TextUtils.isEmpty(text)) return "";
        return prefs.fitStatus() ? Presentation.fitTo(text, Presentation.STATUS_LIMIT) : text;
    }

    /**
     * Copies, and opens Discord when {@code fromNotification} says the tap came from the shade.
     *
     * <p>That distinction is not a nicety: a PendingIntent sent from a notification is one of the few things
     * Android lets start an app from the background, while a tap on our own floating window is not, and the
     * launch would be silently swallowed on most builds. The overlay therefore stays copy-only.</p>
     */
    static boolean copy(Context context, String text, Prefs prefs, boolean fromNotification, String label) {
        String ready = prepare(text, prefs);
        if (TextUtils.isEmpty(ready)) return false;
        put(context, ready, label);
        if (fromNotification && prefs.openDiscord()) openDiscord(context);
        return true;
    }

    static void put(Context context, String text, String label) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
    }

    /** False when Discord is not installed, which the caller turns into a toast instead of a dead tap. */
    static boolean openDiscord(Context context) {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(DISCORD_PACKAGE);
        if (launch == null) return false;
        try {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(launch);
            return true;
        } catch (RuntimeException refusedByThePhone) {
            // A work-profile or restricted install can refuse the hop; the line is already copied.
            return false;
        }
    }
}

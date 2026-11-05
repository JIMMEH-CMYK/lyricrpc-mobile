package com.lyricrpc.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Typeface;
import android.os.Build;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * A crash nobody can describe is a crash nobody can fix, and this app ships with no cable and no logcat.
 * So the throwable is written to a file, pushed into a notification and - when it is the settings screen
 * itself that broke - drawn on the screen with a Copy button.
 */
public final class Crash {
    private static final String CHANNEL = "crash";
    private static final String FILE_NAME = "crash-report.txt";
    private static final int NOTIFICATION_ID = 4712;
    /** A shade only shows so much, and the part that matters is at the top anyway. */
    private static final int NOTIFY_MAX_CHARS = 2500;

    private static volatile boolean installed;

    private Crash() {
    }

    /** Catches everything the app cannot foresee. Safe to call everywhere; only the first stick. */
    public static void install(final Context context) {
        if (installed) return;
        installed = true;
        final Context app = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable error) {
                try {
                    record(app, thread, error);
                } catch (Throwable loggingFailed) {
                    // A crash logger must never become the crash it is reporting.
                }
                if (previous != null) previous.uncaughtException(thread, error);
            }
        });
    }

    /** Save the trace and let the caller keep running, for the paths that can show it themselves. */
    public static void record(Context context, Throwable error) {
        record(context, Thread.currentThread(), error);
    }

    private static void record(Context context, Thread thread, Throwable error) {
        String text = describe(context, thread, error);
        write(context, text);
        notifyShade(context, text);
    }

    // --- the report -------------------------------------------------------------------------

    private static String describe(Context context, Thread thread, Throwable error) {
        StringBuilder out = new StringBuilder();
        out.append("LyricRPC ").append(version(context)).append('\n');
        out.append("Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(") on ")
                .append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        out.append("Thread: ").append(thread == null ? "main" : thread.getName()).append("\n\n");

        StringWriter frames = new StringWriter();
        PrintWriter trace = new PrintWriter(frames);
        for (Throwable at = error; at != null; at = at.getCause()) {
            at.printStackTrace(trace);
            if (at.getCause() != null) trace.print("\nCaused by: ");
        }
        trace.flush();
        return out.append(frames).toString();
    }

    private static String version(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName + " (" + info.versionCode + ")";
        } catch (Exception noSelfInfo) {
            return "unknown";
        }
    }

    // --- the file ---------------------------------------------------------------------------

    /**
     * Both places, because which one a phone's own file manager can reach varies by vendor: the external
     * one is readable over USB, the internal one is always writable.
     */
    private static void write(Context context, String text) {
        File[] bases = { context.getExternalFilesDir(null), context.getFilesDir() };
        for (File base : bases) {
            if (base == null) continue;
            try {
                FileOutputStream out = new FileOutputStream(new File(base, FILE_NAME));
                try {
                    out.write(text.getBytes("UTF-8"));
                } finally {
                    out.close();
                }
            } catch (Exception noRoomForTheLog) {
                // Nothing to do: the notification and the screen still carry the trace.
            }
        }
    }

    public static String read(Context context) {
        File[] places = { new File(context.getExternalFilesDir(null), FILE_NAME),
                new File(context.getFilesDir(), FILE_NAME) };
        for (File file : places) {
            if (!file.isFile()) continue;
            try {
                FileInputStream in = new FileInputStream(file);
                try {
                    byte[] buffer = new byte[(int) Math.min(file.length(), 1 << 20)];
                    int filled = 0;
                    while (filled < buffer.length) {
                        int read = in.read(buffer, filled, buffer.length - filled);
                        if (read <= 0) break;
                        filled += read;
                    }
                    return new String(buffer, 0, filled, "UTF-8");
                } finally {
                    in.close();
                }
            } catch (Exception unreadable) {
                // Try the other copy.
            }
        }
        return null;
    }

    public static void clear(Context context) {
        File[] places = { new File(context.getExternalFilesDir(null), FILE_NAME),
                new File(context.getFilesDir(), FILE_NAME) };
        for (File file : places) {
            if (file.isFile() && !file.delete()) file.deleteOnExit();
        }
    }

    /**
     * Shown once, on the next launch, and deleted when dismissed - the phone can be killed mid-crash, so
     * the shade and the file are the only things that outlive the process.
     */
    public static void showPending(final Activity activity) {
        final String report = read(activity);
        if (TextUtils.isEmpty(report)) return;
        new AlertDialog.Builder(activity)
                .setTitle("LyricRPC crashed last time")
                .setView(scroll(activity, report))
                .setPositiveButton("Copy", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        copy(activity, report);
                    }
                })
                .setNeutralButton("Dismiss", null)
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        clear(activity);
                    }
                })
                .show();
    }

    // --- the screen -------------------------------------------------------------------------

    /**
     * What the app shows when even its own settings screen threw: the trace, readable and copyable,
     * without the process having to survive until a later launch.
     */
    public static View fallback(Context context, Throwable error) {
        final String report = describe(context, Thread.currentThread(), error);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 48, 32, 32);

        TextView headline = new TextView(context);
        headline.setTextSize(18f);
        headline.setTypeface(Typeface.DEFAULT_BOLD);
        headline.setText("LyricRPC could not open");
        root.addView(headline);

        TextView why = new TextView(context);
        why.setTextSize(13f);
        why.setText(error == null ? "" : error.getClass().getName()
                + (error.getMessage() == null ? "" : "\n" + error.getMessage()));
        root.addView(why);

        ScrollView box = scroll(context, report);
        root.addView(box, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        Button copy = new Button(context);
        copy.setText("Copy the report");
        copy.setAllCaps(false);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View target) {
                copy(context, report);
            }
        });
        root.addView(copy);
        return root;
    }

    private static ScrollView scroll(Context context, String text) {
        ScrollView box = new ScrollView(context);
        TextView body = new TextView(context);
        body.setTextSize(11f);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        body.setText(text);
        box.addView(body);
        return box;
    }

    private static void copy(Context context, String text) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("crash", text));
    }

    // --- the shade --------------------------------------------------------------------------

    private static void notifyShade(Context context, String text) {
        try {
            NotificationManager manager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && manager.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel channel =
                        new NotificationChannel(CHANNEL, "Crash reports", NotificationManager.IMPORTANCE_HIGH);
                channel.setShowBadge(false);
                manager.createNotificationChannel(channel);
            }

            Notification.Builder builder = new Notification.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_lyric)
                    .setContentTitle("LyricRPC crashed")
                    .setContentText(firstLine(text))
                    .setAutoCancel(true)
                    .setStyle(new Notification.BigTextStyle().bigText(
                            text.length() > NOTIFY_MAX_CHARS ? text.substring(0, NOTIFY_MAX_CHARS) : text));
            Intent open = new Intent(context, MainActivity.class);
            builder.setContentIntent(PendingIntent.getActivity(context, NOTIFICATION_ID, open,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            manager.notify(NOTIFICATION_ID, builder.build());
        } catch (Exception shadeRefused) {
            // Notifications can be switched off; the file and the screen still carry the trace.
        }
    }

    private static String firstLine(String text) {
        int at = text.indexOf('\n');
        String head = at < 0 ? text : text.substring(0, at);
        return head.length() > 80 ? head.substring(0, 79) + "..." : head;
    }
}

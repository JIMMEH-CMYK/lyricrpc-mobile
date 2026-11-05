package com.lyricrpc.mobile;

import android.app.Application;

/**
 * Registered as the application so the crash recorder is in place before a single view is built - the
 * launch failure this exists to catch happens earlier than any component's constructor.
 */
public final class LyricApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Crash.install(this);
    }
}

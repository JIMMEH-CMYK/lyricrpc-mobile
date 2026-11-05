package com.lyricrpc.mobile;

import android.app.Activity;
import android.os.Bundle;
import android.text.TextUtils;

/**
 * The crash screen, run in its own process so it survives the death of the one that crashed. A trace that
 * only exists in a file the phone cannot open is a trace nobody can read, and the launch failure this
 * exists to catch happens before the settings screen can put anything on display.
 */
public final class CrashActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String report = Crash.read(this);
        if (TextUtils.isEmpty(report)) report = "Nothing was written to the report file.";
        setContentView(Crash.screen(this, "LyricRPC crashed", report));
    }
}

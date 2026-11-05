package com.lyricrpc.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.lyricrpc.mobile.core.Decorations;
import com.lyricrpc.mobile.core.Presentation;
import com.lyricrpc.mobile.core.Providers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Everything the user can change, in one place, persisted between runs. */
public final class Prefs {
    private static final String FILE = "lyricrpc";

    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_DECORATION = "decoration";
    private static final String KEY_CASE = "caseMode";
    private static final String KEY_BOLD = "bold";
    private static final String KEY_KARAOKE = "karaoke";
    private static final String KEY_PREFIX = "prefix";
    private static final String KEY_SUFFIX = "suffix";
    private static final String KEY_OFFSET = "offsetMs";
    private static final String KEY_PROVIDERS = "providers";
    private static final String KEY_OVERLAY = "overlay";
    private static final String KEY_OVERLAY_TEXT_SP = "overlayTextSp";
    private static final String KEY_OVERLAY_ALPHA = "overlayAlpha";
    private static final String KEY_OVERLAY_BLACK = "overlayBlack";
    private static final String KEY_DEVICE_ACCENT = "deviceAccent";
    private static final String KEY_ARTWORK = "artwork";
    private static final String KEY_OPEN_DISCORD = "openDiscord";
    private static final String KEY_FIT_STATUS = "fitStatus";

    private final SharedPreferences store;

    Prefs(Context context) {
        store = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public boolean enabled() {
        return store.getBoolean(KEY_ENABLED, true);
    }

    public void setEnabled(boolean value) {
        store.edit().putBoolean(KEY_ENABLED, value).apply();
    }

    /** The switch the user last flipped, so the tile and the notification agree with the screen. */
    public String decoration() {
        return Decorations.normalize(store.getString(KEY_DECORATION, "none"));
    }

    public void setDecoration(String value) {
        store.edit().putString(KEY_DECORATION, Decorations.normalize(value)).apply();
    }

    public String caseMode() {
        String mode = store.getString(KEY_CASE, "none");
        return Arrays.asList("none", "upper", "lower", "sentence").contains(mode) ? mode : "none";
    }

    public void setCaseMode(String mode) {
        store.edit().putString(KEY_CASE, mode).apply();
    }

    public boolean bold() {
        return store.getBoolean(KEY_BOLD, false);
    }

    public void setBold(boolean value) {
        store.edit().putBoolean(KEY_BOLD, value).apply();
    }

    public boolean karaoke() {
        return store.getBoolean(KEY_KARAOKE, false);
    }

    public void setKaraoke(boolean value) {
        store.edit().putBoolean(KEY_KARAOKE, value).apply();
    }

    public String prefix() {
        return store.getString(KEY_PREFIX, "");
    }

    public String suffix() {
        return store.getString(KEY_SUFFIX, "");
    }

    public void setAffixes(String prefix, String suffix) {
        store.edit().putString(KEY_PREFIX, prefix).putString(KEY_SUFFIX, suffix).apply();
    }

    public long offsetMs() {
        return store.getLong(KEY_OFFSET, 0);
    }

    /** The offset is per-song-nudge, not absolute: adding keeps the user's place in the track. */
    public void addOffset(long deltaMs) {
        store.edit().putLong(KEY_OFFSET, offsetMs() + deltaMs).apply();
    }

    public void resetOffset() {
        store.edit().putLong(KEY_OFFSET, 0).apply();
    }

    public List<String> providers() {
        String raw = store.getString(KEY_PROVIDERS, null);
        if (TextUtils.isEmpty(raw)) return Providers.defaultEnabled();

        Set<String> known = new HashSet<String>();
        for (Providers.Entry entry : Providers.ALL) known.add(entry.key);
        List<String> enabled = new ArrayList<String>();
        for (String key : raw.split(",")) {
            String trimmed = key.trim();
            if (known.contains(trimmed) && !enabled.contains(trimmed)) enabled.add(trimmed);
        }
        // An empty selection would silently kill every lyric, so fall back to the full chain.
        return enabled.isEmpty() ? Providers.defaultEnabled() : enabled;
    }

    public void setProvider(String key, boolean on) {
        List<String> current = new ArrayList<String>(providers());
        if (on && !current.contains(key)) current.add(key);
        if (!on) current.remove(key);
        // Keep the table order, not the order the user tapped in.
        List<String> ordered = new ArrayList<String>();
        for (Providers.Entry entry : Providers.ALL)
            if (current.contains(entry.key)) ordered.add(entry.key);
        store.edit().putString(KEY_PROVIDERS, TextUtils.join(",", ordered)).apply();
    }

    public boolean providerEnabled(String key) {
        return providers().contains(key);
    }

    public boolean overlay() {
        return store.getBoolean(KEY_OVERLAY, true);
    }

    public void setOverlay(boolean value) {
        store.edit().putBoolean(KEY_OVERLAY, value).apply();
    }

    public float overlayTextSp() {
        return store.getFloat(KEY_OVERLAY_TEXT_SP, 17f);
    }

    public void setOverlayTextSp(float value) {
        store.edit().putFloat(KEY_OVERLAY_TEXT_SP, Math.max(11f, Math.min(34f, value))).apply();
    }

    public float overlayAlpha() {
        return store.getFloat(KEY_OVERLAY_ALPHA, 0.92f);
    }

    public void setOverlayAlpha(float value) {
        store.edit().putFloat(KEY_OVERLAY_ALPHA, Math.max(0.25f, Math.min(1f, value))).apply();
    }

    /** True-black instead of translucent: an OLED screen switches those pixels off entirely. */
    public boolean overlayBlack() {
        return store.getBoolean(KEY_OVERLAY_BLACK, false);
    }

    public void setOverlayBlack(boolean value) {
        store.edit().putBoolean(KEY_OVERLAY_BLACK, value).apply();
    }

    /** Follow the phone's own accent colour rather than the built-in green. */
    public boolean deviceAccent() {
        return store.getBoolean(KEY_DEVICE_ACCENT, true);
    }

    public void setDeviceAccent(boolean value) {
        store.edit().putBoolean(KEY_DEVICE_ACCENT, value).apply();
    }

    /** The album art in the notification, which some users find noisy on a small shade. */
    public boolean artwork() {
        return store.getBoolean(KEY_ARTWORK, true);
    }

    public void setArtwork(boolean value) {
        store.edit().putBoolean(KEY_ARTWORK, value).apply();
    }

    /**
     * Jump to Discord once the line is copied. Pasting it into Set Status is still the user's own tap -
     * Discord gives no other app any way to write a status, and this app does not pretend otherwise.
     */
    public boolean openDiscord() {
        return store.getBoolean(KEY_OPEN_DISCORD, true);
    }

    public void setOpenDiscord(boolean value) {
        store.edit().putBoolean(KEY_OPEN_DISCORD, value).apply();
    }

    /** Trim the copied line to what a Discord custom status can actually hold. */
    public boolean fitStatus() {
        return store.getBoolean(KEY_FIT_STATUS, true);
    }

    public void setFitStatus(boolean value) {
        store.edit().putBoolean(KEY_FIT_STATUS, value).apply();
    }

    /** The single object the core works with, rebuilt whenever a setting changes. */
    public Presentation.Style style() {
        Presentation.Style style = new Presentation.Style();
        style.decoration = decoration();
        style.caseMode = caseMode();
        style.bold = bold();
        style.karaoke = karaoke();
        style.prefix = prefix();
        style.suffix = suffix();
        style.offsetMs = offsetMs();
        return style;
    }
}

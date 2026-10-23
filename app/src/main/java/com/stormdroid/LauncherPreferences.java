package com.stormdroid;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** App-wide preferences, and the shared prefs file InstanceSettings keeps its per-instance keys in. */
public class LauncherPreferences {

    private final SharedPreferences prefs;
    private static LauncherPreferences singleton;

    private LauncherPreferences(Context applicationContext) {
        prefs = applicationContext.getSharedPreferences(C.shprefs.NAME, Context.MODE_PRIVATE);
    }

    public static void init(Context applicationContext) {
        singleton = new LauncherPreferences(applicationContext);
    }

    @Nullable
    public static LauncherPreferences getSingleton() { return singleton; }

    @NonNull
    public static LauncherPreferences requireSingleton() {
        if (singleton == null) throw new RuntimeException("LauncherPreferences is not initialized");
        return singleton;
    }

    public SharedPreferences getSharedPrefs() { return prefs; }

    // --- Render scale (GPU load) ---
    // Rendering at a LOWER fraction of the native surface is lighter on the GPU. A fixed percentage
    // floor would force a high-res weak phone to render needlessly many pixels, so the real floor is
    // computed per device by minRenderScalePercent(); the stored value is only clamped to a sane
    // absolute range. Default 72.
    public static final int RENDER_SCALE_ABS_MIN = 25;

    public int getRenderScalePercent() {
        int v = prefs.getInt("render_scale_pct", 72);
        return Math.max(RENDER_SCALE_ABS_MIN, Math.min(100, v));
    }

    /**
     * Lowest render-scale percent that still yields a &gt;=1280x768 internal resolution for the given
     * physical surface, so high-res phones can scale further down than low-res ones. Pass landscape
     * dimensions (the larger value as width). Clamped to [RENDER_SCALE_ABS_MIN, 100].
     */
    public static int minRenderScalePercent(int surfaceW, int surfaceH) {
        if (surfaceW <= 0 || surfaceH <= 0) return 72;   // safe fallback (1080p floor)
        double need = Math.max(1280.0 / surfaceW, 768.0 / surfaceH);
        int pct = (int) Math.ceil(need * 100.0);
        return Math.max(RENDER_SCALE_ABS_MIN, Math.min(100, pct));
    }

    // --- Theme mode (System / Light / Dark) ---
    // Stores an AppCompatDelegate.MODE_NIGHT_* constant; applied in StormDroidApplication.onCreate.

    public int getThemeMode() {
        return prefs.getInt("theme_mode",
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }

    public void setThemeMode(int mode) {
        prefs.edit().putInt("theme_mode", mode).apply();
    }

    // --- Last instance ---

    public String getLastInstanceName() {
        return prefs.getString("last_instance", "");
    }

    public void setLastInstanceName(String name) {
        prefs.edit().putString("last_instance", name).apply();
    }

    // --- In-game overlay — global. Off by default. ---
    // FPS  = the classic green "FPS: XX" counter, top-left.
    // FULL = the performance bar (PerfOverlayView): API, GPU, CPU, RAM, power, heat, FPS + graph.
    public static final int HUD_OFF = 0, HUD_FPS = 1, HUD_FULL = 2;

    public int getHudMode() {
        return prefs.getInt("hud_mode", HUD_OFF);
    }

    public void setHudMode(int mode) {
        prefs.edit().putInt("hud_mode", mode).apply();
    }

    // --- Daily update check (GitHub latest release vs installed version) ---
    // The last day (yyyyMMdd) we ATTEMPTED a check — recorded even on failure, so a phone with no
    // internet still only tries once per day. And the latest tag GitHub reported, so the drawer can
    // badge the GitHub icon; the badge auto-clears once the installed version matches it.

    public String getUpdateCheckDay() { return prefs.getString("update_check_day", ""); }
    public void setUpdateCheckDay(String day) { prefs.edit().putString("update_check_day", day).apply(); }

    public String getLatestSeenTag() { return prefs.getString("update_latest_tag", ""); }
    public void setLatestSeenTag(String tag) {
        prefs.edit().putString("update_latest_tag", tag == null ? "" : tag).apply();
    }

    // --- "What's new" dialog ---
    // The version it was last shown for, so it comes up once per version (LauncherFragment).

    public String getReleaseNotesShownFor() { return prefs.getString("release_notes_shown_for", ""); }
    public void setReleaseNotesShownFor(String version) {
        prefs.edit().putString("release_notes_shown_for", version).apply();
    }

    // --- Custom env vars (advanced; per-instance override in InstanceSettings) ---

    @Nullable
    public String getEnvVars() {
        return prefs.getString("env_vars", null);
    }

    // --- Haptic feedback (global default; per-instance override in InstanceSettings). Default OFF. ---

    public boolean isHapticFeedback() {
        return prefs.getBoolean("haptic", false);
    }
}

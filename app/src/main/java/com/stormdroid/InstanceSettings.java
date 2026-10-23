package com.stormdroid;

import android.content.SharedPreferences;

/**
 * Per-instance launch settings: render size, texture quality, Vulkan compatibility, frame rate,
 * orientation, haptics and the extra environment. Stored in the shared prefs under an
 * {@code inst:<name>:} prefix; a few defaults fall back to the GLOBAL {@link LauncherPreferences} value.
 * The on-screen controls layout lives in the instance folder (ats.controls.ControlsStorage).
 */
public class InstanceSettings {

    private final SharedPreferences p;
    private final String pfx;
    private final LauncherPreferences global;

    private final String instanceName;

    public InstanceSettings(String instanceName) {
        this.instanceName = instanceName;
        global = LauncherPreferences.requireSingleton();
        p = global.getSharedPrefs();
        pfx = "inst:" + instanceName + ":";
    }

    // --- Mirrored (180°-rotated) landscape. Still a FIXED orientation, just the opposite one: a USB-C
    // gamepad cradle holds the phone in one physical pose, which may be the reverse of ours. ---
    public boolean isReverseLandscape() {
        return p.getBoolean(pfx + "reverse_landscape", false);
    }

    public void setReverseLandscape(boolean v) {
        p.edit().putBoolean(pfx + "reverse_landscape", v).apply();
    }

    // --- A fixed resolution instead of filling the screen, letterboxed with black margins: 768 lines,
    // 1366x768 (16:9) or 1024x768 (4:3, for near-square foldables). Fewer pixels than any
    // device-relative preset, and the margins give the on-screen buttons somewhere to sit. Overrides the
    // render-scale setting while on. Default OFF. The "720" in the names is historical. ---
    public static final int FIXED_NONE = 0, FIXED_720_16_9 = 1, FIXED_720_4_3 = 2;

    public int getFixedResMode() {
        return p.getInt(pfx + "fixed_res", FIXED_NONE);
    }

    public void setFixedResMode(int mode) {
        p.edit().putInt(pfx + "fixed_res", mode).apply();
    }

    // --- Frame-rate cap (0 = uncapped, else 30/60), applied by il2mono to Application.targetFrameRate. ---
    public int getFpsCap() {
        return p.getInt(pfx + "fps_cap", 0);
    }

    public void setFpsCap(int fps) {
        p.edit().putInt(pfx + "fps_cap", fps).apply();
    }

    // --- Texture quality: Unity's texture mipmap limit (NativeEngine.textureMipmapLimit). NONE = every
    // texture half size, LOW = a quarter, ULTRA = an eighth. ---
    public static final int TEX_NONE = 0, TEX_LOW = 1, TEX_ULTRA = 2;

    public int getTexTier() {
        return p.getInt(pfx + "tex_tier", TEX_NONE);
    }

    public void setTexTier(int tier) {
        p.edit().putInt(pfx + "tex_tier", tier).apply();
    }

    // --- Haptic feedback: light vibration tick on on-screen button presses. Default OFF. ---
    public boolean isHapticFeedback() {
        return p.getBoolean(pfx + "haptic", global.isHapticFeedback());
    }

    public void setHapticFeedback(boolean v) {
        p.edit().putBoolean(pfx + "haptic", v).apply();
    }

    // --- Vulkan compatibility: Unity without secondary command buffers
    // (-vulkan-disable-secondary-commandbuffers). Auto = on for the GPUs known to lose the device on
    // them (NativeEngine.needsVulkanCompat: the Mali-G615); On is the way out for another GPU. ---
    public static final int VK_COMPAT_AUTO = 0, VK_COMPAT_ON = 1, VK_COMPAT_OFF = 2;

    public int getVulkanCompat() {
        return p.getInt(pfx + "vk_compat", VK_COMPAT_AUTO);
    }

    public void setVulkanCompat(int mode) {
        p.edit().putInt(pfx + "vk_compat", mode).apply();
    }

    // --- Extra env vars (KEY=VALUE, space-separated). Per-instance, falls back to the global value.
    // Power-user / diagnostic knob applied last in NativeEngine, so it can override the defaults
    // (e.g. STORMDROID_UNITY_ARGS=-force-gfx-direct). ---
    public String getEnvVars() {
        return p.getString(pfx + "env_vars", global.getEnvVars());
    }

    public void setEnvVars(String v) {
        if (v == null || v.trim().isEmpty()) p.edit().remove(pfx + "env_vars").apply();
        else p.edit().putString(pfx + "env_vars", v.trim()).apply();
    }

    // --- Render scale (per-instance; the device floor stays a global static) ---
    public int getRenderScalePercent() {
        int v = p.getInt(pfx + "render_scale_pct", global.getRenderScalePercent());
        return Math.max(LauncherPreferences.RENDER_SCALE_ABS_MIN, Math.min(100, v));
    }

    public void setRenderScalePercent(int pct) {
        p.edit().putInt(pfx + "render_scale_pct",
                Math.max(LauncherPreferences.RENDER_SCALE_ABS_MIN, Math.min(100, pct))).apply();
    }

    /** Stored scale raised to the per-device floor, as a 0..1 fraction. */
    public float getEffectiveRenderScale(int surfaceW, int surfaceH) {
        int eff = Math.max(getRenderScalePercent(), LauncherPreferences.minRenderScalePercent(surfaceW, surfaceH));
        return Math.min(100, eff) / 100f;
    }

    /** Remove all stored keys for an instance (call when the instance is deleted). */
    public static void delete(String instanceName) {
        SharedPreferences p = LauncherPreferences.requireSingleton().getSharedPrefs();
        String pfx = "inst:" + instanceName + ":";
        SharedPreferences.Editor e = p.edit();
        for (String key : p.getAll().keySet())
            if (key.startsWith(pfx)) e.remove(key);
        e.apply();
    }
}

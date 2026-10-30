package com.stormdroid.game;

import android.content.Context;
import android.util.Log;

import com.stormdroid.GameDataTransfer;
import com.stormdroid.InstanceSettings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The launcher's graphics profiles, written into the game's own settings: the JSON string "ClientPrefs"
 * in Unity's PlayerPrefs file (URL-encoded). The game has only High, Medium and Low, which differ in
 * antialiasing and anisotropic filtering; its effects are separate switches, so Very low is Low with
 * them off. A profile is written once, before a launch: what the player changes in game afterwards
 * stays, and the profile's button in the settings comes back.
 *
 * With no ClientPrefs the game makes its own from the defaults of its Steam Deck branch (Medium, every
 * effect on), so a fresh install starts on a profile by writing the file first. The game fills what
 * the JSON lacks with its own defaults, except the language: that one has to be there.
 */
public final class GameGraphics {
    private static final String TAG = "StormDroid/GameGraphics";

    private static final Pattern CLIENT_PREFS = Pattern.compile("(<string name=\"ClientPrefs\">)([^<]*)(</string>)");
    private static final Pattern UNITY_QUALITY = Pattern.compile("(name=\"UnityGraphicsQuality\" value=\")\\d+(\")");

    /** Key order as in {@link #values}. "deapthOfField" is the game's own spelling. */
    private static final String[] KEYS = {
            "graphicsLevel", "vSync", "maxFPS", "complexEffects", "shadowsFadeDistance", "screenFog",
            "motionBlur", "deapthOfField", "chromaticAberration", "lightningFlashes",
    };
    private static final String[] LOW = {
            "4", "0", "0", "true", "30.0", "true", "true", "true", "true", "true",
    };
    private static final String[] VERY_LOW = {
            "4", "0", "0", "false", "15.0", "false", "false", "false", "false", "false",
    };
    /** Unity's index of the game's Low: the list is High, Medium, Low, Rec. */
    private static final int UNITY_QUALITY_LOW = 2;

    private GameGraphics() {}

    private static String[] values(int preset) {
        return preset == InstanceSettings.GFX_VERY_LOW ? VERY_LOW : LOW;
    }

    /** True once the game (or a profile) has saved settings: this install is not a fresh one. */
    public static boolean hasSavedSettings(Context ctx) {
        try {
            return clientPrefs(read(ctx)) != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** The profile the game's settings match exactly, GFX_KEEP when none does. */
    public static int detect(Context ctx) {
        try {
            String json = clientPrefs(read(ctx));
            if (json == null) return InstanceSettings.GFX_KEEP;
            if (matches(json, VERY_LOW)) return InstanceSettings.GFX_VERY_LOW;
            if (matches(json, LOW)) return InstanceSettings.GFX_LOW;
        } catch (Exception e) {
            Log.w(TAG, "game settings not read: " + e);
        }
        return InstanceSettings.GFX_KEEP;
    }

    private static boolean matches(String json, String[] values) {
        for (int i = 0; i < KEYS.length; i++) {
            Matcher m = keyPattern(KEYS[i]).matcher(json);
            if (!m.find() || !m.group(2).trim().equals(values[i])) return false;
        }
        return true;
    }

    /** Write the profile into the game's settings. A failure leaves the game on what it had. */
    public static void apply(Context ctx, int preset) {
        if (preset == InstanceSettings.GFX_KEEP) return;
        try {
            String xml = read(ctx);
            String json = clientPrefs(xml);
            json = json != null ? update(json, values(preset)) : fresh(values(preset));
            write(GameDataTransfer.prefsFile(ctx), withClientPrefs(xml, json));
            Log.i(TAG, "graphics profile " + preset + " written");
        } catch (Exception e) {
            Log.w(TAG, "graphics profile not written", e);
        }
    }

    private static Pattern keyPattern(String key) {
        return Pattern.compile("(\"" + key + "\":\\s*)([^,\\n}]+)");
    }

    private static String update(String json, String[] values) {
        for (int i = 0; i < KEYS.length; i++)
            json = keyPattern(KEYS[i]).matcher(json).replaceFirst("$1" + Matcher.quoteReplacement(values[i]));
        return json;
    }

    private static String fresh(String[] values) {
        StringBuilder json = new StringBuilder("{\n  \"language\": \"").append(gameLanguage()).append('"');
        for (int i = 0; i < KEYS.length; i++)
            json.append(",\n  \"").append(KEYS[i]).append("\": ").append(values[i]);
        return json.append("\n}").toString();
    }

    /** The phone's language as the game names it (LocaConfig); English when the game lacks it. */
    private static String gameLanguage() {
        Locale l = Locale.getDefault();
        String lang = l.getLanguage();
        switch (lang) {
            case "de": case "fr": case "es": case "pt": case "it": case "ko": case "ja":
            case "th": case "cs": case "pl": case "ru":
                return lang;
            case "uk":
                return "ua";
            case "zh":
                String tag = l.toLanguageTag();
                return tag.contains("Hant") || tag.endsWith("TW") || tag.endsWith("HK") ? "zh-CT" : "zh-CN";
            default:
                return "en";
        }
    }

    private static String read(Context ctx) throws IOException {
        File f = GameDataTransfer.prefsFile(ctx);
        return f.isFile() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : null;
    }

    private static String clientPrefs(String xml) throws IOException {
        if (xml == null) return null;
        Matcher m = CLIENT_PREFS.matcher(xml);
        return m.find() ? URLDecoder.decode(m.group(2), "UTF-8") : null;
    }

    private static String withClientPrefs(String xml, String json) throws IOException {
        // Unity decodes these as a URL does: a space is %20, not URLEncoder's +.
        String encoded = URLEncoder.encode(json, "UTF-8").replace("+", "%20");
        if (xml == null)
            xml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n</map>\n";
        Matcher m = CLIENT_PREFS.matcher(xml);
        xml = m.find()
                ? m.replaceFirst("$1" + Matcher.quoteReplacement(encoded) + "$3")
                : xml.replace("</map>", "    <string name=\"ClientPrefs\">" + encoded + "</string>\n</map>");
        Matcher q = UNITY_QUALITY.matcher(xml);
        return q.find()
                ? q.replaceFirst("$1" + UNITY_QUALITY_LOW + "$2")
                : xml.replace("</map>", "    <int name=\"UnityGraphicsQuality\" value=\"" + UNITY_QUALITY_LOW + "\" />\n</map>");
    }

    private static void write(File f, String xml) throws IOException {
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("cannot create " + parent);
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(xml.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) throw new IOException("cannot replace " + f.getName());
    }
}

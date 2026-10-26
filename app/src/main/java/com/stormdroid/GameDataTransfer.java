package com.stormdroid;

import android.content.Context;

import com.stormdroid.game.AtsGame;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Backup / restore of Against the Storm's saves and settings, as a zip (ported from ValDroid).
 *
 * SAVES: the game's *.save files (Save, WorldSave, MetaSave, Profiles, CustomGamesLayout and their
 * _Backup copies), in the instance's user-data dir ({@link AtsGame#USER_DATA}). The PC keeps the same
 * files in %USERPROFILE%/AppData/LocalLow/Eremite Games/Against the Storm/, so a zip of that folder
 * imports here and an export unzips there. Export puts them at the top of the zip; import takes every
 * *.save file wherever it sits in the zip. The saves an import replaces are zipped next to the instance
 * first (saves_backup_<date>.zip).
 *
 * SETTINGS: the game's PlayerPrefs (graphics, audio, camera, keybinds, language), which Unity's Android
 * player keeps in the app's shared_prefs/<package>.v2.playerprefs.xml. Phone to phone only: the PC
 * version keeps them in the Windows registry. Not while the game runs (it writes the file on exit).
 */
public final class GameDataTransfer {

    public static final String SAVES = "saves";
    public static final String SETTINGS = "settings";

    private static final String SAVE_EXT = ".save";
    private static final String PREFS_ENTRY = "settings/playerprefs.xml";

    public static final class Result {
        public final List<String> items = new ArrayList<>();
        public String error;
        public long bytes;
        public int files;
        public boolean ok() { return error == null && !items.isEmpty(); }
    }

    private GameDataTransfer() {}

    public static File savesDir(File instanceDir) {
        return new File(instanceDir, AtsGame.USER_DATA);
    }

    public static File prefsFile(Context ctx) {
        return new File(ctx.getApplicationInfo().dataDir, "shared_prefs/" + ctx.getPackageName() + ".v2.playerprefs.xml");
    }

    /** Whether the game's process (Unity's player, ":unity") is running. */
    public static boolean gameRunning(Context ctx) {
        android.app.ActivityManager am = (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null || am.getRunningAppProcesses() == null) return false;
        for (android.app.ActivityManager.RunningAppProcessInfo p : am.getRunningAppProcesses())
            if (p.processName != null && p.processName.equals(ctx.getPackageName() + ":unity")) return true;
        return false;
    }

    // ---- export -----------------------------------------------------------------------------------

    public static Result export(Context ctx, File instanceDir, OutputStream rawOut, String part) {
        Result r = new Result();
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(rawOut))) {
            byte[] buf = new byte[1 << 16];
            if (SAVES.equals(part)) {
                for (File f : saveFiles(savesDir(instanceDir))) zipFile(f, f.getName(), zos, buf, r);
                if (r.files > 0) r.items.add(SAVES);
                else r.error = "No saves yet: play the game once so it writes them.";
            } else {
                File prefs = prefsFile(ctx);
                if (prefs.isFile()) {
                    zipFile(prefs, PREFS_ENTRY, zos, buf, r);
                    r.items.add(SETTINGS);
                } else r.error = "No game settings yet: play the game once so it writes them.";
            }
        } catch (IOException e) {
            r.error = msg(e);
        }
        return r;
    }

    // ---- import -----------------------------------------------------------------------------------

    public static Result importZip(Context ctx, File zipFile, File instanceDir, String part) {
        Result r = new Result();
        try {
            if (SAVES.equals(part)) importSaves(zipFile, instanceDir, r);
            else importSettings(ctx, zipFile, r);
        } catch (IOException e) {
            r.error = msg(e);
        }
        return r;
    }

    private static void importSaves(File zipFile, File instanceDir, Result r) throws IOException {
        // Only the file names count: a zip of the PC folder (with or without its parent folders) works.
        List<String> names = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String n = baseName(e.getName());
                if (!e.isDirectory() && isSaveName(n) && !names.contains(n)) names.add(n);
                zis.closeEntry();
            }
        }
        if (names.isEmpty()) {
            r.error = "No Against the Storm saves (*.save files) in this zip.";
            return;
        }
        File dir = savesDir(instanceDir);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        backupSaves(dir, instanceDir);
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            byte[] buf = new byte[1 << 16];
            ZipEntry e;
            List<String> done = new ArrayList<>();
            while ((e = zis.getNextEntry()) != null) {
                String n = baseName(e.getName());
                if (e.isDirectory() || !isSaveName(n) || done.contains(n)) {
                    zis.closeEntry();
                    continue;
                }
                File tmp = new File(dir, n + ".import");
                try (OutputStream os = new BufferedOutputStream(new FileOutputStream(tmp))) {
                    int k;
                    while ((k = zis.read(buf)) > 0) {
                        os.write(buf, 0, k);
                        r.bytes += k;
                    }
                }
                File dst = new File(dir, n);
                if (dst.exists() && !dst.delete()) throw new IOException("cannot replace " + n);
                if (!tmp.renameTo(dst)) throw new IOException("cannot write " + n);
                done.add(n);
                r.files++;
                zis.closeEntry();
            }
        }
        r.items.add(SAVES);
    }

    private static void importSettings(Context ctx, File zipFile, Result r) throws IOException {
        if (gameRunning(ctx)) {
            r.error = "Close the game first: it writes its settings when it exits.";
            return;
        }
        byte[] xml = null;
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (!e.isDirectory() && e.getName().replace('\\', '/').endsWith(PREFS_ENTRY)) xml = readAll(zis);
                zis.closeEntry();
            }
        }
        if (xml == null || !new String(xml, StandardCharsets.UTF_8).contains("<map")) {
            r.error = "No StormDroid game settings in this zip.";
            return;
        }
        File prefs = prefsFile(ctx);
        File dir = prefs.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File tmp = new File(prefs.getPath() + ".import");
        try (OutputStream os = new FileOutputStream(tmp)) {
            os.write(xml);
        }
        if (prefs.exists() && !prefs.delete()) throw new IOException("cannot replace the settings");
        if (!tmp.renameTo(prefs)) throw new IOException("cannot write the settings");
        r.bytes = xml.length;
        r.files = 1;
        r.items.add(SETTINGS);
    }

    /** The current saves, zipped next to the instance before an import replaces them. */
    private static void backupSaves(File dir, File instanceDir) throws IOException {
        List<File> files = saveFiles(dir);
        if (files.isEmpty()) return;
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(new java.util.Date());
        File out = new File(instanceDir, "saves_backup_" + stamp + ".zip");
        Result ignored = new Result();
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out)))) {
            byte[] buf = new byte[1 << 16];
            for (File f : files) zipFile(f, f.getName(), zos, buf, ignored);
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private static List<File> saveFiles(File dir) {
        List<File> out = new ArrayList<>();
        File[] kids = dir.listFiles();
        if (kids != null) for (File f : kids) if (f.isFile() && isSaveName(f.getName())) out.add(f);
        out.sort((a, b) -> a.getName().compareTo(b.getName()));
        return out;
    }

    private static boolean isSaveName(String n) {
        return n.toLowerCase(java.util.Locale.ROOT).endsWith(SAVE_EXT) && !n.startsWith(".")
                && n.indexOf('/') < 0 && n.indexOf('\\') < 0;
    }

    private static String baseName(String entry) {
        String n = entry.replace('\\', '/');
        int s = n.lastIndexOf('/');
        return s >= 0 ? n.substring(s + 1) : n;
    }

    private static void zipFile(File f, String name, ZipOutputStream zos, byte[] buf, Result r) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
            int n;
            while ((n = in.read(buf)) > 0) {
                zos.write(buf, 0, n);
                r.bytes += n;
            }
        }
        zos.closeEntry();
        r.files++;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static String msg(Exception e) {
        return e.getMessage() != null ? e.getMessage() : "I/O error";
    }
}

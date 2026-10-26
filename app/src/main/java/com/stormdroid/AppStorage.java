package com.stormdroid;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;

public class AppStorage {
    private final String HOME_DIR_PATH;
    private final String CACHE_DIR_PATH;
    private final String LIBRARY_DIR_PATH;
    private final Context appContext;
    private static AppStorage singleton;

    private AppStorage(Context applicationContext) {
        HOME_DIR_PATH    = applicationContext.getFilesDir().getAbsolutePath();
        CACHE_DIR_PATH   = applicationContext.getCacheDir().getAbsolutePath();
        LIBRARY_DIR_PATH = applicationContext.getApplicationInfo().nativeLibraryDir;
        appContext       = applicationContext;
    }

    public static void init(Context applicationContext) {
        singleton = new AppStorage(applicationContext);
    }

    @Nullable
    public static AppStorage getSingleton() {
        return singleton;
    }

    @NonNull
    public static AppStorage requireSingleton() {
        if (singleton == null) throw new RuntimeException("AppStorage is not initialized");
        return singleton;
    }

    /** /data/data/com.stormdroid/files */
    public String getHomePath() { return HOME_DIR_PATH; }

    /** /data/data/com.stormdroid/cache */
    public String getCachePath() { return CACHE_DIR_PATH; }

    /** Native .so libs dir (ARM64, installed by APK) */
    public String getLibraryPath() { return LIBRARY_DIR_PATH; }

    public File getInstancesDir() {
        return new File(HOME_DIR_PATH, "instances");
    }

    public File getInstanceDir(String name) {
        return new File(getInstancesDir(), name);
    }

    /** Public /Download/StormDroid: the games downloaded from Steam (needs All-files access, StorageAccess). */
    public File getDownloadsDir() {
        return new File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), "StormDroid");
    }

    /**
     * Files written into Downloads through plain paths are unknown to the media index, and the system
     * file picker lists only what the index knows (ValDroid, 2026-09-21). Asynchronous, best-effort.
     */
    public void publishToMediaIndex(File f, String mimeType) {
        if (f == null || !f.isFile()) return;
        android.media.MediaScannerConnection.scanFile(appContext,
                new String[]{ f.getAbsolutePath() }, new String[]{ mimeType }, null);
    }

    /** Short default instance name. */
    public static final String DEFAULT_INSTANCE_NAME = "AgainstTheStorm";

    /** "AgainstTheStorm", or "AgainstTheStorm2"/"AgainstTheStorm3"/... — the first name with no existing
     *  instance directory. Short on purpose: the name is part of every game path (see NewInstanceFragment). */
    public static String freeDefaultInstanceName() {
        AppStorage st = requireSingleton();
        if (!st.getInstanceDir(DEFAULT_INSTANCE_NAME).exists()) return DEFAULT_INSTANCE_NAME;
        for (int i = 2; i < 1000; i++) {
            String n = DEFAULT_INSTANCE_NAME + i;
            if (!st.getInstanceDir(n).exists()) return n;
        }
        return DEFAULT_INSTANCE_NAME;   // 1000 instances of the default name — practically unreachable
    }
}

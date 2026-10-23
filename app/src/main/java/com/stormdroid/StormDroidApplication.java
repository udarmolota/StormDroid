package com.stormdroid;

import android.app.Application;
import android.util.Log;

public class StormDroidApplication extends Application {

    /** Name of the file the crash logger appends uncaught stack traces to (in getFilesDir()). */
    public static final String CRASH_LOG = "crash_uncaught.log";

    /** App context for code paths that have no Context handy. */
    public static Application APP;

    @Override
    public void onCreate() {
        super.onCreate();
        APP = this;
        installCrashLogger();          // FIRST — so even early-startup crashes get recorded
        AppStorage.init(this);
        LauncherPreferences.init(this);
        // Apply the user's theme choice (System / Light / Dark) before any activity is shown.
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                LauncherPreferences.requireSingleton().getThemeMode());
    }

    /**
     * Record EVERY uncaught exception (on ANY thread) to a file before the process dies.
     *
     * An uncaught exception hard-crashes the app and nothing lands in our own logs, so a regular user
     * (not a tester) can't tell us what happened. This handler appends the full
     * stack + thread name to {@link #CRASH_LOG} in the app's private files dir (picked up by
     * Settings → "Export logs"), then chains to the platform's default handler so crash behaviour is
     * otherwise unchanged. Best-effort: any failure writing the file is swallowed.
     */
    private void installCrashLogger() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        final java.io.File logFile = new java.io.File(getFilesDir(), CRASH_LOG);
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try (java.io.PrintWriter w =
                         new java.io.PrintWriter(new java.io.FileWriter(logFile, /* append */ true))) {
                w.println("=== UNCAUGHT " + new java.util.Date() + "  thread='" + thread.getName() + "' ===");
                ex.printStackTrace(w);
                w.println();
            } catch (Throwable ignored) { /* never make crash-logging itself crash */ }
            Log.e("StormDroid", "Uncaught exception on thread '" + thread.getName() + "'", ex);
            if (prev != null) prev.uncaughtException(thread, ex);   // keep default crash behaviour
        });
    }
}

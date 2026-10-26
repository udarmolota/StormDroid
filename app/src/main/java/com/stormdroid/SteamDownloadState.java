package com.stormdroid;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CompletableFuture;

/**
 * Process-wide state for an in-progress Steam download (ported from ValDroid). The download runs on a
 * background thread that outlives the DownloadFragment, so this singleton holds the log, the progress
 * and the running flag and re-attaches whichever {@link View} (fragment) is on screen: the player can
 * leave the download screen and come back without losing them, and there is one place to cancel.
 * It is the {@link SteamDownloader.Listener} passed to the downloader.
 */
public final class SteamDownloadState implements SteamDownloader.Listener {

    private static final SteamDownloadState INSTANCE = new SteamDownloadState();
    public static SteamDownloadState get() { return INSTANCE; }
    private SteamDownloadState() {}

    /** The currently-attached UI (fragment). Null when the screen isn't shown. */
    public interface View {
        void onLog(CharSequence fullLog);
        void onPercent(int percent, boolean indeterminate);
        void onFinished(String message);
        CompletableFuture<String> requestSteamGuardCode(boolean previousWrong, String email);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final StringBuilder log = new StringBuilder();
    private volatile boolean downloading;
    private volatile int percent = -1;
    private volatile boolean indeterminate = true;
    private View view;
    private Context appCtx;
    private volatile Cancellable active;
    private volatile boolean cancelling;
    /** Completion must survive a detached/recreated DownloadFragment. */
    private String pendingFinishedMessage;
    /** The archive the last download produced (this app run). */
    private volatile java.io.File gameZip;

    public boolean isDownloading() { return downloading; }
    public boolean isCancelling() { return cancelling; }
    public CharSequence getLog() { synchronized (log) { return log.toString(); } }
    public int getPercent() { return percent; }
    public boolean isIndeterminate() { return indeterminate; }

    /**
     * The game archive to create an instance from: the one this run downloaded, else the newest
     * AgainstTheStorm_*.zip already in /Download/StormDroid (a download finished in an earlier run).
     */
    public java.io.File getGameZip() {
        java.io.File z = gameZip;
        if (z != null && z.isFile()) return z;
        java.io.File[] all = AppStorage.requireSingleton().getDownloadsDir().listFiles(
                (d, n) -> n.startsWith("AgainstTheStorm_") && n.endsWith(".zip"));
        java.io.File best = null;
        if (all != null) for (java.io.File f : all) if (best == null || f.lastModified() > best.lastModified()) best = f;
        return best;
    }

    @Override
    public void onGameZip(java.io.File zip) { gameZip = zip; }

    /** Register the running downloader so the user can cancel it. */
    public void setActive(Cancellable c) { active = c; cancelling = false; }

    /** Stop the running download: the downloader flags itself to bail and interrupts its blocking I/O. */
    public void cancel() {
        if (!downloading) return;
        cancelling = true;
        appendLine("Cancelling…");
        main.post(() -> { if (view != null) view.onLog(getLog()); });
        Cancellable c = active;
        if (c != null) c.cancel();
    }

    /** Called on the main thread by the fragment when it (re)appears or goes away. */
    public synchronized void setView(View v) {
        this.view = v;
        if (pendingFinishedMessage != null) main.post(this::deliverPendingFinished);
    }
    public synchronized void clearView(View v) { if (this.view == v) this.view = null; }

    /** Start a new download session: clears the log and raises the keep-alive service. */
    public void begin(Context context) {
        appCtx = context.getApplicationContext();
        synchronized (log) { log.setLength(0); }
        downloading = true;
        indeterminate = true;
        percent = -1;
        synchronized (this) { pendingFinishedMessage = null; }
        DownloadKeepAliveService.start(appCtx);
    }

    private void appendLine(String line) {
        synchronized (log) {
            log.append(line).append('\n');
            if (log.length() > 40000) log.delete(0, log.length() - 30000);
        }
    }

    // ---- downloader callbacks (background threads) ----
    @Override
    public void onProgress(String message) {
        appendLine(message);
        main.post(() -> { if (view != null) view.onLog(getLog()); });
    }

    @Override
    public void onPercent(int p) {
        percent = Math.max(0, Math.min(100, p));
        indeterminate = false;
        main.post(() -> { if (view != null) view.onPercent(percent, false); });
    }

    @Override
    public void onDone(String message) {
        appendLine((isError(message) ? "✗ " : "✓ ") + message);
        downloading = false;
        cancelling = false;
        active = null;
        synchronized (this) { pendingFinishedMessage = message; }
        if (appCtx != null) DownloadKeepAliveService.stop(appCtx);
        main.post(this::deliverPendingFinished);
    }

    /** Deliver completion exactly once, now or when the download screen is attached again. */
    private void deliverPendingFinished() {
        final View target;
        final String message;
        synchronized (this) {
            target = view;
            message = pendingFinishedMessage;
            if (target == null || message == null) return;
            pendingFinishedMessage = null;
        }
        target.onLog(getLog());
        target.onFinished(message);
    }

    @Override
    public CompletableFuture<String> requestSteamGuardCode(boolean previousWrong, String email) {
        View v = this.view;
        if (v != null) return v.requestSteamGuardCode(previousWrong, email);
        return CompletableFuture.completedFuture("");
    }

    public static boolean isError(String m) {
        if (m == null) return true;
        String s = m.toLowerCase();
        return s.contains("error") || s.contains("fail") || s.contains("crash")
                || s.contains("lost") || s.contains("cannot") || s.contains("could not")
                || s.contains("cancel") || s.contains("incomplete");
    }
}

package com.stormdroid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import com.stormdroid.game.AtsGame;
import com.stormdroid.game.GameInstance;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class InstallerService extends Service {

    private static final String TAG      = "StormDroid/Installer";
    private static final int    NOTIF_ID = 1;
    private static final String CHANNEL  = "stormdroid_install";

    // Task identifiers
    public static final String TASK_INSTALL_INSTANCE = "INSTALL_INSTANCE";

    // Intent extras
    public static final String EXTRA_TASK          = "task";
    public static final String EXTRA_ZIP_PATH      = "zip_path";
    public static final String EXTRA_INSTANCE_NAME = "instance_name";

    // Broadcasts
    public static final String BROADCAST_PROGRESS = "com.stormdroid.INSTALL_PROGRESS";
    public static final String BROADCAST_DONE     = "com.stormdroid.INSTALL_DONE";
    public static final String BROADCAST_ERROR    = "com.stormdroid.INSTALL_ERROR";
    public static final String EXTRA_MESSAGE      = "message";
    public static final String EXTRA_SUCCESS      = "success";
    /** Progress broadcasts: 0..100, or -1 when the step has no measurable progress. */
    public static final String EXTRA_PERCENT      = "percent";

    private AppStorage storage;
    private LauncherPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());

    /** Screen-independent completion feedback (works regardless of which screen is open). */
    private void toast(String msg) {
        ui.post(() -> Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        storage = AppStorage.requireSingleton();
        prefs   = LauncherPreferences.requireSingleton();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String task = intent.getStringExtra(EXTRA_TASK);
        if (task == null) return START_NOT_STICKY;

        startForeground(NOTIF_ID, buildNotification("Installing..."));

        new Thread(() -> {
            try {
                switch (task) {
                    case TASK_INSTALL_INSTANCE: {
                        String zipPath      = intent.getStringExtra(EXTRA_ZIP_PATH);
                        String instanceName = intent.getStringExtra(EXTRA_INSTANCE_NAME);
                        try {
                            installInstance(zipPath, instanceName);
                        } finally {
                            deleteIfCacheCopy(zipPath);
                        }
                        break;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Install failed", e);
                broadcastError(e.getMessage());
            } finally {
                stopForeground(true);
                stopSelf(startId);
            }
        }).start();

        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    /**
     * The picked archive's copy in the app cache (NewInstanceFragment) is ours and 4-5 GB nobody needs once
     * the install is over, whatever its outcome. An archive elsewhere (the Steam download) is the user's.
     */
    private void deleteIfCacheCopy(String zipPath) {
        if (zipPath == null) return;
        File zip = new File(zipPath);
        if (getCacheDir().equals(zip.getParentFile()) && zip.isFile() && !zip.delete())
            Log.w(TAG, "cannot delete " + zip);
    }

    // =========================================================================
    // INSTALL INSTANCE FROM ZIP
    // =========================================================================

    private void installInstance(String zipPath, String instanceName) throws Exception {
        if (zipPath == null)      throw new Exception("No zip path");
        if (instanceName == null || instanceName.isEmpty()) throw new Exception("No instance name");

        File zipFile = new File(zipPath);
        if (!zipFile.exists()) throw new Exception("Zip not found: " + zipPath);

        File instanceDir = storage.getInstanceDir(instanceName);
        // NEVER overwrite an existing instance: that silently wipes the user's data when a new instance
        // is given a name that already exists. The user must delete the old instance first.
        File[] existing = instanceDir.exists() ? instanceDir.listFiles() : null;
        if (existing != null && existing.length > 0) {
            throw new Exception("Instance '" + instanceName + "' already exists — pick another name "
                    + "(or delete it first).");
        }

        // Validate the archive BEFORE creating any folders: it must contain the game's executable
        // somewhere, or we would leave an orphaned instance folder the launcher hides (isInstalled()).
        broadcastProgress("Checking archive...");
        if (!zipContainsEntry(zipFile, AtsGame.EXE)) {
            throw new Exception(AtsGame.EXE + " not found in the archive — this doesn't look like "
                    + "Against the Storm's Windows build. Nothing was installed.");
        }
        instanceDir.mkdirs();
        broadcastProgress("Extracting instance...");
        extractZip(zipFile, instanceDir);

        // Re-root: the game files may sit inside a wrapper folder at any depth. Find the executable and
        // lift its folder's contents to the instance top, dropping the wrappers, so isInstalled() (which
        // checks the root) passes. If it is missing after extraction, delete the whole instance dir.
        File exe = new File(instanceDir, AtsGame.EXE);
        if (!exe.exists()) {
            File found = findFile(instanceDir, AtsGame.EXE);
            if (found == null) {
                deleteDir(instanceDir);
                throw new Exception(AtsGame.EXE + " not found after extraction — nothing was installed.");
            }
            File gameRoot = found.getParentFile();
            if (gameRoot != null && !gameRoot.equals(instanceDir)) {
                broadcastProgress("Flattening directory structure...");
                reRoot(gameRoot, instanceDir);
            }
        }

        prefs.setLastInstanceName(instanceName);

        // Completeness gate: warn HERE with the exact missing files, so the user knows it's their copy of
        // the game, not the app. The instance is left in place but we do NOT claim success.
        java.util.List<String> missing = new GameInstance(instanceName).missingCoreFiles();
        if (!missing.isEmpty()) {
            broadcastDone(false, "Instance '" + instanceName + "' is INCOMPLETE — this copy of the game "
                    + "is missing: " + String.join(", ", missing) + ". Use a complete copy of the game.");
            return;
        }

        // The game's Direct3D 11 shaders are converted for Vulkan before the first launch, not here
        // (NativeEngine.prepare -> ShaderPrep): installing only puts the files in place.
        broadcastProgress("Instance installed.");
        broadcastDone(true, "Instance '" + instanceName + "' installed — ready to launch");
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    /**
     * Unpack the archive, reporting the share of the uncompressed size written so far (the game is
     * several GB: minutes on a phone, which without a number looked like a hang).
     */
    private void extractZip(File zipFile, File destDir) throws IOException {
        destDir.mkdirs();
        long total = 0;
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zipFile)) {
            java.util.Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                long sz = en.nextElement().getSize();
                if (sz > 0) total += sz;
            }
        }
        long written = 0;
        int lastPct = -1;
        long lastTime = 0;
        try (ZipInputStream zis = new ZipInputStream(new java.io.BufferedInputStream(new FileInputStream(zipFile), 1 << 16))) {
            ZipEntry entry;
            byte[] buf = new byte[1 << 16];
            while ((entry = zis.getNextEntry()) != null) {
                File out = new File(destDir, entry.getName());
                if (entry.isDirectory()) {
                    out.mkdirs();
                } else {
                    out.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        int len;
                        while ((len = zis.read(buf)) != -1) {
                            fos.write(buf, 0, len);
                            written += len;
                            int pct = total > 0 ? (int) Math.min(100, written * 100 / total) : -1;
                            long now = System.currentTimeMillis();
                            if (pct != lastPct && now - lastTime > 300) {
                                lastPct = pct;
                                lastTime = now;
                                String msg = getString(R.string.install_extracting, pct, written / (1024 * 1024), total / (1024 * 1024));
                                broadcastProgress(msg, pct);
                                updateNotification(msg, pct);
                            }
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    /** True if the archive contains an entry whose file name (last path segment) equals {@code fileName}.
     *  Uses ZipFile (reads the central directory directly) so it's instant even for a large game zip. */
    private boolean zipContainsEntry(File zipFile, String fileName) throws IOException {
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(zipFile)) {
            java.util.Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                int slash = n.lastIndexOf('/');
                String base = (slash >= 0) ? n.substring(slash + 1) : n;
                if (base.equals(fileName)) return true;
            }
        }
        return false;
    }

    /**
     * Move every child of {@code gameRoot} up to {@code instanceDir}, then remove the now-empty wrapper
     * folders between them. {@code gameRoot} must be a descendant of {@code instanceDir}. This lifts a
     * nested game ("instance/game/Against the Storm.exe") to the instance root so isInstalled() passes.
     */
    private void reRoot(File gameRoot, File instanceDir) throws IOException {
        // The top wrapper folder can share a name with something inside the game, which stopped with
        // "Name clash". The wrapper goes out of the way under a temporary name first.
        File top = gameRoot;
        while (top.getParentFile() != null && !top.getParentFile().equals(instanceDir)) top = top.getParentFile();
        if (instanceDir.equals(top.getParentFile())) {
            File moved = new File(instanceDir, ".wrapper_" + System.currentTimeMillis());
            if (top.renameTo(moved)) {
                String rest = gameRoot.getPath().substring(top.getPath().length());
                gameRoot = new File(moved.getPath() + rest);
            }
        }
        File[] kids = gameRoot.listFiles();
        if (kids != null) {
            for (File k : kids) {
                File target = new File(instanceDir, k.getName());
                if (target.exists())
                    throw new IOException("Name clash while flattening: " + k.getName());
                if (!k.renameTo(target))
                    throw new IOException("Could not move " + k.getName() + " to the instance root");
            }
        }
        // Delete the wrapper chain from gameRoot up to (but not including) instanceDir.
        File p = gameRoot;
        while (p != null && !p.equals(instanceDir)) {
            File parent = p.getParentFile();
            deleteDir(p);
            p = parent;
        }
    }

    private File findFile(File dir, String name) {
        if (!dir.isDirectory()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isFile() && f.getName().equals(name)) return f;
            if (f.isDirectory()) {
                File found = findFile(f, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void deleteDir(File dir) {
        if (dir == null) return;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            if (f.isDirectory()) deleteDir(f);
            else f.delete();
        }
        dir.delete();
    }

    // =========================================================================
    // BROADCASTS
    // =========================================================================

    private void broadcastProgress(String msg) {
        broadcastProgress(msg, -1);
    }

    private void broadcastProgress(String msg, int percent) {
        if (percent < 0) Log.d(TAG, msg);
        Intent i = new Intent(BROADCAST_PROGRESS);
        i.setPackage(getPackageName());   // explicit target so RECEIVER_NOT_EXPORTED receivers get it
        i.putExtra(EXTRA_MESSAGE, msg);
        i.putExtra(EXTRA_PERCENT, percent);
        sendBroadcast(i);
    }

    private void broadcastDone(boolean success, String msg) {
        Log.i(TAG, msg);
        toast("✓ " + msg);   // ✓ — always visible, even on other screens
        Intent i = new Intent(BROADCAST_DONE);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_SUCCESS, success);
        i.putExtra(EXTRA_MESSAGE, msg);
        sendBroadcast(i);
    }

    private void broadcastError(String msg) {
        Log.e(TAG, "ERROR: " + msg);
        toast("Install failed: " + (msg != null ? msg : "unknown error"));
        Intent i = new Intent(BROADCAST_ERROR);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_MESSAGE, msg);
        sendBroadcast(i);
    }

    // =========================================================================
    // NOTIFICATION
    // =========================================================================

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL, "Installation", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        return buildNotification(text, -1);
    }

    /** percent 0..100 shows a progress bar in the notification, -1 none. */
    private Notification buildNotification(String text, int percent) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL)
                .setContentTitle("StormDroid")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setOnlyAlertOnce(true)
                .setOngoing(true);
        if (percent >= 0) b.setProgress(100, percent, false);
        return b.build();
    }

    private void updateNotification(String text, int percent) {
        getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification(text, percent));
    }

    // =========================================================================
    // STATIC STARTERS
    // =========================================================================

    public static void startInstallInstance(Context ctx, String zipPath, String instanceName) {
        Intent i = new Intent(ctx, InstallerService.class);
        i.putExtra(EXTRA_TASK, TASK_INSTALL_INSTANCE);
        i.putExtra(EXTRA_ZIP_PATH, zipPath);
        i.putExtra(EXTRA_INSTANCE_NAME, instanceName);
        ctx.startForegroundService(i);
    }
}

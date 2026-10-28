package com.stormdroid;

import com.stormdroid.game.GameInstance;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Collects an instance's diagnostic logs into a single zip for sharing/support: the game process's own
 * log (game.NativeLog, this run and the one before, with the launch config at the top), the app's
 * uncaught-crash log, recent logcat, and exit_info.txt — the system's record of WHY our previous
 * processes died (ANR / native crash / LMK / user swipe), with the stored ANR thread dump when one
 * exists. Missing files are skipped silently.
 */
public final class LogExporter {

    /**
     * A long game log ships as its start and its end. The start has the launch config, the GPU, its
     * driver and texture formats, and whether BC textures are converted (vkshim); the end has the
     * crash. A driver repeating one error can fill the middle within a minute.
     */
    private static final long NATIVE_LOG_HEAD_BYTES = 1L * 1024 * 1024;
    private static final long NATIVE_LOG_TAIL_BYTES = 2L * 1024 * 1024;

    private LogExporter() {}

    public static final class Result {
        public final List<String> items = new ArrayList<>();
        public long bytes;
        public String error;
        public boolean ok() { return error == null && !items.isEmpty(); }
    }

    public static Result export(android.content.Context ctx, GameInstance gi, OutputStream rawOut) {
        Result r = new Result();
        // No instance (the install itself failed): the launcher's own logs still go.
        File gamePath = gi != null ? new File(gi.getGamePath()) : null;

        // Global (not instance-scoped) uncaught-crash log. Lives in the app's private files dir.
        File crashLog = new File(AppStorage.requireSingleton().getHomePath(),
                StormDroidApplication.CRASH_LOG);

        File[] candidates = {
                gamePath != null ? new File(gamePath, com.stormdroid.game.NativeLog.FILE) : null,
                gamePath != null ? new File(gamePath, com.stormdroid.game.NativeLog.PREV) : null,
                // the shader conversion's report (ShaderPrep)
                gamePath != null ? new File(gamePath, "native_engine/shaderdroid_report.json") : null,
                crashLog,
        };

        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(rawOut))) {
            byte[] buf = new byte[65536];
            for (File f : candidates) {
                if (f == null || !f.isFile()) continue;
                zos.putNextEntry(new ZipEntry(f.getName()));
                try (FileInputStream in = new FileInputStream(f)) {
                    boolean gameLog = f.getName().startsWith("native_engine") && f.getName().endsWith(".log");
                    long length = f.length();
                    if (gameLog && length > NATIVE_LOG_HEAD_BYTES + NATIVE_LOG_TAIL_BYTES) {
                        r.bytes += copy(in, zos, buf, NATIVE_LOG_HEAD_BYTES);
                        long skip = length - NATIVE_LOG_HEAD_BYTES - NATIVE_LOG_TAIL_BYTES;
                        byte[] mark = ("\n... [" + (skip >> 10) + " KB of the log skipped] ...\n")
                                .getBytes(StandardCharsets.UTF_8);
                        zos.write(mark);
                        while (skip > 0) {
                            long s = in.skip(skip);
                            if (s <= 0) break;
                            skip -= s;
                        }
                    }
                    r.bytes += copy(in, zos, buf, Long.MAX_VALUE);
                }
                zos.closeEntry();
                r.items.add(f.getName());
            }
            addLogcat(zos, buf, r);
            if (ctx != null) addExitInfo(ctx, zos, buf, r);
        } catch (Exception e) {
            r.error = e.getMessage();
            return r;
        }
        if (r.items.isEmpty()) r.error = "No logs found yet (run the game first).";
        return r;
    }

    /** Copies up to max bytes of in into zos; returns how many. */
    private static long copy(InputStream in, ZipOutputStream zos, byte[] buf, long max) throws java.io.IOException {
        long done = 0;
        int n;
        while (done < max && (n = in.read(buf, 0, (int) Math.min(buf.length, max - done))) > 0) {
            zos.write(buf, 0, n);
            done += n;
        }
        return done;
    }

    /**
     * Adds the system's ApplicationExitInfo history (API 30+ == our minSdk): timestamp, process,
     * decoded reason (ANR / CRASH_NATIVE / LOW_MEMORY / USER_REQUESTED / ...), signal, and memory
     * at death for the last dozen deaths of our package's processes. For entries where the system
     * stored a trace (ANR thread dumps, some native tombstones) the trace is appended, capped, so
     * a "game froze then closed" report carries the actual stack of the hang.
     * Capture failure is recorded inside the entry and never blocks the rest of the export.
     */
    private static void addExitInfo(android.content.Context ctx, ZipOutputStream zos, byte[] buf,
                                    Result r) throws java.io.IOException {
        final String name = "exit_info.txt";
        long written = 0;
        zos.putNextEntry(new ZipEntry(name));
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                    ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE);
            List<android.app.ApplicationExitInfo> exits =
                    am.getHistoricalProcessExitReasons(ctx.getPackageName(), 0, 12);
            java.text.SimpleDateFormat fmt =
                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US);
            StringBuilder sb = new StringBuilder(1024);
            sb.append("Process exit history (newest first) for ").append(ctx.getPackageName())
              .append(" — Android ").append(android.os.Build.VERSION.RELEASE)
              .append(" / API ").append(android.os.Build.VERSION.SDK_INT).append('\n').append('\n');
            if (exits.isEmpty()) sb.append("(none recorded)\n");
            for (android.app.ApplicationExitInfo e : exits) {
                sb.append(fmt.format(new java.util.Date(e.getTimestamp())))
                  .append("  proc=").append(e.getProcessName())
                  .append("  reason=").append(exitReasonName(e.getReason()))
                  .append("  status=").append(e.getStatus()).append(signalName(e))
                  .append("  pss=").append(e.getPss()).append("kB rss=").append(e.getRss())
                  .append("kB\n");
                String d = e.getDescription();
                if (d != null && !d.isEmpty()) sb.append("    desc: ").append(d).append('\n');
            }
            byte[] head = sb.toString().getBytes(StandardCharsets.UTF_8);
            zos.write(head);
            written += head.length;
            int traces = 0;
            for (android.app.ApplicationExitInfo e : exits) {
                if (traces >= 2) break;   // the two most recent stored traces are plenty
                try (InputStream in = e.getTraceInputStream()) {
                    if (in == null) continue;
                    byte[] hdr = ("\n===== stored trace: "
                            + fmt.format(new java.util.Date(e.getTimestamp())) + " "
                            + exitReasonName(e.getReason()) + " =====\n")
                            .getBytes(StandardCharsets.UTF_8);
                    zos.write(hdr);
                    written += hdr.length;
                    long cap = 262144;   // 256 kB per trace keeps the zip mailable
                    int n;
                    while ((n = in.read(buf)) > 0 && cap > 0) {
                        int w = (int)Math.min(n, cap);
                        zos.write(buf, 0, w);
                        written += w;
                        cap -= w;
                    }
                    traces++;
                } catch (Exception ignored) { /* per-entry trace is best-effort */ }
            }
        } catch (Exception e) {
            byte[] msg = ("exit-info capture failed: " + e + "\n")
                    .getBytes(StandardCharsets.UTF_8);
            zos.write(msg);
            written += msg.length;
        } finally {
            zos.closeEntry();
        }
        r.bytes += written;
        r.items.add(name);
    }

    private static String exitReasonName(int reason) {
        switch (reason) {
            case android.app.ApplicationExitInfo.REASON_ANR: return "ANR (hang)";
            case android.app.ApplicationExitInfo.REASON_CRASH: return "CRASH (java)";
            case android.app.ApplicationExitInfo.REASON_CRASH_NATIVE: return "CRASH_NATIVE";
            case android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "DEPENDENCY_DIED";
            case android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "EXCESSIVE_RESOURCE_USAGE";
            case android.app.ApplicationExitInfo.REASON_EXIT_SELF: return "EXIT_SELF";
            case android.app.ApplicationExitInfo.REASON_FREEZER: return "FREEZER";
            case android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "INITIALIZATION_FAILURE";
            case android.app.ApplicationExitInfo.REASON_LOW_MEMORY: return "LOW_MEMORY (LMK)";
            case android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "PERMISSION_CHANGE";
            case android.app.ApplicationExitInfo.REASON_SIGNALED: return "SIGNALED";
            case android.app.ApplicationExitInfo.REASON_USER_REQUESTED: return "USER_REQUESTED (swipe/force-stop)";
            case android.app.ApplicationExitInfo.REASON_USER_STOPPED: return "USER_STOPPED";
            case android.app.ApplicationExitInfo.REASON_OTHER: return "OTHER";
            default: return "UNKNOWN(" + reason + ")";
        }
    }

    /** Human name for the kill signal, appended after the raw status where it applies. */
    private static String signalName(android.app.ApplicationExitInfo e) {
        if (e.getReason() != android.app.ApplicationExitInfo.REASON_SIGNALED
                && e.getReason() != android.app.ApplicationExitInfo.REASON_CRASH_NATIVE) return "";
        switch (e.getStatus()) {
            case 3:  return " (SIGQUIT)";
            case 6:  return " (SIGABRT)";
            case 9:  return " (SIGKILL)";
            case 11: return " (SIGSEGV)";
            default: return "";
        }
    }

    /**
     * Adds recent logcat lines visible to this app UID. Android normally hides other apps' logs,
     * but Java and native output from StormDroid remains available. A capture failure is
     * recorded inside the entry instead of preventing the regular log files from being exported.
     */
    private static void addLogcat(ZipOutputStream zos, byte[] buf, Result r)
            throws java.io.IOException {
        final String name = "logcat.txt";
        Process process = null;
        long written = 0;
        zos.putNextEntry(new ZipEntry(name));
        try {
            process = new ProcessBuilder(
                    "logcat", "-b", "all", "-d", "-v", "threadtime", "-t", "8000")
                    .redirectErrorStream(true)
                    .start();
            try (InputStream in = process.getInputStream()) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    zos.write(buf, 0, n);
                    written += n;
                }
            }
            int exitCode = process.waitFor();
            if (written == 0) {
                byte[] message = ("logcat returned no accessible entries (exit "
                        + exitCode + ").\n").getBytes(StandardCharsets.UTF_8);
                zos.write(message);
                written += message.length;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            byte[] message = "logcat capture interrupted.\n".getBytes(StandardCharsets.UTF_8);
            zos.write(message);
            written += message.length;
        } catch (Exception e) {
            byte[] message = ("logcat capture failed: " + e + "\n")
                    .getBytes(StandardCharsets.UTF_8);
            zos.write(message);
            written += message.length;
        } finally {
            if (process != null) process.destroy();
            zos.closeEntry();
        }
        r.bytes += written;
        r.items.add(name);
    }
}

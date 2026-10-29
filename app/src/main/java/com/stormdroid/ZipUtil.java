package com.stormdroid;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Minimal directory -> zip packer (ported from RimDroid): turns a game downloaded from Steam into the
 * archive in /Download/StormDroid that an instance is created from.
 */
public final class ZipUtil {

    private ZipUtil() {}

    /** Packing several GB takes minutes: the caller sees how far it got and can stop it. */
    public interface Progress {
        /** Bytes of file data packed so far, out of the total. */
        void onBytes(long done, long total);
        boolean isCancelled();
    }

    /** Thrown out of {@link #zipDir} once {@link Progress#isCancelled()} turns true. */
    public static final class CancelledException extends IOException {
        CancelledException() { super("packing cancelled"); }
    }

    /** Zip the entire contents of {@code srcDir} into {@code destZip} (entries relative to srcDir). */
    public static void zipDir(File srcDir, File destZip, Progress progress) throws IOException {
        File parent = destZip.getParentFile();
        if (parent != null) parent.mkdirs();
        long[] done = {0, sizeOf(srcDir)};
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(destZip), 1 << 20))) {
            // Several GB on a phone: the fastest compression level, which still shrinks the game a lot
            // (most of it is uncompressed Unity data) at a fraction of the default level's time.
            zos.setLevel(Deflater.BEST_SPEED);
            addEntries(srcDir, srcDir, zos, progress, done);
        }
    }

    private static boolean skipped(File f) {
        // The downloader's bookkeeping is not part of the game.
        return f.getName().equals(".DepotDownloader") || f.getName().startsWith(".stormdroid_complete_");
    }

    private static long sizeOf(File dir) {
        File[] kids = dir.listFiles();
        if (kids == null) return 0;
        long n = 0;
        for (File f : kids) {
            if (skipped(f)) continue;
            n += f.isDirectory() ? sizeOf(f) : f.length();
        }
        return n;
    }

    private static void addEntries(File root, File current, ZipOutputStream zos,
                                   Progress progress, long[] done) throws IOException {
        File[] kids = current.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (skipped(f)) continue;
            String rel = root.toURI().relativize(f.toURI()).getPath();  // forward-slash relative path
            if (f.isDirectory()) {
                if (!rel.endsWith("/")) rel += "/";
                zos.putNextEntry(new ZipEntry(rel));
                zos.closeEntry();
                addEntries(root, f, zos, progress, done);
            } else {
                zos.putNextEntry(new ZipEntry(rel));
                try (InputStream in = new FileInputStream(f)) {
                    copy(in, zos, progress, done);
                }
                zos.closeEntry();
            }
        }
    }

    private static void copy(InputStream in, OutputStream out, Progress progress, long[] done) throws IOException {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) != -1) {
            // Checked per buffer: one Unity data file alone can take a minute.
            if (progress.isCancelled()) throw new CancelledException();
            out.write(buf, 0, n);
            done[0] += n;
            progress.onBytes(done[0], done[1]);
        }
    }
}

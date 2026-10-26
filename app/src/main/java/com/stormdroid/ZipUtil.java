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

    /** Zip the entire contents of {@code srcDir} into {@code destZip} (entries relative to srcDir). */
    public static void zipDir(File srcDir, File destZip) throws IOException {
        File parent = destZip.getParentFile();
        if (parent != null) parent.mkdirs();
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(destZip), 1 << 20))) {
            // Several GB on a phone: the fastest compression level, which still shrinks the game a lot
            // (most of it is uncompressed Unity data) at a fraction of the default level's time.
            zos.setLevel(Deflater.BEST_SPEED);
            addEntries(srcDir, srcDir, zos);
        }
    }

    private static void addEntries(File root, File current, ZipOutputStream zos) throws IOException {
        File[] kids = current.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            // The downloader's bookkeeping is not part of the game.
            if (f.getName().equals(".DepotDownloader") || f.getName().startsWith(".stormdroid_complete_")) continue;
            String rel = root.toURI().relativize(f.toURI()).getPath();  // forward-slash relative path
            if (f.isDirectory()) {
                if (!rel.endsWith("/")) rel += "/";
                zos.putNextEntry(new ZipEntry(rel));
                zos.closeEntry();
                addEntries(root, f, zos);
            } else {
                zos.putNextEntry(new ZipEntry(rel));
                try (InputStream in = new FileInputStream(f)) {
                    copy(in, zos);
                }
                zos.closeEntry();
            }
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
    }
}

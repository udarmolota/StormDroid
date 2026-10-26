package com.stormdroid.game;

import android.util.Log;

import com.shaderdroid.ShaderDroid;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Retargets the game's Direct3D 11 shaders to Vulkan on the phone, once per instance, with ShaderDroid
 * (the closed converter library, com.shaderdroid.ShaderDroid). The data folder is converted in place
 * (Vulkan only: the D3D11 programs are dropped); a marker file then says the instance is done.
 * Already-retargeted files (a copy prepared on a PC) are left as they are and only get the marker.
 */
public final class ShaderPrep {
    private static final String TAG = "StormDroid/ShaderPrep";
    /** In the data folder: the ShaderDroid version that prepared it. */
    private static final String MARKER = ".shaderdroid";
    /** Room for the temporary copy of the largest file being replaced, plus a margin. */
    private static final long SPACE_MARGIN = 256L * 1024 * 1024;

    private ShaderPrep() {}

    public static boolean isPrepared(File dataDir) {
        return new File(dataDir, MARKER).isFile();
    }

    /**
     * Converts the data folder; progress lines go to {@code progress} (may be null). The report lands in
     * {@code workDir}/shaderdroid_report.json for bug reports.
     */
    public static void prepare(File dataDir, File workDir, ShaderDroid.Log progress) throws IOException {
        if (!ShaderDroid.available())
            throw new IOException("this build has no shader converter (libshaderdroid.so): the game's "
                    + "DirectX shaders cannot be converted for Vulkan");
        long need = largestFile(dataDir) + SPACE_MARGIN;
        long free = dataDir.getUsableSpace();
        if (free < need)
            throw new IOException("not enough free space to convert the shaders: need "
                    + need / (1024 * 1024) + " MB, " + free / (1024 * 1024) + " MB free");
        if (!workDir.isDirectory() && !workDir.mkdirs()) throw new IOException("cannot create " + workDir);
        String version = ShaderDroid.version();
        Log.i(TAG, "converting " + dataDir + " with ShaderDroid " + version);
        long t0 = System.currentTimeMillis();
        String error = ShaderDroid.convert(dataDir.getAbsolutePath(), dataDir.getAbsolutePath(), ShaderDroid.DROP_D3D11,
                0, new File(workDir, "shaderdroid_report.json").getAbsolutePath(), line -> {
                    Log.i(TAG, line);
                    if (progress != null) progress.line(line);
                });
        if (error != null) throw new IOException("shader conversion failed: " + error);
        try (OutputStream out = new FileOutputStream(new File(dataDir, MARKER))) {
            out.write(("ShaderDroid " + version + "\n").getBytes(StandardCharsets.UTF_8));
        }
        Log.i(TAG, "shaders converted in " + (System.currentTimeMillis() - t0) / 1000 + " s");
    }

    private static long largestFile(File dir) {
        long max = 0;
        File[] kids = dir.listFiles();
        if (kids == null) return 0;
        for (File k : kids) max = Math.max(max, k.isDirectory() ? largestFile(k) : k.length());
        return max;
    }
}

package com.shaderdroid;

/**
 * The JNI face of ShaderDroid, the closed-source shader converter (libshaderdroid.so): it retargets a
 * Windows Unity game's Direct3D 11 shaders to Vulkan. The library is not part of this repository; it is
 * dropped into app/src/main/jniLibs/arm64-v8a/ for release builds, and the launcher works without it
 * (available() is false and nothing is converted).
 */
public final class ShaderDroid {
    /** Remove the D3D11 platform from retargeted shaders (smaller files; the phone only needs Vulkan). */
    public static final int DROP_D3D11 = 1;
    public static final int NO_COMPUTE = 2;
    public static final int NO_BUNDLES = 4;

    /** One progress line, called on the thread that runs convert(). */
    public interface Log {
        void line(String s);
    }

    private static final boolean LOADED;

    static {
        boolean ok;
        try {
            System.loadLibrary("shaderdroid");
            ok = true;
        } catch (UnsatisfiedLinkError e) {
            ok = false;
        }
        LOADED = ok;
    }

    private ShaderDroid() {}

    /** Whether this build carries the converter. */
    public static boolean available() {
        return LOADED;
    }

    public static native String version();

    /**
     * Converts the game's "<Game>_Data" folder srcData into dstData (the same folder converts in place:
     * files are replaced through a temporary file). jobs <= 0: all cores. reportPath: JSON report, or null.
     * Returns null on success, else the error message.
     */
    public static native String convert(String srcData, String dstData, int flags, int jobs, String reportPath, Log log);
}

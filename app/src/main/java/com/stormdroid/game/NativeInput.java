package com.stormdroid.game;

/**
 * The native engine's numbers for the overlay, over JNI into il2mono (libil2cpp.so, pad.c). Only in
 * the ":unity" process, where the player has loaded that library.
 */
public final class NativeInput {
    static {
        System.loadLibrary("il2cpp");   // already loaded by the player; this binds the JNI methods
    }

    private NativeInput() {}

    private static native long nativeGetFrameCount();
    private static native boolean nativeMouseScroll(float x, float y, float notches);
    // The virtual keyboard and mouse the managed input bridge feeds to the Input System (Against the
    // Storm's AtsInputBridge; pad.c keeps the state).
    private static native void nativeKey(int glfwKey, boolean down);
    private static native void nativeMouseButton(int button, boolean down);
    private static native void nativeMousePosition(float x, float y);
    private static native void nativeSetPad(int buttons, float lx, float ly, float rx, float ry, float lt, float rt);
    private static native void nativeMouseDelta(float dx, float dy);
    private static native int nativeGetCursorState();

    /** Relative mouse motion, fractions of the game rect. */
    public static void mouseDelta(float dx, float dy) {
        nativeMouseDelta(dx, dy);
    }

    /** -1 = the game has not reported; else bit 0 = cursor locked, bit 1 = cursor visible. */
    public static int cursorState() {
        return nativeGetCursorState();
    }

    /** The virtual gamepad: GamepadButton bits, sticks -1..1 (y up), triggers 0..1. */
    public static void setPad(int buttons, float lx, float ly, float rx, float ry, float lt, float rt) {
        nativeSetPad(buttons, lx, ly, rx, ry, lt, rt);
    }

    /** A key by GLFW code. */
    public static void key(int glfwKey, boolean down) {
        nativeKey(glfwKey, down);
    }

    /** 0 = left, 1 = right, 2 = middle. */
    public static void mouseButton(int button, boolean down) {
        nativeMouseButton(button, down);
    }

    /** The cursor as viewport fractions, top-left origin. */
    public static void mousePosition(float x, float y) {
        nativeMousePosition(x, y);
    }

    /** Viewport fractions, top-left origin; positive notches scroll up. False until the bridge is ready. */
    public static boolean scroll(float x, float y, float notches) {
        return nativeMouseScroll(x, y, notches);
    }

    /** Frames the engine presented so far (vkshim counts them), for the FPS counter. */
    public static long frameCount() {
        return nativeGetFrameCount();
    }
}

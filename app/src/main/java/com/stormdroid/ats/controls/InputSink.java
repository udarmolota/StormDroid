package com.stormdroid.ats.controls;

import android.util.Log;

/**
 * Where the on-screen controls deliver their input (ported from ValDroid's controls). Same static
 * API as Zomdroid's InputNativeInterface, so the ported elements call it unchanged. Against the
 * Storm runs only on the native engine (Unity's Android player, ":unity" process), where
 * AtsGameOverlay installs a {@link Backend} that takes everything: keyboard, mouse and gamepad go
 * over JNI to the managed AtsInputBridge's virtual Input System devices. Without a backend (the
 * editor) everything is a no-op.
 */
public final class InputSink {
    private static final String TAG = "StormDroid/AtsInputSink";

    private InputSink() {}

    // ------------------------------------------------------------------ backend

    /**
     * Takes all input instead of the X server and the evdev pad. Positions and deltas are fractions
     * of the game rect, top-left origin, + = right / down; key and button numbers are GLFW's.
     */
    public interface Backend {
        void key(int glfwKey, boolean pressed);
        void mouseButton(int glfwButton, boolean pressed);
        void mousePosition(float x, float y);
        void mouseDelta(float dx, float dy);
        void mouseScroll(float notches);
        /** GLFW axis 0..3 = LX, LY, RX, RY in -1..1 (down = +y); 4/5 = LT/RT in 0..1. */
        void gamepadAxis(int axis, float value);
        /** GLFW hat mask: 1 up, 2 right, 4 down, 8 left. */
        void gamepadDpad(int mask);
        /** GLFW gamepad button: A, B, X, Y, LB, RB, Back, Start, Guide, L3, R3. */
        void gamepadButton(int button, boolean pressed);
        /** Whether the game holds the mouse (mouse look): 1, 0, or -1 while it is not known. */
        int mouseLocked();
        /** Whether the game hides its cursor: 1, 0, or -1 while it is not known. */
        int cursorHidden();
    }

    private static volatile Backend backend;

    public static void setBackend(Backend b) {
        backend = b;
    }

    /** True in the native engine's process, where a Backend takes the input instead of the evdev pad. */
    public static boolean hasBackend() {
        return backend != null;
    }

    // STORMDROID_DIAG=1 in the game's environment: the touchpad and mouse stick log every tap they
    // accept or reject, to follow a lost click from the finger to the game.
    private static Boolean diag;

    public static boolean isDiag() {
        if (diag == null) diag = "1".equals(System.getenv("STORMDROID_DIAG"));
        return diag;
    }

    /** A tap decision of the touchpad or the mouse stick, under STORMDROID_DIAG=1. */
    public static void logTap(String who, boolean accepted, float dist, long elapsedMs, long eventMs) {
        if (!isDiag()) return;
        Log.i(TAG, "tap " + (accepted ? "accepted" : "rejected") + " on " + who + ": moved " + (int) dist
                + " px, " + elapsedMs + " ms by the clock, " + eventMs + " ms by the touch times");
    }

    /** The game rect in game-buffer px, never 0 (a fraction is taken of it). */
    private static float gameWidthPx() {
        return Math.max(1, Math.round(gameW * renderScale));
    }

    private static float gameHeightPx() {
        return Math.max(1, Math.round(gameH * renderScale));
    }

    // ------------------------------------------------------------------ geometry

    // Zomdroid's elements send the cursor as "view px * render scale", because there the view WAS
    // the game surface. The overlay covers the whole screen while the game may sit in a
    // letterboxed rect, so the rect's offset is subtracted here, in the one place that knows it.
    private static float renderScale = 1f;
    private static int gameLeft, gameTop, gameW, gameH;   // screen px; gameW <= 0 = full view

    /** Called by InputControlsView whenever the render scale or the game rect changes. */
    static void setGeometry(float scale, int left, int top, int w, int h) {
        renderScale = scale > 0f ? scale : 1f;
        gameLeft = left; gameTop = top; gameW = w; gameH = h;
    }

    /** View px * renderScale (Zomdroid convention) -> game-buffer px, clamped to the buffer. */
    static int toGameX(double scaledViewX) {
        if (gameW <= 0) return (int) Math.round(scaledViewX);
        int max = Math.max(1, Math.round(gameW * renderScale)) - 1;
        int v = (int) Math.round(scaledViewX - gameLeft * renderScale);
        return v < 0 ? 0 : (v > max ? max : v);
    }

    static int toGameY(double scaledViewY) {
        if (gameH <= 0) return (int) Math.round(scaledViewY);
        int max = Math.max(1, Math.round(gameH * renderScale)) - 1;
        int v = (int) Math.round(scaledViewY - gameTop * renderScale);
        return v < 0 ? 0 : (v > max ? max : v);
    }

    // ------------------------------------------------------------------ keyboard / mouse

    public static void sendKeyboard(int key, boolean isPressed) {
        Backend be = backend;
        if (be != null) be.key(key, isPressed);
    }


    /** True while the game holds the mouse (see XServer.isMouseLockedByGame). */
    public static boolean isMouseLocked() {
        Backend be = backend;
        return be != null && be.mouseLocked() == 1;
    }

    /** False while no X server is up (the editor, a session without one): the mouse-lock signal
     *  then says nothing about the game, and callers must not read "not locked" as "in a menu". */
    public static boolean hasMouseLockSignal() {
        Backend be = backend;
        return be != null && be.mouseLocked() >= 0;
    }

    /** True while the game shows no cursor of its own (see XServer.isGameCursorHidden). */
    public static boolean isGameCursorHidden() {
        Backend be = backend;
        return be != null && be.cursorHidden() == 1;
    }

    /** Mouse look: move the pointer by a delta (view px * renderScale). */
    public static void sendCursorDelta(double dx, double dy) {
        Backend be = backend;
        if (be != null) be.mouseDelta((float) (dx / gameWidthPx()), (float) (dy / gameHeightPx()));
    }

    /** The game's pointer position in view px (for re-syncing the overlay cursor); unknown here. */
    static float[] pointerInView() {
        return null;   // nothing moves the pointer behind the overlay's back
    }

    public static void sendCursorPos(double x, double y) {
        Backend be = backend;
        if (be != null) be.mousePosition(toGameX(x) / gameWidthPx(), toGameY(y) / gameHeightPx());
    }

    /**
     * GLFW button 0/1/2 = left/right/middle. Pressed where the X pointer already is: every cursor
     * move is injected as it happens, and re-sending the position here would drag a pointer the
     * game has warped (mouse look) back to the overlay cursor. GLFW buttons 4-8 have no X button.
     */
    public static void sendMouseButton(int button, boolean isPressed) {
        Backend be = backend;
        if (be != null && button >= 0 && button <= 2) be.mouseButton(button, isPressed);
    }

    /** X has no wheel axis: each notch is a press+release of button 4 (up) or 5 (down). */
    public static void sendMouseScroll(double xoffset, double yoffset) {
        Backend be = backend;
        if (be != null && yoffset != 0) be.mouseScroll((float) yoffset);
    }

    /**
     * Deliberately a no-op: the Input System makes text from the virtual keyboard's keys itself.
     */
    public static void sendChar(int codepoint) {}

    // ------------------------------------------------------------------ gamepad

    /** GLFW axis 0..3 = LX, LY, RX, RY in -1..1 (down = +y, as evdev); 4/5 = LT/RT in 0..1. */
    public static void sendJoystickAxis(int axis, float state) {
        Backend be = backend;
        if (be != null) be.gamepadAxis(axis, state);
    }

    /** GLFW hat mask: 1 up, 2 right, 4 down, 8 left -> HAT0X/HAT0Y. */
    public static void sendJoystickDpad(int dpad, char state) {
        Backend be = backend;
        if (be != null) be.gamepadDpad(state);
    }

    /** GLFW gamepad button order (A, B, X, Y, LB, RB, Back, Start, Guide, L3, R3) -> evdev codes. */
    public static void sendJoystickButton(int button, boolean isPressed) {
        Backend be = backend;
        if (be != null) be.gamepadButton(button, isPressed);
    }

    /** The virtual pad exists from launch; nothing to announce (kept for API parity). */
    public static void sendJoystickConnected() {}
}

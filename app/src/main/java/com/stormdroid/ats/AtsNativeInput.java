package com.stormdroid.ats;

import com.stormdroid.ats.controls.InputSink;
import com.stormdroid.game.NativeInput;

/**
 * The on-screen controls' input for Against the Storm (native engine, ":unity" process only), as
 * ValDroid's NativeInput: everything goes over JNI to il2mono (pad.c), where the managed
 * AtsInputBridge picks it up once per Input System update and feeds a virtual gamepad, keyboard
 * and mouse.
 */
public final class AtsNativeInput implements InputSink.Backend {
    // UnityEngine.InputSystem.LowLevel.GamepadButton bit numbers.
    private static final int DPAD_UP = 0, DPAD_DOWN = 1, DPAD_LEFT = 2, DPAD_RIGHT = 3;
    private static final int NORTH = 4, EAST = 5, SOUTH = 6, WEST = 7;
    private static final int L3 = 8, R3 = 9, LB = 10, RB = 11, START = 12, SELECT = 13;
    private static final int DPAD_BITS = (1 << DPAD_UP) | (1 << DPAD_DOWN) | (1 << DPAD_LEFT) | (1 << DPAD_RIGHT);
    /** GLFW gamepad button order (A, B, X, Y, LB, RB, Back, Start, Guide, L3, R3) -> bit; -1 = none. */
    private static final int[] GLFW_BUTTON_BITS = { SOUTH, EAST, WEST, NORTH, LB, RB, SELECT, START, -1, L3, R3 };

    private int padButtons;
    private float leftX, leftY, rightX, rightY, leftTrigger, rightTrigger;
    private float mouseX = 0.5f, mouseY = 0.5f;   // the wheel acts where the cursor last was

    /** Rendered frames so far (the FPS counter's source). */
    public static long frameCount() {
        return NativeInput.frameCount();
    }

    @Override public void key(int glfwKey, boolean pressed) { NativeInput.key(glfwKey, pressed); }

    @Override public void mouseButton(int glfwButton, boolean pressed) { NativeInput.mouseButton(glfwButton, pressed); }

    @Override public void mousePosition(float x, float y) {
        mouseX = x;
        mouseY = y;
        NativeInput.mousePosition(x, y);
    }

    @Override public void mouseDelta(float dx, float dy) { NativeInput.mouseDelta(dx, dy); }

    @Override public void mouseScroll(float notches) { NativeInput.scroll(mouseX, mouseY, notches); }

    // The Input System's stick y points up; GLFW's (and the controls') points down.
    @Override public synchronized void gamepadAxis(int axis, float value) {
        switch (axis) {
            case 0: leftX = value; break;
            case 1: leftY = -value; break;
            case 2: rightX = value; break;
            case 3: rightY = -value; break;
            case 4: leftTrigger = value; break;
            case 5: rightTrigger = value; break;
            default: return;
        }
        pushPad();
    }

    @Override public synchronized void gamepadDpad(int mask) {
        int bits = 0;
        if ((mask & 1) != 0) bits |= 1 << DPAD_UP;
        if ((mask & 2) != 0) bits |= 1 << DPAD_RIGHT;
        if ((mask & 4) != 0) bits |= 1 << DPAD_DOWN;
        if ((mask & 8) != 0) bits |= 1 << DPAD_LEFT;
        padButtons = (padButtons & ~DPAD_BITS) | bits;
        pushPad();
    }

    @Override public synchronized void gamepadButton(int button, boolean pressed) {
        if (button < 0 || button >= GLFW_BUTTON_BITS.length || GLFW_BUTTON_BITS[button] < 0) return;
        int bit = 1 << GLFW_BUTTON_BITS[button];
        padButtons = pressed ? padButtons | bit : padButtons & ~bit;
        pushPad();
    }

    private void pushPad() {
        NativeInput.setPad(padButtons, leftX, leftY, rightX, rightY, leftTrigger, rightTrigger);
    }

    @Override public int mouseLocked() {
        int s = NativeInput.cursorState();
        return s < 0 ? -1 : (s & 1);
    }

    @Override public int cursorHidden() {
        int s = NativeInput.cursorState();
        return s < 0 ? -1 : ((s & 2) != 0 ? 0 : 1);
    }
}

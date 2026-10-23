package com.stormdroid.ats;

import com.stormdroid.AppStorage;
import com.stormdroid.InstanceSettings;
import com.stormdroid.LauncherPreferences;
import com.stormdroid.PerfOverlayView;
import com.stormdroid.PerfSampler;
import com.stormdroid.R;
import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.stormdroid.ats.controls.InputControlsView;
import com.stormdroid.ats.controls.InputSink;
import com.stormdroid.game.NativeEngine;
import com.stormdroid.ats.AtsNativeInput;

/**
 * What the native engine shows over Unity's player (NativeUnityActivity creates it by name, in the
 * ":unity" process): the instance's on-screen controls, the soft keyboard and the in-game HUD, as in
 * GameActivity. The controls' input goes to {@link AtsNativeInput} instead of the X server. Touches no
 * control takes fall through to the player, so menus still work by touch.
 */
public class AtsGameOverlay extends FrameLayout
        implements View.OnKeyListener, View.OnGenericMotionListener, View.OnCapturedPointerListener {
    private final InputControlsView controls;
    // A physical controller, as in GameActivity: its buttons and sticks drive the game's virtual gamepad
    // (VirtualGamepad routes to InputSink's backend here). NativeUnityActivity offers us its events.
    private final com.stormdroid.ats.input.GamepadHandler gamepad;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private java.io.File snapshotDir;   // the instance folder: native_engine.log and the snapshots
    private int snapshotCount;

    // HUD, global setting (Settings → Frame rate): off, the classic FPS counter or the full bar.
    private TextView fpsText;
    private long fpsLastCount, fpsLastTimeMs;
    private PerfOverlayView perfView;
    private PerfSampler perfSampler;
    private HandlerThread perfThread;
    private Handler perfHandler;

    public AtsGameOverlay(Context context) {
        super(context);
        String instanceName = context instanceof Activity
                ? ((Activity) context).getIntent().getStringExtra(NativeEngine.EXTRA_INSTANCE) : null;
        // The game's own log for "Report a bug": first, so it has as much of the start as possible.
        AppStorage storage = AppStorage.getSingleton();
        if (storage != null && instanceName != null) {
            snapshotDir = storage.getInstanceDir(instanceName);
            com.stormdroid.game.NativeLog.start(snapshotDir, context instanceof Activity
                    ? ((Activity) context).getIntent().getStringExtra(NativeEngine.EXTRA_LAUNCH_CONFIG) : null);
        }
        if (context instanceof Activity) applyWindowSettings((Activity) context, instanceName);

        InputSink.setBackend(new AtsNativeInput());
        controls = new InputControlsView(context);
        controls.setInstanceName(instanceName);   // before layout: the layout is read on the first size change
        controls.setRenderScale(1f);              // the cursor is sent as a fraction of the screen
        addView(controls, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        // Physical mouse, as GameActivity: Android's own pointer is hidden so it does not double up with
        // the overlay's cursor, which stays the single visible one. The overlay covers the whole game,
        // so its icon is the one Android shows.
        android.view.PointerIcon nullIcon =
                android.view.PointerIcon.getSystemIcon(context, android.view.PointerIcon.TYPE_NULL);
        setPointerIcon(nullIcon);
        controls.setPointerIcon(nullIcon);

        KeyboardCatcher keyboardCatcher = new KeyboardCatcher(context);
        addView(keyboardCatcher, new LayoutParams(1, 1));   // 1px, invisible
        controls.setKeyboardToggleListener(keyboardCatcher::toggle);

        gamepad = context instanceof Activity ? new com.stormdroid.ats.input.GamepadHandler((Activity) context, controls) : null;

        int hud = LauncherPreferences.getSingleton() != null
                ? LauncherPreferences.getSingleton().getHudMode() : LauncherPreferences.HUD_OFF;
        if (hud == LauncherPreferences.HUD_FPS) addFpsText(context);
        if (hud == LauncherPreferences.HUD_FULL) addPerfBar(context);
    }

    /**
     * As GameActivity: one fixed landscape (mirrored on request, for gamepad cradles that hold the
     * phone the other way round).
     */
    private static void applyWindowSettings(Activity activity, String instanceName) {
        boolean reverse = instanceName != null && new InstanceSettings(instanceName).isReverseLandscape();
        activity.setRequestedOrientation(reverse
                ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    }

    /** Classic counter ("FPS: XX", top-left), as in GameActivity. */
    private void addFpsText(Context context) {
        fpsText = new TextView(context);
        fpsText.setText("FPS: --");
        fpsText.setTextColor(0xFF00FF66);                 // green, readable over any scene
        fpsText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        fpsText.setShadowLayer(4f, 0f, 0f, 0xFF000000);   // outline so it reads on light scenes
        fpsText.setPadding(0, 0, 0, 0);
        final int m = Math.round(8 * getResources().getDisplayMetrics().density);
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.setMargins(m, m, 0, 0);
        addView(fpsText, lp);
        // Shift it RIGHT by its own width once laid out, so it clears the game's top-left HUD.
        fpsText.post(() -> {
            LayoutParams p = (LayoutParams) fpsText.getLayoutParams();
            p.leftMargin = m + fpsText.getWidth();
            fpsText.setLayoutParams(p);
        });
    }

    /** Full performance bar, top-centre; the native engine always renders with Vulkan. */
    private void addPerfBar(Context context) {
        perfView = new PerfOverlayView(context, "VK");
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.setMargins(0, Math.round(4 * getResources().getDisplayMetrics().density), 0, 0);
        addView(perfView, lp);
        perfSampler = new PerfSampler(context);
        perfThread = new HandlerThread("PerfOverlay");
        perfThread.start();
        perfHandler = new Handler(perfThread.getLooper());
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        controls.setGameRect(0, 0, w, h);   // the player fills the screen
    }

    // ------------------------------------------------------------------ lifecycle

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            startHud();
            if (gamepad != null) gamepad.start();
            startDeviceWatch();
            ui.removeCallbacks(mouseLockTick);
            ui.post(mouseLockTick);
        } else {
            stopHud();
            stopDeviceWatch();
            ui.removeCallbacks(mouseLockTick);
            if (gamepad != null) gamepad.stop();   // releases whatever the controller held
            controls.resetAll();   // nothing stays held while the game is in the background
        }
    }

    // ------------------------------------------------------------------ connected devices

    // As GameActivity: a physical gamepad hides the on-screen gamepad elements, a physical keyboard all
    // of them; both come back when the device goes.
    private android.hardware.input.InputManager inputManager;
    private boolean lastPadConnected;
    private final android.hardware.input.InputManager.InputDeviceListener deviceListener =
            new android.hardware.input.InputManager.InputDeviceListener() {
                @Override public void onInputDeviceAdded(int id) {
                    if (gamepad != null) gamepad.onInputDeviceChanged(id, "connected");
                    refreshDevices();
                }
                @Override public void onInputDeviceRemoved(int id) {
                    if (gamepad != null) gamepad.onInputDeviceRemoved(id);
                    refreshDevices();
                }
                @Override public void onInputDeviceChanged(int id) {
                    if (gamepad != null) gamepad.onInputDeviceChanged(id, "changed");
                    refreshDevices();
                }
            };

    private void startDeviceWatch() {
        if (inputManager == null)
            inputManager = (android.hardware.input.InputManager) getContext().getSystemService(Context.INPUT_SERVICE);
        if (inputManager != null) inputManager.registerInputDeviceListener(deviceListener, null);
        lastPadConnected = !com.stormdroid.ats.input.GamepadHandler.hasConnectedGamepad();   // force the first apply
        refreshDevices();
        if (gamepad != null) gamepad.logConnectedGamepads("game start");
    }

    private void stopDeviceWatch() {
        if (inputManager != null) inputManager.unregisterInputDeviceListener(deviceListener);
    }

    private void refreshDevices() {
        controls.setKeyboardConnected(com.stormdroid.ats.input.GamepadHandler.hasExternalKeyboard());
        boolean pad = com.stormdroid.ats.input.GamepadHandler.hasConnectedGamepad();
        if (pad == lastPadConnected) return;   // only on a change: never undo the manual hide toggle
        lastPadConnected = pad;
        controls.setGamepadConnected(pad);
    }

    // ------------------------------------------------------------------ touches on the bare game

    // A touch the on-screen controls did not take: a tap is a
    // left click there, a finger held still a right click, a pinch the mouse wheel (the game zooms with
    // it). All through the virtual mouse; the touch is consumed, so Unity's own Touchscreen never sees
    // it. Before this, bare touches went to Unity and its UI took them, but a touch whose release Unity
    // missed left Touchscreen/press held, and the UI's Click action then ignored the virtual mouse
    // (menus and chests dead to the touchpad, S25 2026-10-08).
    private static final long LONG_PRESS_MS = 350;
    private android.view.ScaleGestureDetector scaleDetector;
    private boolean scaling, tapMoved, longPressFired, gestureOwned;
    private float tapDownX, tapDownY;

    private final Runnable longPressRunnable = this::longPress;

    // Drag on the bare game: the camera follows the finger, through the virtual gamepad's left stick
    // (the game's camera stick), so the game stays in its gamepad scheme. The stick is the finger's
    // offset from where it went down, full at DRAG_FULL_DP; a finger that stops sliding lets go of it.
    private static final float DRAG_FULL_DP = 90f;
    private boolean dragPanning;
    private float lastPanX, lastPanY;
    private final Runnable panIdleRelease = this::releaseDragPan;

    private void dragPan(float x, float y) {
        float full = DRAG_FULL_DP * getResources().getDisplayMetrics().density;
        float sx = clampUnit(-(x - tapDownX) / full), sy = clampUnit(-(y - tapDownY) / full);
        dragPanning = true;
        InputSink.sendJoystickAxis(0, sx);   // GLFW axes: + = right / down
        InputSink.sendJoystickAxis(1, sy);
    }

    private void releaseDragPan() {
        ui.removeCallbacks(panIdleRelease);
        if (!dragPanning) return;
        dragPanning = false;
        InputSink.sendJoystickAxis(0, 0f);
        InputSink.sendJoystickAxis(1, 0f);
    }

    private static float clampUnit(float v) { return v > 1f ? 1f : (v < -1f ? -1f : v); }

    private void longPress() {
        if (!gestureOwned || tapMoved || scaling || longPressFired) return;
        longPressFired = true;
        controls.tapAt(tapDownX, tapDownY, com.stormdroid.ats.controls.GLFWBinding.MOUSE_BUTTON_RIGHT);
        controls.maybeHaptic();
    }

    private class ScaleListener extends android.view.ScaleGestureDetector.SimpleOnScaleGestureListener {
        private float accum;
        @Override public boolean onScaleBegin(android.view.ScaleGestureDetector dt) { scaling = true; accum = 0f; return true; }
        @Override public boolean onScale(android.view.ScaleGestureDetector dt) {
            accum += dt.getScaleFactor() - 1f;
            while (accum >  0.15f) { accum -= 0.15f; pinchScroll(dt.getFocusX(), dt.getFocusY(), +1); }
            while (accum < -0.15f) { accum += 0.15f; pinchScroll(dt.getFocusX(), dt.getFocusY(), -1); }
            return true;
        }
        @Override public void onScaleEnd(android.view.ScaleGestureDetector dt) { scaling = false; }
    }

    /** One wheel notch at the pinch focus, through the shared cursor. */
    private void pinchScroll(float x, float y, int notches) {
        controls.moveCursorTo(x, y);
        InputSink.sendMouseScroll(0, notches);
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent e) {
        if (isMouse(e)) return mouseTouch(e);
        if (scaleDetector == null) scaleDetector = new android.view.ScaleGestureDetector(getContext(), new ScaleListener());
        scaleDetector.onTouchEvent(e);
        if (e.getPointerCount() >= 2 || scaling) {   // pinch: never a tap, a long press or a pan
            ui.removeCallbacks(longPressRunnable);
            releaseDragPan();
            tapMoved = true;
            gestureOwned = false;
            return true;
        }
        float d = getResources().getDisplayMetrics().density;
        switch (e.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                tapDownX = e.getX(); tapDownY = e.getY();
                lastPanX = tapDownX; lastPanY = tapDownY;
                tapMoved = false; longPressFired = false; gestureOwned = true;
                ui.removeCallbacks(longPressRunnable);
                ui.postDelayed(longPressRunnable, LONG_PRESS_MS);
                return true;
            case android.view.MotionEvent.ACTION_MOVE:
                if (gestureOwned && !tapMoved
                        && Math.abs(e.getX() - tapDownX) + Math.abs(e.getY() - tapDownY) > 22 * d) {
                    tapMoved = true;                        // a drag: neither click
                    ui.removeCallbacks(longPressRunnable);
                }
                if (gestureOwned && tapMoved
                        && Math.abs(e.getX() - lastPanX) + Math.abs(e.getY() - lastPanY) > 2 * d) {
                    dragPan(e.getX(), e.getY());
                    lastPanX = e.getX(); lastPanY = e.getY();
                    ui.removeCallbacks(panIdleRelease);
                    ui.postDelayed(panIdleRelease, 90);
                }
                return true;
            case android.view.MotionEvent.ACTION_UP:
                ui.removeCallbacks(longPressRunnable);
                releaseDragPan();
                if (gestureOwned && !tapMoved && !longPressFired)
                    controls.tapAt(e.getX(), e.getY(), com.stormdroid.ats.controls.GLFWBinding.MOUSE_BUTTON_LEFT);
                gestureOwned = false;
                return true;
            case android.view.MotionEvent.ACTION_CANCEL:
                ui.removeCallbacks(longPressRunnable);
                releaseDragPan();
                gestureOwned = false;
                return true;
        }
        return true;
    }

    @Override public boolean onKey(View v, int keyCode, android.view.KeyEvent event) {
        return gamepad != null && gamepad.onKey(event);
    }

    @Override public boolean onGenericMotion(View v, android.view.MotionEvent event) {
        if (gamepad != null && gamepad.onMotion(event)) return true;
        return isMouse(event) && mouseMotion(event);
    }

    // ------------------------------------------------------------------ physical mouse

    // A real mouse is taken whole, as the touches are, and goes to the game through the virtual mouse
    // like everything else (StormDroid's overlay does the same): its moves drive the overlay's cursor, its
    // buttons are the game's left, right and middle buttons, its wheel the wheel. Left to the player, a
    // press arrived as a bare touch and became a left click wherever it was, so the right button never
    // reached the game (an Infinix player could not eat, 2026-10-14). While the game holds the mouse
    // (mouse look) the pointer is captured and its relative motion turns the camera. The capture itself
    // is the Unity player's: it requests and releases it as the game locks and frees its cursor
    // (Cursor.lockState). The overlay only takes the captured motion: an earlier poll of its own released
    // the player's capture whenever its idea of "a mouse is connected" differed, and the free pointer
    // ran to the screen edge mid-game and brought up Android's bars (the same Infinix player, 2026-10-15).
    private static boolean isMouse(android.view.MotionEvent e) {
        return (e.getSource() & android.view.InputDevice.SOURCE_MOUSE) == android.view.InputDevice.SOURCE_MOUSE
                || e.getToolType(0) == android.view.MotionEvent.TOOL_TYPE_MOUSE;
    }

    // A mouse press must not land on the on-screen buttons under it: the mouse is the game's, the
    // buttons are for fingers.
    @Override public boolean onInterceptTouchEvent(android.view.MotionEvent e) {
        return isMouse(e) || super.onInterceptTouchEvent(e);
    }

    private int mouseButtonsDown;   // MotionEvent.BUTTON_* reported pressed
    private float hoverX = -1, hoverY = -1;   // the free pointer's last position

    private boolean mouseMotion(android.view.MotionEvent e) {
        switch (e.getActionMasked()) {
            case android.view.MotionEvent.ACTION_HOVER_ENTER:
            case android.view.MotionEvent.ACTION_HOVER_MOVE: {
                float x = e.getX(), y = e.getY();
                // The game holds the mouse but the pointer is not captured (yet): turn the camera by the
                // pointer's own motion, so mouse look never goes dead.
                if (InputSink.isMouseLocked() && !hasPointerCapture() && hoverX >= 0) controls.moveCursorBy(x - hoverX, y - hoverY);
                else controls.moveCursorTo(x, y);
                hoverX = x;
                hoverY = y;
                return true;
            }
            case android.view.MotionEvent.ACTION_SCROLL: {
                float v = e.getAxisValue(android.view.MotionEvent.AXIS_VSCROLL);
                controls.moveCursorTo(e.getX(), e.getY());
                if (v != 0) InputSink.sendMouseScroll(0, v > 0 ? 1 : -1);
                return true;
            }
            default:
                return true;   // button press/release come as touch events; nothing else is the player's
        }
    }

    private boolean mouseTouch(android.view.MotionEvent e) {
        switch (e.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
            case android.view.MotionEvent.ACTION_MOVE:
            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL: {
                controls.moveCursorTo(e.getX(), e.getY());
                int action = e.getActionMasked();
                boolean up = action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL;
                // A synthetic mouse tap (adb "input mouse tap") carries no button state: the left one.
                int now = up ? 0 : e.getButtonState() != 0 ? e.getButtonState() : android.view.MotionEvent.BUTTON_PRIMARY;
                reportMouseButtons(now);
                return true;
            }
            default:
                return true;
        }
    }

    /** Press / release the buttons whose state changed, as GLFW numbers: 0 left, 1 right, 2 middle. */
    private void reportMouseButtons(int now) {
        int changed = now ^ mouseButtonsDown;
        int[] bits = { android.view.MotionEvent.BUTTON_PRIMARY, android.view.MotionEvent.BUTTON_SECONDARY,
                android.view.MotionEvent.BUTTON_TERTIARY };
        for (int b = 0; b < bits.length; b++) {
            if ((changed & bits[b]) != 0) InputSink.sendMouseButton(b, (now & bits[b]) != 0);
        }
        mouseButtonsDown = now;
    }

    /** Captured mouse (mouse look): relative motion turns the camera; buttons and wheel as above. */
    @Override public boolean onCapturedPointer(View v, android.view.MotionEvent e) {
        switch (e.getActionMasked()) {
            case android.view.MotionEvent.ACTION_MOVE: {
                float dx = 0, dy = 0;
                for (int h = 0; h < e.getHistorySize(); h++) {
                    dx += e.getHistoricalAxisValue(android.view.MotionEvent.AXIS_RELATIVE_X, h);
                    dy += e.getHistoricalAxisValue(android.view.MotionEvent.AXIS_RELATIVE_Y, h);
                }
                dx += e.getAxisValue(android.view.MotionEvent.AXIS_RELATIVE_X);
                dy += e.getAxisValue(android.view.MotionEvent.AXIS_RELATIVE_Y);
                if (dx != 0 || dy != 0) controls.moveCursorBy(dx, dy);
                break;
            }
            case android.view.MotionEvent.ACTION_SCROLL: {
                float s = e.getAxisValue(android.view.MotionEvent.AXIS_VSCROLL);
                if (s != 0) InputSink.sendMouseScroll(0, s > 0 ? 1 : -1);
                break;
            }
            default:
                break;
        }
        reportMouseButtons(e.getButtonState());
        return true;
    }

    // Polled, because the lock is the game's (the bridge reports it): the overlay's cursor hides while
    // the game holds the mouse. Each change of the lock, of the mice Android reports or of the capture
    // goes to the log, one line, to follow a lost capture from a report.
    private String lastMouseState;

    private final Runnable mouseLockTick = new Runnable() {
        @Override public void run() {
            boolean locked = InputSink.isMouseLocked();
            String mice = physicalMice();
            controls.setMouseLocked(locked);
            controls.setPhysicalMouse(!mice.isEmpty());
            logMouseState(locked, mice);
            ui.postDelayed(this, 150);
        }
    };

    @Override public void onPointerCaptureChange(boolean hasCapture) {
        super.onPointerCaptureChange(hasCapture);
        logMouseState(InputSink.isMouseLocked(), physicalMice());
    }

    private void logMouseState(boolean locked, String mice) {
        String state = "game lock " + (locked ? "on" : "off") + ", pointer capture " + (hasPointerCapture() ? "on" : "off")
                + ", mice: " + (mice.isEmpty() ? "none" : mice);
        if (state.equals(lastMouseState)) return;
        lastMouseState = state;
        android.util.Log.i("StormDroid/AtsMouse", state);
    }

    /** The physical mice Android reports, by name; empty when none. */
    private static String physicalMice() {
        StringBuilder sb = new StringBuilder();
        for (int id : android.view.InputDevice.getDeviceIds()) {
            android.view.InputDevice d = android.view.InputDevice.getDevice(id);
            if (d == null || d.isVirtual() || !d.supportsSource(android.view.InputDevice.SOURCE_MOUSE)) continue;
            if (sb.length() > 0) sb.append("; ");
            sb.append(d.getName());
        }
        return sb.toString();
    }

    @Override protected void onDetachedFromWindow() {
        ui.removeCallbacks(mouseLockTick);
        stopDeviceWatch();
        if (gamepad != null) gamepad.stop();
        stopHud();
        controls.resetAll();
        if (perfThread != null) perfThread.quitSafely();
        super.onDetachedFromWindow();
    }

    // ------------------------------------------------------------------ snapshots

    /** Files the snapshots go to, in turn; "Report a bug" attaches them (LogExporter). */
    public static final String[] SNAPSHOT_FILES = { "native_snapshot_1.jpg", "native_snapshot_2.jpg", "native_snapshot_3.jpg" };
    private static final long SNAPSHOT_PERIOD_MS = 10_000;
    private static final int SNAPSHOT_WIDTH = 480;

    /**
     * Every few seconds a small copy of what is in Unity's own surface right now (PixelCopy), kept as
     * the last three JPEGs in the instance folder, with a line in the log. For a phone where the screen
     * shows something else than the game: the copy tells whether Unity's surface still holds that
     * picture or the game moved on and the picture never reached the screen.
     */
    private final Runnable snapshotTick = new Runnable() {
        @Override public void run() {
            takeSnapshot();
            ui.postDelayed(this, SNAPSHOT_PERIOD_MS);
        }
    };

    private void takeSnapshot() {
        if (snapshotDir == null) return;
        android.view.SurfaceView surface = findSurfaceView(getRootView());
        if (surface == null || surface.getWidth() <= 0 || !surface.getHolder().getSurface().isValid()) {
            android.util.Log.w("StormDroid/AtsSnapshot", "snapshot: no usable SurfaceView ("
                    + (surface == null ? "none in the window" : surface.getWidth() + "x" + surface.getHeight()) + ")");
            return;
        }
        int h = Math.max(1, Math.round((float) SNAPSHOT_WIDTH * surface.getHeight() / surface.getWidth()));
        final android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(SNAPSHOT_WIDTH, h, android.graphics.Bitmap.Config.ARGB_8888);
        final int n = snapshotCount++;
        try {
            android.view.PixelCopy.request(surface, bmp, result -> saveSnapshot(n, bmp, result), ui);
        } catch (RuntimeException e) {
            android.util.Log.w("StormDroid/AtsSnapshot", "snapshot " + n + " failed: " + e);
        }
    }

    /** Unity's SurfaceView, wherever the player put it in the window. */
    private static android.view.SurfaceView findSurfaceView(View v) {
        if (v instanceof android.view.SurfaceView) return (android.view.SurfaceView) v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                android.view.SurfaceView found = findSurfaceView(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void saveSnapshot(int n, android.graphics.Bitmap bmp, int result) {
        if (result != android.view.PixelCopy.SUCCESS) {
            android.util.Log.w("StormDroid/AtsSnapshot", "snapshot " + n + ": PixelCopy result " + result);
            return;
        }
        // A cheap fingerprint, so the log alone shows whether the picture in the surface changes.
        long sum = 0;
        for (int y = 0; y < bmp.getHeight(); y += 8)
            for (int x = 0; x < bmp.getWidth(); x += 8) sum = sum * 31 + bmp.getPixel(x, y);
        java.io.File f = new java.io.File(snapshotDir, SNAPSHOT_FILES[n % SNAPSHOT_FILES.length]);
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out);
        } catch (java.io.IOException e) {
            android.util.Log.w("StormDroid/AtsSnapshot", "snapshot " + n + ": " + e);
            return;
        }
        android.util.Log.i("StormDroid/AtsSnapshot", "snapshot " + n + " -> " + f.getName() + ", fingerprint "
                + Long.toHexString(sum));
    }

    private void startHud() {
        ui.removeCallbacks(snapshotTick);
        // Snapshots only for diagnosis (VALDROID_DIAG=1 in the environment field): copying a frame back
        // from the GPU and compressing it can show as a hitch on a weak phone.
        if ("1".equals(System.getenv("VALDROID_DIAG"))) ui.postDelayed(snapshotTick, SNAPSHOT_PERIOD_MS);
        if (fpsText != null) {
            fpsLastTimeMs = 0;                          // the first interval would be skewed
            ui.removeCallbacks(fpsTextTick);
            ui.postDelayed(fpsTextTick, 1000);
        }
        if (perfHandler != null) {
            perfHandler.removeCallbacks(perfTick);
            perfHandler.post(perfTick);                 // first sample only primes the deltas
        }
    }

    private void stopHud() {
        ui.removeCallbacks(snapshotTick);
        ui.removeCallbacks(fpsTextTick);
        if (perfHandler != null) perfHandler.removeCallbacks(perfTick);
    }

    // Frames are counted by the bridge (one per rendered frame), so this is the game's real rate.
    private static long frameCount() {
        try {
            return AtsNativeInput.frameCount();
        } catch (UnsatisfiedLinkError e) {
            return -1;
        }
    }

    private final Runnable fpsTextTick = new Runnable() {
        @Override public void run() {
            if (fpsText == null) return;
            long now = SystemClock.elapsedRealtime();
            long count = frameCount();
            if (count < 0) return;
            if (fpsLastTimeMs != 0) {
                long dFrames = count - fpsLastCount;
                long dMs = now - fpsLastTimeMs;
                if (dMs > 0) fpsText.setText("FPS: " + Math.round(dFrames * 1000.0 / dMs));
            }
            fpsLastCount = count;
            fpsLastTimeMs = now;
            ui.postDelayed(this, 1000);
        }
    };

    private final Runnable perfTick = new Runnable() {
        @Override public void run() {
            if (perfView == null || perfSampler == null || perfHandler == null) return;
            final PerfSampler.Stats stats = perfSampler.sample(frameCount());
            ui.post(() -> { if (perfView != null) perfView.setStats(stats); });
            perfHandler.postDelayed(this, 1000);
        }
    };

    // ------------------------------------------------------------------ soft keyboard

    /**
     * Invisible view that owns the IME connection. It is a "dummy" editor (BaseInputConnection without
     * a full editor): Android turns committed text into key events itself, single mappable characters
     * as ordinary key presses and anything else as one ACTION_MULTIPLE event carrying the characters.
     * They reach the player like a hardware keyboard's (NativeUnityActivity forwards ACTION_MULTIPLE),
     * and the player makes the text for input fields, the same path as "adb shell input text".
     */
    private static final class KeyboardCatcher extends View {
        private boolean accepting;

        KeyboardCatcher(Context c) {
            super(c);
            setFocusable(true);
            setFocusableInTouchMode(true);
        }

        /** Show the keyboard if hidden, hide it if shown. Bound to the on-screen TOGGLE_KEYBOARD button. */
        void toggle() {
            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm == null) return;
            accepting = !accepting;
            requestFocus();
            imm.restartInput(this);
            if (accepting) imm.showSoftInput(this, InputMethodManager.SHOW_FORCED);
            else imm.hideSoftInputFromWindow(getWindowToken(), 0);
        }

        @Override public boolean onCheckIsTextEditor() { return accepting; }

        @Override public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
            if (!accepting) return null;
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT;
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI;
            return new BaseInputConnection(this, false) {
                // Keyboards that compose words (Gboard and most with suggestions) send the word as
                // composing text and commit it whole; the dummy editor would hand that to the player
                // as one ACTION_MULTIPLE event, which Unity drops. So composing and committed text are
                // turned into single key presses here, as they change: what is typed is sent at once,
                // what the keyboard takes back is erased with Backspace. Characters without a key
                // in the virtual keyboard map (Cyrillic and the like) still cannot be typed.
                private final StringBuilder composing = new StringBuilder();

                @Override public boolean setComposingText(CharSequence text, int newCursorPosition) {
                    replaceComposing(text);
                    return true;
                }

                @Override public boolean commitText(CharSequence text, int newCursorPosition) {
                    replaceComposing(text);
                    composing.setLength(0);
                    return true;
                }

                @Override public boolean finishComposingText() {
                    composing.setLength(0);
                    return true;
                }

                // The dummy editor holds no text, so a delete would remove nothing: send Backspace.
                @Override public boolean deleteSurroundingText(int before, int after) {
                    for (int i = 0; i < before; i++) backspace();
                    return true;
                }

                /** Turn the composing text into {@code text}: Backspace the part that differs, type the rest. */
                private void replaceComposing(CharSequence text) {
                    String now = text != null ? text.toString() : "";
                    int common = 0;
                    while (common < now.length() && common < composing.length()
                            && now.charAt(common) == composing.charAt(common)) common++;
                    for (int i = composing.length(); i > common; i--) backspace();
                    composing.setLength(common);
                    for (int i = common; i < now.length(); i++) {
                        char c = now.charAt(i);
                        KeyEvent[] events = KEY_MAP.getEvents(new char[]{ c });
                        if (events != null) for (KeyEvent e : events) sendKeyEvent(e);
                        composing.append(c);
                    }
                }

                private void backspace() {
                    sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
                    sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL));
                }
            };
        }

        private static final android.view.KeyCharacterMap KEY_MAP =
                android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD);
    }
}

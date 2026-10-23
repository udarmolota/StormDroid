using System;
using System.Reflection;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using UnityEngine;
using UnityEngine.InputSystem;
using UnityEngine.InputSystem.LowLevel;

// Against the Storm's input bridge, ported from ValDroid's
// ValDroidBridge (VirtualPad.cs, VirtualKeyboardMouse.cs).
//
// The game reads its input only through Unity's Input System (PlayerSettings.activeInputHandler = 1), and
// ships the package's desktop (Windows) build: without its #if UNITY_ANDROID layouts the Android player's
// keyboard and gamepad events find no device and are dropped, only the generic Touchscreen gets through.
// So, as ValDroid does for Valheim, the on-screen controls write a small state block in il2mono
// (unity/src/main/cpp/il2mono/pad.c, over JNI from com.stormdroid.game.NativeInput) and this bridge queues it
// before every Input System update to its own virtual Gamepad, Keyboard and Mouse.
//
// The launcher (NativeEngine) adds AtsInputBridge.dll to ScriptingAssemblies.json and StormDroid.Bridge.Initialize
// to RuntimeInitializeOnLoads.json, so the player loads it with the game. Built by build_input_bridge.sh.
namespace StormDroid
{
    public static class Bridge
    {
        // pad.c: lets the overlay's wheel (pinch, mouse wheel) through to the virtual mouse.
        [MethodImpl(MethodImplOptions.InternalCall)]
        static extern void SetScrollReady(bool ready);

        [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.BeforeSceneLoad)]
        static void Initialize()
        {
            try
            {
                VirtualPad.Initialize();
                VirtualKeyboardMouse.Initialize();
                Handheld.Initialize();
                SetScrollReady(true);
                Debug.Log("STORMDROID input bridge: started");
            }
            catch (Exception e)
            {
                Debug.Log("STORMDROID input bridge: start failed: " + e);
            }
        }
    }

    // The game's Steam Deck mode. Against the Storm switches between its keyboard and gamepad schemes
    // (prompts, the virtual pointer) only on a handheld: InputService.UpdateDevice runs while
    // IPlatformService.IsHandheldDevice(), which SteamService answers from SteamUtils.IsSteamRunningOnSteamDeck.
    // There is no Steam here, so the bridge sets SteamService.isSteamdeck itself once the services exist
    // (MainController.Instance.AppServices.PlatformService). ATS_HANDHELD=0 in the environment turns it off.
    public static class Handheld
    {
        static PropertyInfo s_Instance, s_AppServices, s_Platform;
        static object s_Done;

        internal static void Initialize()
        {
            if (Environment.GetEnvironmentVariable("ATS_HANDHELD") == "0")
                return;
            var controller = Type.GetType("Eremite.Controller.MainController, Assembly-CSharp");
            if (controller == null)
            {
                Debug.Log("STORMDROID input bridge: no MainController, handheld mode off");
                return;
            }
            s_Instance = controller.GetProperty("Instance", BindingFlags.Public | BindingFlags.Static);
            InputSystem.onBeforeUpdate += Apply;
        }

        // Every update until it has applied to the current platform service (cheap: a few reflection reads).
        static void Apply()
        {
            try
            {
                object main = s_Instance?.GetValue(null);
                if (main == null)
                    return;
                if (s_AppServices == null)
                    s_AppServices = main.GetType().GetProperty("AppServices", BindingFlags.Public | BindingFlags.Instance);
                object services = s_AppServices?.GetValue(main);
                if (services == null)
                    return;
                if (s_Platform == null)
                    s_Platform = services.GetType().GetProperty("PlatformService", BindingFlags.Public | BindingFlags.Instance);
                object platform = s_Platform?.GetValue(services);
                if (platform == null || ReferenceEquals(platform, s_Done))
                    return;
                var field = platform.GetType().GetField("isSteamdeck", BindingFlags.NonPublic | BindingFlags.Instance);
                if (field != null)
                    field.SetValue(platform, true);
                s_Done = platform;
                Debug.Log("STORMDROID input bridge: handheld mode " + (field != null ? "on" : "unavailable") + " (" + platform.GetType().Name + ")");
            }
            catch (Exception e)
            {
                Debug.Log("STORMDROID input bridge: handheld mode failed: " + e);
                InputSystem.onBeforeUpdate -= Apply;
            }
        }
    }

    // A virtual gamepad. Must match StormDroidPadState in il2mono/pad.c.
    public static class VirtualPad
    {
        [StructLayout(LayoutKind.Sequential)]
        struct NativeState
        {
            public uint sequence;
            public uint buttons; // bit = (int)GamepadButton
            public float leftX, leftY, rightX, rightY, leftTrigger, rightTrigger;
        }

        [MethodImpl(MethodImplOptions.InternalCall)]
        static extern IntPtr GetStatePointer();

        static Gamepad s_Pad;
        static IntPtr s_State;
        // pad.c starts at 0: a pad nothing has touched sends no state, so the game does not switch its
        // prompts to the gamepad scheme on its own.
        static uint s_LastSequence;

        internal static void Initialize()
        {
            s_State = GetStatePointer();
            if (s_State == IntPtr.Zero)
                return;
            s_Pad = InputSystem.AddDevice<Gamepad>("StormDroid Virtual Pad");
            InputSystem.onBeforeUpdate += Push;
            Debug.Log("STORMDROID input bridge: virtual gamepad added, id " + s_Pad.deviceId);
        }

        static unsafe void Push()
        {
            if (s_Pad == null || !s_Pad.added)
                return;
            var native = (NativeState*)s_State;
            uint sequence = native->sequence;
            if (sequence == s_LastSequence)
                return;
            s_LastSequence = sequence;
            var state = new GamepadState
            {
                buttons = native->buttons,
                leftStick = new Vector2(native->leftX, native->leftY),
                rightStick = new Vector2(native->rightX, native->rightY),
                leftTrigger = native->leftTrigger,
                rightTrigger = native->rightTrigger,
            };
            InputSystem.QueueStateEvent(s_Pad, state);
        }
    }

    // A virtual keyboard and mouse. Must match StormDroidKbmState in il2mono/pad.c: keys by GLFW code, the
    // mouse buttons, the cursor as a fraction of the screen (top-left origin), the delta and wheel since
    // the last take.
    public static class VirtualKeyboardMouse
    {
        const int KeyWords = 12;

        [StructLayout(LayoutKind.Sequential)]
        unsafe struct NativeState
        {
            public uint sequence;
            public fixed uint keys[KeyWords];
            public uint mouseButtons;     // bit 0 left, 1 right, 2 middle
            public float mouseX, mouseY;
            public float deltaX, deltaY;
            public float scroll;          // wheel notches, + = up
        }

        // False when nothing changed since lastSequence; otherwise copies the state and clears the
        // native delta and wheel.
        [MethodImpl(MethodImplOptions.InternalCall)]
        static extern unsafe bool Take(NativeState* state, uint lastSequence);

        static Keyboard s_Keyboard;
        static Mouse s_Mouse;
        static uint s_LastSequence = uint.MaxValue;
        static readonly uint[] s_LastKeys = new uint[KeyWords];
        static readonly Key[] s_GlfwToKey = BuildKeyTable();

        internal static void Initialize()
        {
            s_Keyboard = InputSystem.AddDevice<Keyboard>("StormDroid Virtual Keyboard");
            s_Mouse = InputSystem.AddDevice<Mouse>("StormDroid Virtual Mouse");
            InputSystem.onBeforeUpdate += Push;
            Debug.Log("STORMDROID input bridge: virtual keyboard and mouse added, ids " + s_Keyboard.deviceId + ", " + s_Mouse.deviceId);
        }

        static unsafe void Push()
        {
            if (s_Keyboard == null || !s_Keyboard.added || s_Mouse == null || !s_Mouse.added)
                return;
            NativeState native;
            if (!Take(&native, s_LastSequence))
                return;
            s_LastSequence = native.sequence;

            bool keysChanged = false;
            for (int w = 0; w < KeyWords; w++)
            {
                if (native.keys[w] != s_LastKeys[w])
                {
                    keysChanged = true;
                    s_LastKeys[w] = native.keys[w];
                }
            }
            if (keysChanged)
            {
                var keyboard = new KeyboardState();
                for (int code = 0; code < s_GlfwToKey.Length; code++)
                {
                    if ((native.keys[code >> 5] & (1u << (code & 31))) != 0 && s_GlfwToKey[code] != Key.None)
                        keyboard.Set(s_GlfwToKey[code], true);
                }
                InputSystem.QueueStateEvent(s_Keyboard, keyboard);
            }

            float width = Screen.width, height = Screen.height;
            var mouse = new MouseState
            {
                position = new Vector2(native.mouseX * width, (1f - native.mouseY) * height),
                delta = new Vector2(native.deltaX * width, -native.deltaY * height),
                // The Input System's wheel unit on the desktop is 120 per notch (the game's zoom expects it).
                scroll = new Vector2(0f, native.scroll * 120f),
            }
            .WithButton(MouseButton.Left, (native.mouseButtons & 1) != 0)
            .WithButton(MouseButton.Right, (native.mouseButtons & 2) != 0)
            .WithButton(MouseButton.Middle, (native.mouseButtons & 4) != 0);
            InputSystem.QueueStateEvent(s_Mouse, mouse);
        }

        // GLFW key code (com.stormdroid.game.NativeInput sends these) -> Input System key.
        static Key[] BuildKeyTable()
        {
            var t = new Key[KeyWords * 32];
            for (int i = 0; i < 26; i++) t[65 + i] = Key.A + i;
            t[48] = Key.Digit0;
            for (int i = 1; i <= 9; i++) t[48 + i] = Key.Digit1 + (i - 1);
            for (int i = 0; i < 12; i++) t[290 + i] = Key.F1 + i;
            for (int i = 0; i < 10; i++) t[320 + i] = Key.Numpad0 + i;
            t[32] = Key.Space;
            t[39] = Key.Quote;
            t[44] = Key.Comma;
            t[45] = Key.Minus;
            t[46] = Key.Period;
            t[47] = Key.Slash;
            t[59] = Key.Semicolon;
            t[61] = Key.Equals;
            t[91] = Key.LeftBracket;
            t[92] = Key.Backslash;
            t[93] = Key.RightBracket;
            t[96] = Key.Backquote;
            t[256] = Key.Escape;
            t[257] = Key.Enter;
            t[258] = Key.Tab;
            t[259] = Key.Backspace;
            t[260] = Key.Insert;
            t[261] = Key.Delete;
            t[262] = Key.RightArrow;
            t[263] = Key.LeftArrow;
            t[264] = Key.DownArrow;
            t[265] = Key.UpArrow;
            t[266] = Key.PageUp;
            t[267] = Key.PageDown;
            t[268] = Key.Home;
            t[269] = Key.End;
            t[280] = Key.CapsLock;
            t[340] = Key.LeftShift;
            t[341] = Key.LeftCtrl;
            t[342] = Key.LeftAlt;
            t[344] = Key.RightShift;
            t[345] = Key.RightCtrl;
            t[346] = Key.RightAlt;
            return t;
        }
    }
}

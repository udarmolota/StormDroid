# StormDroid

An Android launcher for **Against the Storm** (Eremite Games): the game's Windows version runs on Unity's own
ARM64 Android player, with nothing emulated.

- **Engine.** Unity 2021.3.45f2's Android player (the game's own Unity version, `libunity.so` arm64), its
  serialized-file target check patched so it accepts the game's Windows files.
- **C#.** The game's managed code runs on ARM64 Mono (Unity's fork) through **il2mono**, a `libil2cpp.so`
  replacement (`unity/src/main/cpp/il2mono`).
- **Graphics.** Vulkan through **vkshim**, a thin observing shim (`unity/src/main/cpp/vkshim`). The game ships
  only Direct3D 11 shaders, so they are retargeted to Vulkan (SPIR-V) ahead of time.
- **Input.** The game reads only Unity's Input System, which drops Android's own key and gamepad events. The
  on-screen controls (`app/.../ats`, derived from Zomdroid) feed a virtual keyboard, mouse and gamepad through
  JNI (`il2mono/pad.c`) and the managed input bridge (`tools/input_bridge`). The bridge also turns on the game's
  Steam Deck mode, so it switches between its keyboard and gamepad schemes.

Unofficial; not endorsed by Eremite Games. The user supplies their own copy of the game.

## Layout

| Path | What |
|---|---|
| `app/` | the launcher: instances, install from ZIP, settings, controls editor, the in-game overlay |
| `unity/` | the `:unity` module: `NativeUnityActivity` (own process), il2mono, vkshim |
| `unity/prepared/` | Unity's binaries (git-ignored), made by `tools/unity/prepare_unity.sh` |
| `tools/unity/` | `prepare_unity.sh`, `patch_libunity.py`, `xar_extract.py` |
| `tools/input_bridge/` | `InputBridge.cs` and its build script (output: `AtsInputBridge.dll`) |

## Building

1. Download Unity's *Android Support for Editor 2021.3.45f2* package (`UnitySetup-Android-Support-for-Editor-2021.3.45f2.pkg`)
   and run `sh tools/unity/prepare_unity.sh <that .pkg>` (Python 3 and Windows' `tar.exe`, or set `TAR` and `PYTHON`).
2. Build the input bridge into the prepared set:
   `sh tools/input_bridge/build_input_bridge.sh unity/prepared/assets/stormdroid_native/Managed <game>/Against\ the\ Storm_Data/Managed <unity-2021.3-mono-bcl> unity/prepared/assets/stormdroid_native/Managed`
   (the BCL is the `unityjit-linux` class library folder of a Unity 2021.3.45f2 editor).
3. `local.properties` needs `sdk.dir`; `unityPrepared=<folder>` selects another prepared set.
4. Build with Android Studio or `gradlew assembleDebug`.

## The game's files

The instance is the game folder as a PC install has it (`Against the Storm.exe`, `Against the Storm_Data`).
For now its preparation for Android is done on a PC:

- shaders retargeted to Vulkan with the D3D11 programs dropped;
- `Managed/` given Unity 2021.3's Mono class libraries in place of the Windows ones.

Moving both steps into the launcher (on the phone, at install time) is the next stage.

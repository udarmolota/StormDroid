# StormDroid

**StormDroid** is an unofficial, community-developed launcher that runs the Windows version of
[Against the Storm](https://eremitegames.com/against-the-storm/) on ARM64 Android devices, natively, with
Vulkan graphics and touch controls.

> [!NOTE]
> StormDroid requires the Windows version of Against the Storm. Steam users can download the game they own
> right in the app (the Steam Mobile app with Steam Guard confirms the login); you can also install your own
> copy of the game as a ZIP.

> [!WARNING]
> **The app is in beta.** It supports both main phone GPUs, **Snapdragon (Adreno)** and **Mali** (MediaTek,
> Tensor, Exynos). It has been tested on two phones so far. Xclipse GPUs are not tested yet.

## Features

- ✔️ Runs **Against the Storm** on ARM64 phones — the game's own Unity engine built for Android, nothing
  emulated
- ✔️ **Shaders converted on the phone** — before the first launch, the game's DirectX shaders are converted
  for Vulkan, once per instance, in about half a minute
- ✔️ **Mali support** — the game's PC textures are converted to a format the GPU reads while the game loads
- ✔️ **Graphics profiles** — Low and Very low in the instance settings, written into the game's own settings
- ✔️ **In-app Steam download** — the game is saved as a ZIP in *Download/StormDroid*; **Create an instance**
  opens instance creation with it
- ✔️ **Install from your own ZIP** — the game folder from your PC, zipped
- ✔️ **Editable on-screen touch controls** — a keyboard + mouse layout, move / resize / opacity, buttons
  bound to any key or mouse action, an icon pack (Q / E rotate the camera, R / N rotate buildings)
- ✔️ **Save and settings import & export** — bring your settlement over from PC
- ✔️ **Against the Storm news** from Steam on the home screen
- ✔️ **Bug reports** with logs, sent from your own email app
- ✔️ Russian, Spanish, Portuguese and Simplified Chinese translations

## Project status & what to expect

StormDroid is very young — the first release came out in **October 2026** — and is built by **one person**, on
the same approach as [RimDroid](https://github.com/udarmolota/RimDroid). A few honest notes:

- **Tested on two devices** so far: Galaxy S25 Ultra (Snapdragon) and a Mali-G615 phone. Other phones should
  work; how well they play is still unknown — reports help.

## System requirements

- Android 11+, ARM64; an **Adreno (Snapdragon)** or **Mali** GPU (Xclipse not tested yet)
- About **15 GB free storage** for the first install (download, ZIP and the installed game); the ZIP can be
  deleted afterwards
- A copy of Against the Storm you own (Windows version, e.g. on Steam)

## Roadmap

- [x] Against the Storm on the native engine, Vulkan graphics
- [x] Shader conversion on the phone
- [x] Steam download
- [x] Save / settings import & export
- [x] **Mali GPUs**: the game's textures converted to a format they support
- [ ] **Xclipse GPUs**: test them
- [ ] **Cloud saves** with Steam
- [ ] **GOG** download, maybe **Epic Games Store** later
- [ ] Controls layout import & export
- [ ] Optimization: frame rate, loading times, battery and heat

## How it works

Against the Storm ships only for Windows and Direct3D 11. StormDroid runs the game's own Unity version
(2021.3) as Unity's **Android player**: the game's C# code runs on a native ARM64 build of Unity's Mono, and
graphics go through your phone's GPU with Vulkan. The game's shaders are converted from Direct3D 11 to
Vulkan on the phone by **ShaderDroid**, the launcher's shader converter. The on-screen controls are fed
into the game's own input system as a keyboard and mouse.

## Build

<details>
<summary>For developers</summary>

1. Download Unity's *Android Support for Editor 2021.3.45f2* package
   (`UnitySetup-Android-Support-for-Editor-2021.3.45f2.pkg`) and run
   `UNIX_BCL=<unityjit-linux> sh tools/unity/prepare_unity.sh <that .pkg>` (Python 3 and Windows' `tar.exe`, or
   set `TAR` and `PYTHON`). `UNIX_BCL` is `Editor/Data/MonoBleedingEdge/lib/mono/unityjit-linux` of the Unity
   2021.3.45f2 Linux editor (`Unity-2021.3.45f2.tar.xz`): the Unix class libraries that replace the game's
   Windows ones.
2. Build the input bridge into the prepared set:
   `sh tools/input_bridge/build_input_bridge.sh unity/prepared/assets/stormdroid_native/Managed <game>/Against\ the\ Storm_Data/Managed <unity-2021.3-mono-bcl> unity/prepared/assets/stormdroid_native/Managed`
   (the BCL is the `unityjit-linux` class library folder of a Unity 2021.3.45f2 editor).
3. Put `libshaderdroid.so` (arm64, from the ShaderDroid repository, closed source) into
   `app/src/main/jniLibs/arm64-v8a/`. It is git-ignored.
4. `local.properties` needs `sdk.dir`; `unityPrepared=<folder>` selects another prepared set.
5. Build with Android Studio or `gradlew assembleDebug`. A release build is signed with the key that
   `keystore.properties` names (copy `keystore.properties.example`; never committed), else with the debug key.

| Path | What |
|---|---|
| `app/` | the launcher: instances, install from ZIP, Steam download, settings, controls editor |
| `unity/` | the `:unity` module: `NativeUnityActivity` (own process), il2mono (`libil2cpp.so` replacement), vkshim |
| `unity/prepared/` | Unity's binaries (git-ignored), made by `tools/unity/prepare_unity.sh` |
| `tools/unity/` | `prepare_unity.sh`, `patch_libunity.py`, `xar_extract.py` |
| `tools/input_bridge/` | `InputBridge.cs` and its build script (output: `AtsInputBridge.dll`) |

</details>

## Supporting development

This is an independent project. To help keep it going, contributions are welcome via
[Ko-Fi](https://ko-fi.com/udarmolota).

## Feedback

Please report issues or request features via
[GitHub Issues](https://github.com/udarmolota/StormDroid/issues). Tap **Report a bug** in the app's side
menu right after the problem happens — the report carries the logs we need.

News and discussion: [Reddit](https://www.reddit.com/r/UdarMolotaDev/) · TG: [udarmolota](http://t.me/udarmolota)

## Credits & Third-Party Sources

- [Unity](https://unity.com) Android player — the game's own engine, by Unity Technologies (proprietary, not in
  this repository)
- [Mono (Unity fork)](https://github.com/Unity-Technologies/mono) — native ARM64 runtime and class libraries
- [JavaSteam](https://github.com/Longi94/JavaSteam) — Steam login and downloads
- [Zomdroid](https://github.com/udarmolota/zomdroid) (MIT) — the on-screen controls layout model and the
  controls editor are ported from it
- [RimDroid](https://github.com/udarmolota/RimDroid) — sibling launcher; the Steam download and launcher screens
  come from it

Full license texts: [NOTICE](NOTICE).

Unofficial; not endorsed by Eremite Games.

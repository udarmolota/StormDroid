#!/bin/sh
# Fill unity/prepared/ with the Unity 2021.3.45f2 Android player files the :unity module packages (Against the
# Storm's Unity version). Unity's binaries are never committed; this script extracts them from Unity's own
# Android Support package (UnitySetup-Android-Support-for-Editor-2021.3.45f2.pkg, download it from Unity).
#
# usage: prepare_unity.sh <android-support.pkg> [<out-dir>]   (out-dir default: unity/prepared)
#
# Result:
#   jniLibs/arm64-v8a/libunity.so   build-target check patched + libvkshim.so (patch_libunity.py)
#   jniLibs/arm64-v8a/libmain.so
#   libs/unity-classes.jar
#   symbols/arm64-v8a/libunity.sym.so   Unity's symbol file (debugging only, not for the APK)
#   assets/bin/Data/                boot.config, unity_app_guid, "unity default resources" (Data/ root),
#                                   empty Managed/Resources/mscorlib.dll-resources.dat
#   assets/stormdroid_native/Managed/  UnityEngine*.dll of the 2021.3 Android Mono variation (they replace the
#                                   game's Windows copies); the input bridge (tools/input_bridge) goes here too.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
PKG=${1:?usage: prepare_unity.sh <android-support.pkg> [<out-dir>]}
OUT=${2:-$ROOT/unity/prepared}
PY=${PYTHON:-python}
XAR=$HERE/xar_extract.py
TAR=${TAR:-/c/Windows/System32/tar.exe}       # bsdtar: reads the cpio payload, GNU tar cannot
WORK=$OUT.work
rm -rf "$WORK"; mkdir -p "$WORK"
trap 'rm -rf "$WORK"' EXIT

VARIATION=Variations/il2cpp/Release
echo "extracting the Android player from $PKG"
"$PY" -I "$XAR" "$PKG" get TargetSupport.pkg.tmp/Payload "$WORK/Payload"
(cd "$WORK" && "$TAR" -xf Payload "./$VARIATION/Libs/arm64-v8a/libunity.so" "./$VARIATION/Libs/arm64-v8a/libmain.so" \
    "./$VARIATION/Classes/classes.jar" "./$VARIATION/Symbols/arm64-v8a/libunity.sym.so" \
    "./Variations/mono/Managed/*" "./Data/unity default resources")

rm -rf "$OUT"
mkdir -p "$OUT/jniLibs/arm64-v8a" "$OUT/libs" "$OUT/symbols/arm64-v8a"
"$PY" -I "$HERE/patch_libunity.py" "$WORK/$VARIATION/Libs/arm64-v8a/libunity.so" "$OUT/jniLibs/arm64-v8a/libunity.so"
cp "$WORK/$VARIATION/Libs/arm64-v8a/libmain.so" "$OUT/jniLibs/arm64-v8a/"
cp "$WORK/$VARIATION/Classes/classes.jar" "$OUT/libs/unity-classes.jar"
cp "$WORK/$VARIATION/Symbols/arm64-v8a/libunity.sym.so" "$OUT/symbols/arm64-v8a/"

DATA="$OUT/assets/bin/Data"
mkdir -p "$DATA/Managed/Resources"
# The Android player opens "Data/unity default resources" at the data root (2021.3 package: Data/ as in 2022.3).
cp "$WORK/Data/unity default resources" "$DATA/"
# The keys of a Unity Android build's boot.config; the ids are ours: the player does not start without
# unity_app_guid.
cat > "$DATA/boot.config" <<'EOF'
gfx-threading-mode=4
wait-for-native-debugger=0
hdr-display-enabled=0
gc-max-time-slice=3
androidStartInFullscreen=1
androidRenderOutsideSafeArea=1
build-guid=3a9d6e21c4b84f0f9e57b1d2a6c8e403
EOF
printf '%s' "8c41f2d7-6b09-4e3a-9f15-2d7a0c5e9b84" > "$DATA/unity_app_guid"
# Extracted by the player on first start; the content is IL2CPP's and unused by il2mono, it only has to exist.
: > "$DATA/Managed/Resources/mscorlib.dll-resources.dat"

MANAGED="$OUT/assets/stormdroid_native/Managed"
mkdir -p "$MANAGED"
cp "$WORK/Variations/mono/Managed/"UnityEngine*.dll "$MANAGED/"
echo "prepared $OUT ($(ls "$MANAGED" | wc -l) managed overrides)"

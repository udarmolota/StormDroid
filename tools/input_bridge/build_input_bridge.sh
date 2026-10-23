#!/bin/sh
# Build AtsInputBridge.dll (InputBridge.cs here), the managed input bridge for Against the Storm.
#
# Compiled against the game's own Unity.InputSystem.dll (only as a reference: nothing of the game goes into
# the APK; at run time the bridge binds to the game's copy) and the player's UnityEngine*.dll.
#
# usage: build_input_bridge.sh <unity-managed-dir> <game-managed-dir> <bcl-dir> <out-dir>
#   <unity-managed-dir>  UnityEngine*.dll of the 2021.3 Android player (unity/prepared/assets/stormdroid_native/Managed)
#   <game-managed-dir>   the game's Against the Storm_Data/Managed (Unity.InputSystem.dll)
#   <bcl-dir>            Mono's class libraries of the same Unity (unityjit-linux of 2021.3.45f2)
#   <out-dir>            receives AtsInputBridge.dll
# environment: CSC, the C# compiler command; default: the Roslyn that ships with the Unity Editor
#   ("<Editor>/Data/NetCoreRuntime/dotnet.exe" "<Editor>/Data/DotNetSdkRoslyn/csc.dll").
set -e
USAGE="usage: build_input_bridge.sh <unity-managed-dir> <game-managed-dir> <bcl-dir> <out-dir>"
UNITY=${1:?$USAGE}
GAME=${2:?$USAGE}
BCL=${3:?$USAGE}
OUT=${4:?$USAGE}
HERE=$(cd "$(dirname "$0")" && pwd)
EDITOR="/c/Program Files/Unity/Hub/Editor/6000.0.75f1/Editor/Data"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

REFS="$WORK/refs.rsp"
: > "$REFS"
for f in "$BCL/mscorlib.dll" "$BCL/System.dll" "$BCL/System.Core.dll" "$BCL"/Facades/netstandard.dll \
         "$UNITY"/UnityEngine.dll "$UNITY"/UnityEngine.CoreModule.dll "$UNITY"/UnityEngine.InputModule.dll \
         "$GAME/Unity.InputSystem.dll"; do
    [ -f "$f" ] && printf '%s\n' "-r:\"$(cygpath -w "$f" 2>/dev/null || echo "$f")\"" >> "$REFS"
done
mkdir -p "$OUT"
OUTW=$(cygpath -w "$OUT/AtsInputBridge.dll" 2>/dev/null || echo "$OUT/AtsInputBridge.dll")
SRC=$(cygpath -w "$HERE/InputBridge.cs" 2>/dev/null || echo "$HERE/InputBridge.cs")
if [ -n "$CSC" ]; then
    # shellcheck disable=SC2086
    $CSC -nologo -noconfig -nostdlib+ -target:library -langversion:9.0 -unsafe+ -optimize+ -deterministic \
        -out:"$OUTW" @"$REFS" "$SRC"
else
    "$EDITOR/NetCoreRuntime/dotnet.exe" "$EDITOR/DotNetSdkRoslyn/csc.dll" -nologo -noconfig -nostdlib+ -target:library \
        -langversion:9.0 -unsafe+ -optimize+ -deterministic -out:"$OUTW" @"$(cygpath -w "$REFS" 2>/dev/null || echo "$REFS")" "$SRC"
fi
echo "built $OUT/AtsInputBridge.dll"

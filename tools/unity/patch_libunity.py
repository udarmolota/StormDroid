"""Patch the Android libunity.so of Unity 2021.3.45f2 (il2cpp Release arm64-v8a, Against the Storm's Unity):

  * build-target check in SerializedFile::ReadMetadata<false> (little-endian files; found with Unity's own
    Symbols/arm64-v8a/libunity.sym.so), so the player accepts the game's Windows-tagged serialized files:
        0x5deab0  cmp  w1, #0xd   ->  mov w1, #0xd   (the file is treated as an Android file)
        0x5deab8  b.ne <error>    ->  nop
  * the one dlopen("libvulkan.so") name -> "libvkshim.so" (same length; the Vulkan shim in
    unity/src/main/cpp/vkshim loads the system libvulkan.so itself).

The original bytes and the file's SHA-256 are verified first, so the script refuses any other libunity build.
usage: patch_libunity.py IN OUT
"""
import hashlib, struct, sys

# Variations/il2cpp/Release/Libs/arm64-v8a/libunity.so of UnitySetup-Android-Support-for-Editor-2021.3.45f2.pkg
ORIG_SHA256 = "dab8b8d67f215b8f8c83870a3ef4b118e5a0e0e2c225eb1f48e23ba9f1bc582c"
CMP_ADDR, BNE_ADDR = 0x5deab0, 0x5deab8
CMP_W1_13 = 0x7100343F   # cmp w1, #0xd
MOV_W1_13 = 0x528001A1   # mov w1, #0xd
NOP = 0xD503201F
VULKAN, SHIM = b"libvulkan.so\0", b"libvkshim.so\0"


def file_offset(d: bytes, va: int) -> int:
    ph = struct.unpack_from("<Q", d, 0x20)[0]; n = struct.unpack_from("<H", d, 0x38)[0]
    for k in range(n):
        t, f, o, v, p, fs = struct.unpack_from("<IIQQQQ", d, ph + k * 56)
        if t == 1 and v <= va < v + fs and f & 1:
            return o + va - v
    raise SystemExit("address not in an executable segment")


def main():
    src, dst = sys.argv[1], sys.argv[2]
    d = bytearray(open(src, "rb").read())
    if ORIG_SHA256 and hashlib.sha256(d).hexdigest() != ORIG_SHA256:
        raise SystemExit("not the analysed libunity (SHA-256 differs)")
    a, b = file_offset(d, CMP_ADDR), file_offset(d, BNE_ADDR)
    cur_a, cur_b = struct.unpack_from("<I", d, a)[0], struct.unpack_from("<I", d, b)[0]
    if cur_a != CMP_W1_13 or (cur_b & 0xFF00001F) != 0x54000001:
        raise SystemExit(f"unexpected bytes: {cur_a:#x} {cur_b:#x} - not the analysed libunity")
    struct.pack_into("<I", d, a, MOV_W1_13)
    struct.pack_into("<I", d, b, NOP)
    if d.count(VULKAN) != 1:
        raise SystemExit(f"expected one {VULKAN!r} in libunity, found {d.count(VULKAN)}")
    v = d.find(VULKAN)
    d[v:v + len(SHIM)] = SHIM
    open(dst, "wb").write(d)
    print("patched", dst)


if __name__ == "__main__":
    main()

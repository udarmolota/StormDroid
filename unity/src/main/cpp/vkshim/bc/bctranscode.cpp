// BC blocks decoded with bcdec and encoded again with etcpak, one 4x4 block at a time, in place: BC7,
// BC3 and BC2 become ETC2 RGBA8 (16 bytes), BC1 ETC2 RGB8 (8), BC4 EAC R11 (8), BC5 EAC RG11 (16).
// etcpak takes pixels as BGRA words (its own PNG loader reads them so), and its EAC encoders take the
// channels from the red and green bytes of those words.
#include "bctranscode.h"

#include <algorithm>
#include <atomic>
#include <thread>
#include <vector>

#include <vulkan/vulkan.h>

#define BCDEC_IMPLEMENTATION
#define BCDEC_STATIC
#include "etcpak/bcdec.h"
#include "etcpak/ProcessRGB.hpp"

namespace {

enum Kind { NONE, RGB8, RGBA8, R11, RG11 };

Kind kindOf(int format, int* bc) {
    switch (format) {
        case VK_FORMAT_BC1_RGB_UNORM_BLOCK: case VK_FORMAT_BC1_RGB_SRGB_BLOCK:
        case VK_FORMAT_BC1_RGBA_UNORM_BLOCK: case VK_FORMAT_BC1_RGBA_SRGB_BLOCK: *bc = 1; return RGB8;
        case VK_FORMAT_BC2_UNORM_BLOCK: case VK_FORMAT_BC2_SRGB_BLOCK: *bc = 2; return RGBA8;
        case VK_FORMAT_BC3_UNORM_BLOCK: case VK_FORMAT_BC3_SRGB_BLOCK: *bc = 3; return RGBA8;
        case VK_FORMAT_BC7_UNORM_BLOCK: case VK_FORMAT_BC7_SRGB_BLOCK: *bc = 7; return RGBA8;
        case VK_FORMAT_BC4_UNORM_BLOCK: *bc = 4; return R11;
        case VK_FORMAT_BC5_UNORM_BLOCK: *bc = 5; return RG11;
        default: return NONE;
    }
}

void block(Kind kind, int bc, uint8_t* blk) {
    uint32_t px[16];
    if (kind == R11) {
        uint8_t r[16];
        bcdec_bc4(blk, r, 4);
        for (int i = 0; i < 16; i++) px[i] = (uint32_t)r[i] << 16;
        CompressEacR(px, (uint64_t*)blk, 1, 4);
        return;
    }
    if (kind == RG11) {
        uint8_t rg[32];
        bcdec_bc5(blk, rg, 8);
        for (int i = 0; i < 16; i++) px[i] = (uint32_t)rg[2 * i] << 16 | (uint32_t)rg[2 * i + 1] << 8;
        CompressEacRg(px, (uint64_t*)blk, 1, 4);
        return;
    }
    uint8_t rgba[64];
    switch (bc) {
        case 1: bcdec_bc1(blk, rgba, 16); break;
        case 2: bcdec_bc2(blk, rgba, 16); break;
        case 3: bcdec_bc3(blk, rgba, 16); break;
        default: bcdec_bc7(blk, rgba, 16); break;
    }
    for (int i = 0; i < 16; i++) {
        const uint8_t* p = rgba + 4 * i;
        px[i] = (uint32_t)p[2] | (uint32_t)p[1] << 8 | (uint32_t)p[0] << 16 | (uint32_t)p[3] << 24;
    }
    if (kind == RGBA8) CompressEtc2Rgba(px, (uint64_t*)blk, 1, 4, true);
    else CompressEtc2Rgb(px, (uint64_t*)blk, 1, 4, true);
}

// A row of blocks: the unit of work the threads share.
struct Row { uint8_t* data; uint32_t blocks, blockBytes; Kind kind; int bc; };

void rows(const BcRegion* regions, uint32_t count, std::vector<Row>& out) {
    for (uint32_t i = 0; i < count; i++) {
        const BcRegion& r = regions[i];
        int bc = 0;
        Kind kind = kindOf(r.format, &bc);
        if (kind == NONE) continue;
        uint32_t bytes = (kind == RGB8 || kind == R11) ? 8 : 16;
        for (uint32_t s = 0; s < r.slices; s++)
            for (uint32_t y = 0; y < r.bh; y++)
                out.push_back({ r.data + (size_t)s * r.slicePitch + (size_t)y * r.rowPitch, r.bw, bytes, kind, bc });
    }
}

}  // namespace

extern "C" uint64_t bc_transcode(const BcRegion* regions, uint32_t count) {
    std::vector<Row> work;
    rows(regions, count, work);
    uint64_t total = 0;
    for (const Row& w : work) total += w.blocks;
    if (total == 0) return 0;
    auto run = [&work](std::atomic<size_t>* next) {
        for (size_t i; (i = next->fetch_add(1)) < work.size();) {
            const Row& w = work[i];
            for (uint32_t b = 0; b < w.blocks; b++) block(w.kind, w.bc, w.data + (size_t)b * w.blockBytes);
        }
    };
    std::atomic<size_t> next{0};
    // Small uploads (a few icons) on the calling thread; a texture's worth spread over the cores.
    unsigned threads = total < 4096 ? 1 : std::max(1u, std::min(8u, std::thread::hardware_concurrency()));
    std::vector<std::thread> pool;
    for (unsigned t = 1; t < threads; t++) pool.emplace_back(run, &next);
    run(&next);
    for (std::thread& t : pool) t.join();
    return total;
}

// BC -> ETC2/EAC block re-encoding for GPUs without BC (see bcconv.c).
#ifndef STORMDROID_BCTRANSCODE_H
#define STORMDROID_BCTRANSCODE_H
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// One copy region's blocks in an upload buffer: rows of bw blocks, rowPitch bytes apart; slices of bh
// rows, slicePitch bytes apart. Re-encoded in place: the target format has the same block size.
typedef struct {
    uint8_t* data;
    uint32_t bw, bh, rowPitch, slices, slicePitch;
    int format;   // the VkFormat the engine asked for (a BC one)
} BcRegion;

// Re-encodes every region, spread over the CPU's cores when there is enough work. Returns the number of
// blocks done; regions in a format without an encoder here are left as they are.
uint64_t bc_transcode(const BcRegion* regions, uint32_t count);

#ifdef __cplusplus
}
#endif
#endif

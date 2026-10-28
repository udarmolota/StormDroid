// BC textures on GPUs without BC (Mali, Xclipse, PowerVR).
//
// Against the Storm ships its textures for PC GPUs: DXT1, DXT5 and BC7 (BC1, BC3, BC7). A GPU without
// textureCompressionBC cannot sample them, and Unity then unpacks every one to RGBA32 on load: 4-8x
// the memory and slow loads. Here the engine is told BC is there, and every BC texture is created in
// the ETC2/EAC format with the same block size instead - BC7/BC3/BC2 -> ETC2 RGBA8, BC1 -> ETC2 RGB8,
// BC4 -> EAC R11, BC5 -> EAC RG11, 16 or 8 bytes per 4x4 block either way - so every size, offset and
// copy region the engine computes stays right. The blocks are re-encoded in place in the engine's
// upload buffer just before the command buffer that copies them reaches the GPU (bctranscode.cpp:
// bcdec, etcpak), and the memory is flushed for the GPU after that.
//
// BC1's one-bit alpha has no counterpart in ETC2 RGB8 and is lost; BC6H and signed BC4/BC5 have no
// encoder here (created as asked, which a GPU without BC refuses: the game's few BC6H cubemaps).
//
// On when the GPU has no textureCompressionBC. STORMDROID_BC_CONVERT=1 turns it on on any GPU (a test
// on an Adreno phone), =0 off even without BC (Unity then unpacks the textures itself).
//
// What the re-encoding needs to find the engine's blocks: which images were created converted and
// from which BC format, where each buffer is bound, where each memory object is mapped. A copy into a
// converted image is noted for its command buffer and done at that command buffer's vkQueueSubmit:
// by then the engine has written its upload buffer, and the GPU has not read it yet. While converting,
// a memory object stays mapped from its first vkMapMemory until it is freed: the engine may write an
// upload buffer, unmap it and only later submit the copy that reads it. Real functions are kept once
// for the process: Unity creates one device.
#include "bcconv.h"
#include "bctranscode.h"

#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "VKSHIM", __VA_ARGS__)

// ------------------------------------------------------------------ on or off

static atomic_int g_conv = -1;      // -1: not decided yet
static atomic_int g_real_bc = -1;   // the GPU's own textureCompressionBC; -1: not asked yet
static PFN_vkGetPhysicalDeviceFeatures g_loader_features;
static PFN_vkFlushMappedMemoryRanges g_loader_flush;

void bcconv_set_loader(void* lib)
{
    if (!lib)
        return;
    g_loader_features = (PFN_vkGetPhysicalDeviceFeatures)dlsym(lib, "vkGetPhysicalDeviceFeatures");
    g_loader_flush = (PFN_vkFlushMappedMemoryRanges)dlsym(lib, "vkFlushMappedMemoryRanges");
}

// STORMDROID_BC_CONVERT: 1 or 0 forces, -1 when unset.
static int forced(void)
{
    const char* e = getenv("STORMDROID_BC_CONVERT");
    return e && e[0] == '1' ? 1 : e && e[0] == '0' ? 0 : -1;
}

static void decide(int real_bc)
{
    int expected = -1;
    if (real_bc >= 0)
        atomic_compare_exchange_strong(&g_real_bc, &expected, real_bc);
    if (atomic_load(&g_conv) >= 0)
        return;
    int f = forced(), bc = atomic_load(&g_real_bc);
    if (f < 0 && bc < 0)
        return;   // decided once the GPU's features are known
    int on = f >= 0 ? f : !bc;
    expected = -1;
    if (atomic_compare_exchange_strong(&g_conv, &expected, on))
        LOG("BC conversion %s (GPU's own BC: %s%s)", on ? "ON: BC textures are created as ETC2/EAC" : "off",
            bc < 0 ? "not asked" : bc ? "yes" : "no", f >= 0 ? "; forced by STORMDROID_BC_CONVERT" : "");
}

// The GPU's own answer, asked once, before anything the engine is told about it.
static void decide_for(VkPhysicalDevice gpu)
{
    if (atomic_load(&g_conv) >= 0)
        return;
    if (atomic_load(&g_real_bc) < 0 && gpu && g_loader_features)
    {
        VkPhysicalDeviceFeatures f;
        memset(&f, 0, sizeof(f));
        g_loader_features(gpu, &f);
        decide(f.textureCompressionBC ? 1 : 0);
    }
    else
        decide(-1);
}

bool bcconv_on(void)
{
    if (atomic_load(&g_conv) < 0)
        decide(-1);
    return atomic_load(&g_conv) == 1;
}

static bool is_bc(VkFormat f)
{
    return f >= VK_FORMAT_BC1_RGB_UNORM_BLOCK && f <= VK_FORMAT_BC7_SRGB_BLOCK;
}

// The format a BC texture is created in when converting; UNDEFINED where there is no encoder here.
static VkFormat substitute(VkFormat f)
{
    switch (f)
    {
    case VK_FORMAT_BC1_RGB_UNORM_BLOCK: case VK_FORMAT_BC1_RGBA_UNORM_BLOCK: return VK_FORMAT_ETC2_R8G8B8_UNORM_BLOCK;
    case VK_FORMAT_BC1_RGB_SRGB_BLOCK: case VK_FORMAT_BC1_RGBA_SRGB_BLOCK: return VK_FORMAT_ETC2_R8G8B8_SRGB_BLOCK;
    case VK_FORMAT_BC2_UNORM_BLOCK: case VK_FORMAT_BC3_UNORM_BLOCK: case VK_FORMAT_BC7_UNORM_BLOCK:
        return VK_FORMAT_ETC2_R8G8B8A8_UNORM_BLOCK;
    case VK_FORMAT_BC2_SRGB_BLOCK: case VK_FORMAT_BC3_SRGB_BLOCK: case VK_FORMAT_BC7_SRGB_BLOCK:
        return VK_FORMAT_ETC2_R8G8B8A8_SRGB_BLOCK;
    case VK_FORMAT_BC4_UNORM_BLOCK: return VK_FORMAT_EAC_R11_UNORM_BLOCK;
    case VK_FORMAT_BC5_UNORM_BLOCK: return VK_FORMAT_EAC_R11G11_UNORM_BLOCK;
    default: return VK_FORMAT_UNDEFINED;
    }
}

// The format to ask the driver about: the substitute while converting, else the format itself.
static VkFormat query_format(VkFormat f)
{
    if (!is_bc(f) || !bcconv_on())
        return f;
    return substitute(f);   // UNDEFINED: the driver reports no support, as it should
}

// ------------------------------------------------------------------ instance level: what the engine is told

static PFN_vkGetPhysicalDeviceFeatures r_features;
static PFN_vkGetPhysicalDeviceFeatures2 r_features2, r_features2khr;
static PFN_vkGetPhysicalDeviceFormatProperties r_format;
static PFN_vkGetPhysicalDeviceFormatProperties2 r_format2, r_format2khr;
static PFN_vkGetPhysicalDeviceImageFormatProperties r_image_format;
static PFN_vkGetPhysicalDeviceImageFormatProperties2 r_image_format2, r_image_format2khr;

static VKAPI_ATTR void VKAPI_CALL bc_GetPhysicalDeviceFeatures(VkPhysicalDevice gpu, VkPhysicalDeviceFeatures* f)
{
    r_features(gpu, f);
    decide(f->textureCompressionBC ? 1 : 0);
    if (bcconv_on())
        f->textureCompressionBC = VK_TRUE;
}

static void features2(PFN_vkGetPhysicalDeviceFeatures2 real, VkPhysicalDevice gpu, VkPhysicalDeviceFeatures2* f)
{
    real(gpu, f);
    decide(f->features.textureCompressionBC ? 1 : 0);
    if (bcconv_on())
        f->features.textureCompressionBC = VK_TRUE;
}

static VKAPI_ATTR void VKAPI_CALL bc_GetPhysicalDeviceFeatures2(VkPhysicalDevice gpu, VkPhysicalDeviceFeatures2* f)
{ features2(r_features2, gpu, f); }
static VKAPI_ATTR void VKAPI_CALL bc_GetPhysicalDeviceFeatures2KHR(VkPhysicalDevice gpu, VkPhysicalDeviceFeatures2* f)
{ features2(r_features2khr, gpu, f); }

static VKAPI_ATTR void VKAPI_CALL bc_GetPhysicalDeviceFormatProperties(VkPhysicalDevice gpu, VkFormat format,
                                                                      VkFormatProperties* p)
{
    decide_for(gpu);
    VkFormat q = query_format(format);
    if (q == VK_FORMAT_UNDEFINED)
    {
        memset(p, 0, sizeof(*p));
        return;
    }
    r_format(gpu, q, p);
}

static void format2(PFN_vkGetPhysicalDeviceFormatProperties2 real, VkPhysicalDevice gpu, VkFormat format,
                    VkFormatProperties2* p)
{
    decide_for(gpu);
    VkFormat q = query_format(format);
    real(gpu, q == VK_FORMAT_UNDEFINED ? format : q, p);
    if (q == VK_FORMAT_UNDEFINED)
        memset(&p->formatProperties, 0, sizeof(p->formatProperties));
}

static VKAPI_ATTR void VKAPI_CALL bc_GetPhysicalDeviceFormatProperties2(VkPhysicalDevice gpu, VkFormat format,
                                                                       VkFormatProperties2* p)
{ format2(r_format2, gpu, format, p); }
static VKAPI_ATTR void VKAPI_CALL bc_GetPhysicalDeviceFormatProperties2KHR(VkPhysicalDevice gpu, VkFormat format,
                                                                          VkFormatProperties2* p)
{ format2(r_format2khr, gpu, format, p); }

static VKAPI_ATTR VkResult VKAPI_CALL bc_GetPhysicalDeviceImageFormatProperties(
    VkPhysicalDevice gpu, VkFormat format, VkImageType type, VkImageTiling tiling, VkImageUsageFlags usage,
    VkImageCreateFlags flags, VkImageFormatProperties* p)
{
    decide_for(gpu);
    VkFormat q = query_format(format);
    if (q == VK_FORMAT_UNDEFINED)
        return VK_ERROR_FORMAT_NOT_SUPPORTED;
    return r_image_format(gpu, q, type, tiling, usage, flags, p);
}

static VkResult image_format2(PFN_vkGetPhysicalDeviceImageFormatProperties2 real, VkPhysicalDevice gpu,
                              const VkPhysicalDeviceImageFormatInfo2* info, VkImageFormatProperties2* p)
{
    decide_for(gpu);
    VkFormat q = query_format(info->format);
    if (q == VK_FORMAT_UNDEFINED)
        return VK_ERROR_FORMAT_NOT_SUPPORTED;
    if (q == info->format)
        return real(gpu, info, p);
    VkPhysicalDeviceImageFormatInfo2 copy = *info;
    copy.format = q;
    return real(gpu, &copy, p);
}

static VKAPI_ATTR VkResult VKAPI_CALL bc_GetPhysicalDeviceImageFormatProperties2(
    VkPhysicalDevice gpu, const VkPhysicalDeviceImageFormatInfo2* info, VkImageFormatProperties2* p)
{ return image_format2(r_image_format2, gpu, info, p); }
static VKAPI_ATTR VkResult VKAPI_CALL bc_GetPhysicalDeviceImageFormatProperties2KHR(
    VkPhysicalDevice gpu, const VkPhysicalDeviceImageFormatInfo2* info, VkImageFormatProperties2* p)
{ return image_format2(r_image_format2khr, gpu, info, p); }

PFN_vkVoidFunction bcconv_instance_hook(const char* name, PFN_vkVoidFunction real)
{
    if (forced() == 0)
        return NULL;   // never converting: the engine sees the GPU as it is
#define INST(fn, slot, type) \
    if (!strcmp(name, "vk" #fn)) { slot = (type)real; return (PFN_vkVoidFunction)bc_##fn; }
    INST(GetPhysicalDeviceFeatures, r_features, PFN_vkGetPhysicalDeviceFeatures)
    INST(GetPhysicalDeviceFeatures2, r_features2, PFN_vkGetPhysicalDeviceFeatures2)
    INST(GetPhysicalDeviceFeatures2KHR, r_features2khr, PFN_vkGetPhysicalDeviceFeatures2)
    INST(GetPhysicalDeviceFormatProperties, r_format, PFN_vkGetPhysicalDeviceFormatProperties)
    INST(GetPhysicalDeviceFormatProperties2, r_format2, PFN_vkGetPhysicalDeviceFormatProperties2)
    INST(GetPhysicalDeviceFormatProperties2KHR, r_format2khr, PFN_vkGetPhysicalDeviceFormatProperties2)
    INST(GetPhysicalDeviceImageFormatProperties, r_image_format, PFN_vkGetPhysicalDeviceImageFormatProperties)
    INST(GetPhysicalDeviceImageFormatProperties2, r_image_format2, PFN_vkGetPhysicalDeviceImageFormatProperties2)
    INST(GetPhysicalDeviceImageFormatProperties2KHR, r_image_format2khr, PFN_vkGetPhysicalDeviceImageFormatProperties2)
#undef INST
    return NULL;
}

const VkDeviceCreateInfo* bcconv_device_info(VkPhysicalDevice gpu, const VkDeviceCreateInfo* info,
                                             VkDeviceCreateInfo* copy, VkPhysicalDeviceFeatures* features)
{
    decide_for(gpu);
    if (!info || atomic_load(&g_real_bc) != 0)
        return info;   // the GPU has BC (or was not asked): the request stands
    *copy = *info;
    if (info->pEnabledFeatures)
    {
        *features = *info->pEnabledFeatures;
        features->textureCompressionBC = VK_FALSE;
        copy->pEnabledFeatures = features;
    }
    // In the caller's own VkPhysicalDeviceFeatures2 too: the driver only reads it.
    for (VkBaseOutStructure* s = (VkBaseOutStructure*)info->pNext; s; s = s->pNext)
        if (s->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2)
            ((VkPhysicalDeviceFeatures2*)s)->features.textureCompressionBC = VK_FALSE;
    return copy;
}

// ------------------------------------------------------------------ bookkeeping

typedef struct { uint64_t key, a, b; } MapSlot;
typedef struct { MapSlot* s; unsigned cap, live, tombs; } HMap;
#define HMAP_TOMB (~0ULL)

static unsigned hmap_slot(const HMap* m, uint64_t key)
{
    return (unsigned)((key * 0x9E3779B97F4A7C15ULL) >> 32) & (m->cap - 1);
}

static MapSlot* hmap_find(HMap* m, uint64_t key)
{
    if (!m->cap || !key || key == HMAP_TOMB)
        return NULL;
    for (unsigned i = hmap_slot(m, key), n = 0; n < m->cap; i = (i + 1) & (m->cap - 1), n++)
    {
        if (m->s[i].key == 0)
            return NULL;
        if (m->s[i].key == key)
            return &m->s[i];
    }
    return NULL;
}

static void hmap_put(HMap* m, uint64_t key, uint64_t a, uint64_t b);

// Twice the size when at least a quarter is live, else the same size without the tombstones.
static void hmap_rebuild(HMap* m)
{
    unsigned cap = !m->cap ? 1024 : m->live * 4 >= m->cap ? m->cap * 2 : m->cap;
    MapSlot* s = calloc(cap, sizeof(*s));
    if (!s)
        return;
    MapSlot* old = m->s;
    unsigned old_cap = m->cap;
    m->s = s;
    m->cap = cap;
    m->live = m->tombs = 0;
    for (unsigned i = 0; i < old_cap; i++)
        if (old[i].key && old[i].key != HMAP_TOMB)
            hmap_put(m, old[i].key, old[i].a, old[i].b);
    free(old);
}

static void hmap_put(HMap* m, uint64_t key, uint64_t a, uint64_t b)
{
    if (!key || key == HMAP_TOMB)
        return;
    MapSlot* hit = hmap_find(m, key);
    if (hit)
    {
        hit->a = a;
        hit->b = b;
        return;
    }
    if ((m->live + m->tombs + 1) * 4 > m->cap * 3)
        hmap_rebuild(m);
    if ((m->live + m->tombs + 1) * 4 > m->cap * 3)
        return;   // out of memory: not tracked
    for (unsigned i = hmap_slot(m, key);; i = (i + 1) & (m->cap - 1))
    {
        if (m->s[i].key && m->s[i].key != HMAP_TOMB)
            continue;
        if (m->s[i].key == HMAP_TOMB)
            m->tombs--;
        m->s[i].key = key;
        m->s[i].a = a;
        m->s[i].b = b;
        m->live++;
        return;
    }
}

static void hmap_del(HMap* m, uint64_t key)
{
    MapSlot* hit = hmap_find(m, key);
    if (!hit)
        return;
    hit->key = HMAP_TOMB;
    m->live--;
    m->tombs++;
}

typedef struct { VkCommandBuffer cb; BcRegion r; VkDeviceMemory memory; } Job;
// Copies from a mapped buffer into one the CPU does not map (vkCmdCopyBuffer), until their command
// buffer is submitted: blocks relayed that way into a converted image are re-encoded at the source.
typedef struct { VkCommandBuffer cb; VkBuffer dst; VkDeviceSize dst_off, size; uint8_t* src; VkDeviceMemory memory; } Relay;

static pthread_mutex_t g_mu = PTHREAD_MUTEX_INITIALIZER;
static HMap g_images;   // image -> the BC format it was asked in
static HMap g_binds;    // buffer -> memory, offset
static HMap g_maps;     // memory -> pointer, device
static Job* g_jobs;
static unsigned g_job_count, g_job_cap;
static Relay* g_relays;
static unsigned g_relay_count, g_relay_cap;
static unsigned g_image_count, g_no_encoder, g_unmapped, g_regions, g_relayed;
static uint64_t g_bytes, g_nanos, g_last_summary_ns;

static uint64_t now_ns(void)
{
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
}

// The BC format a converted image was asked in, 0 if it is not one. g_mu held.
static VkFormat image_format(VkImage image)
{
    MapSlot* hit = hmap_find(&g_images, (uint64_t)image);
    return hit ? (VkFormat)hit->a : (VkFormat)0;
}

// Where the CPU sees a buffer's bytes, NULL when its memory is not mapped. g_mu held.
static uint8_t* buffer_ptr(VkBuffer b, VkDeviceSize off, VkDeviceMemory* memory)
{
    MapSlot* bind = hmap_find(&g_binds, (uint64_t)b);
    MapSlot* map = bind ? hmap_find(&g_maps, bind->a) : NULL;
    if (!map)
        return NULL;
    if (memory)
        *memory = (VkDeviceMemory)bind->a;
    return (uint8_t*)(uintptr_t)map->a + bind->b + off;
}

static void add_job(VkCommandBuffer cb, const BcRegion* r, VkDeviceMemory memory)
{
    if (g_job_count == g_job_cap)
    {
        unsigned cap = g_job_cap ? g_job_cap * 2 : 256;
        Job* jobs = realloc(g_jobs, cap * sizeof(*jobs));
        if (!jobs)
            return;
        g_jobs = jobs;
        g_job_cap = cap;
    }
    g_jobs[g_job_count].cb = cb;
    g_jobs[g_job_count].r = *r;
    g_jobs[g_job_count].memory = memory;
    g_job_count++;
}

// One copy region into a converted image. g_mu held.
static void note_region(VkCommandBuffer cb, VkFormat fmt, VkBuffer src, VkDeviceSize buffer_offset, uint32_t row_length,
                        uint32_t image_height, VkExtent3D extent, uint32_t layers)
{
    uint32_t bytes = (fmt <= VK_FORMAT_BC1_RGBA_SRGB_BLOCK || fmt == VK_FORMAT_BC4_UNORM_BLOCK ||
                      fmt == VK_FORMAT_BC4_SNORM_BLOCK) ? 8 : 16;
    uint32_t row_texels = row_length ? row_length : extent.width;
    uint32_t height_texels = image_height ? image_height : extent.height;
    BcRegion r;
    r.bw = (extent.width + 3) / 4;
    r.bh = (extent.height + 3) / 4;
    r.rowPitch = (row_texels + 3) / 4 * bytes;
    r.slicePitch = (height_texels + 3) / 4 * r.rowPitch;
    r.slices = layers * (extent.depth ? extent.depth : 1);
    r.format = fmt;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    r.data = buffer_ptr(src, buffer_offset, &memory);
    if (r.data)
    {
        add_job(cb, &r, memory);
        return;
    }
    // Not mapped: blocks a copy brought over from a mapped buffer, in a command buffer not submitted yet,
    // are re-encoded at their source when that one is submitted.
    VkDeviceSize len = (VkDeviceSize)r.slicePitch * (r.slices - 1) + (VkDeviceSize)r.rowPitch * (r.bh - 1) +
                       (VkDeviceSize)r.bw * bytes;
    for (unsigned i = g_relay_count; i-- > 0;)
    {
        Relay* x = &g_relays[i];
        if (x->dst != src || buffer_offset < x->dst_off || buffer_offset + len > x->dst_off + x->size)
            continue;
        r.data = x->src + (buffer_offset - x->dst_off);
        add_job(x->cb, &r, x->memory);
        g_relayed++;
        return;
    }
    if (++g_unmapped <= 10)
        LOG("BC conversion: a copy into a converted texture (%ux%u, format %d) comes from a buffer the CPU has not "
            "mapped: its blocks stay BC", extent.width, extent.height, fmt);
}

// vkCmdCopyBuffer regions from a mapped buffer into an unmapped one: remembered as relays. g_mu held.
static void note_relay(VkCommandBuffer cb, VkBuffer src, VkBuffer dst, VkDeviceSize src_off, VkDeviceSize dst_off,
                       VkDeviceSize size)
{
    VkDeviceMemory memory = VK_NULL_HANDLE;
    uint8_t* from = buffer_ptr(src, src_off, &memory);
    if (!from || buffer_ptr(dst, dst_off, NULL))
        return;
    if (g_relay_count == g_relay_cap)
    {
        unsigned cap = g_relay_cap ? g_relay_cap * 2 : 256;
        Relay* relays = realloc(g_relays, cap * sizeof(*relays));
        if (!relays)
            return;
        g_relays = relays;
        g_relay_cap = cap;
    }
    g_relays[g_relay_count++] = (Relay){ cb, dst, dst_off, size, from, memory };
}

static void forget_relays(VkCommandBuffer cb)   // g_mu held
{
    for (unsigned i = 0; i < g_relay_count;)
        if (g_relays[i].cb == cb)
            g_relays[i] = g_relays[--g_relay_count];
        else
            i++;
}

// A command buffer begun again, reset or freed: what it noted is gone with its old recording.
static void forget(VkCommandBuffer cb)
{
    if (!__atomic_load_n(&g_job_count, __ATOMIC_RELAXED) && !__atomic_load_n(&g_relay_count, __ATOMIC_RELAXED))
        return;
    pthread_mutex_lock(&g_mu);
    for (unsigned i = 0; i < g_job_count;)
        if (g_jobs[i].cb == cb)
            g_jobs[i] = g_jobs[--g_job_count];
        else
            i++;
    forget_relays(cb);
    pthread_mutex_unlock(&g_mu);
}

static int job_cmp(const void* a, const void* b)
{
    uintptr_t x = (uintptr_t)((const Job*)a)->r.data, y = (uintptr_t)((const Job*)b)->r.data;
    return x < y ? -1 : x > y;
}

static void summary(void)   // g_mu held
{
    uint64_t t = now_ns();
    if (t - g_last_summary_ns < 10000000000ull)
        return;
    g_last_summary_ns = t;
    LOG("BC conversion: %u textures created as ETC2/EAC, %u regions / %llu MB re-encoded in %llu ms (%u relayed "
        "through a GPU buffer); %u from unmapped buffers (left BC), %u without an encoder", g_image_count, g_regions,
        (unsigned long long)(g_bytes >> 20), (unsigned long long)(g_nanos / 1000000), g_relayed, g_unmapped,
        g_no_encoder);
}

void bcconv_before_submit(const VkCommandBuffer* cbs, uint32_t n)
{
    if (!n || atomic_load(&g_conv) != 1 ||
        (!__atomic_load_n(&g_job_count, __ATOMIC_RELAXED) && !__atomic_load_n(&g_relay_count, __ATOMIC_RELAXED)))
        return;
    pthread_mutex_lock(&g_mu);
    Job* todo = malloc((size_t)(g_job_count ? g_job_count : 1) * sizeof(*todo));
    unsigned count = 0;
    for (unsigned i = 0; todo && i < g_job_count;)
    {
        bool mine = false;
        for (uint32_t k = 0; k < n && !mine; k++)
            mine = g_jobs[i].cb == cbs[k];
        if (!mine)
        {
            i++;
            continue;
        }
        todo[count++] = g_jobs[i];
        g_jobs[i] = g_jobs[--g_job_count];
    }
    for (uint32_t k = 0; k < n && g_relay_count; k++)
        forget_relays(cbs[k]);   // their data is on its way to the GPU
    pthread_mutex_unlock(&g_mu);
    if (!count)
    {
        free(todo);
        return;
    }
    // The same blocks copied twice (one upload into two textures) must be re-encoded once: a second pass
    // would read ETC2 as BC.
    qsort(todo, count, sizeof(*todo), job_cmp);
    unsigned kept = 1;
    for (unsigned i = 1; i < count; i++)
        if (todo[i].r.data != todo[kept - 1].r.data)
            todo[kept++] = todo[i];
    count = kept;
    BcRegion* regions = malloc(count * sizeof(*regions));
    uint64_t t0 = now_ns(), bytes = 0;
    if (regions)
    {
        for (unsigned i = 0; i < count; i++)
        {
            const BcRegion* r = &todo[i].r;
            regions[i] = *r;
            bool small = r->format <= VK_FORMAT_BC1_RGBA_SRGB_BLOCK || r->format == VK_FORMAT_BC4_UNORM_BLOCK ||
                         r->format == VK_FORMAT_BC4_SNORM_BLOCK;
            bytes += (uint64_t)r->bw * r->bh * r->slices * (small ? 8 : 16);
        }
        bc_transcode(regions, count);
        free(regions);
    }
    uint64_t took = now_ns() - t0;
    // The engine flushed its writes before we changed them: flushed again for the GPU (a no-op on
    // coherent memory). Every memory object once, mapped whole.
    pthread_mutex_lock(&g_mu);
    for (unsigned i = 0; i < count && g_loader_flush; i++)
    {
        bool seen = false;
        for (unsigned k = 0; k < i && !seen; k++)
            seen = todo[k].memory == todo[i].memory;
        MapSlot* map = seen ? NULL : hmap_find(&g_maps, (uint64_t)todo[i].memory);
        if (!map)
            continue;
        VkMappedMemoryRange range = { VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE, NULL, todo[i].memory, 0, VK_WHOLE_SIZE };
        g_loader_flush((VkDevice)(uintptr_t)map->b, 1, &range);
    }
    g_regions += count;
    g_bytes += bytes;
    g_nanos += took;
    summary();
    pthread_mutex_unlock(&g_mu);
    free(todo);
}

// ------------------------------------------------------------------ device level

VkResult bcconv_create_image(PFN_vkCreateImage real, VkDevice device, const VkImageCreateInfo* info,
                             const VkAllocationCallbacks* alloc, VkImage* image)
{
    VkFormat sub = is_bc(info->format) ? substitute(info->format) : VK_FORMAT_UNDEFINED;
    if (is_bc(info->format) && !sub && __atomic_add_fetch(&g_no_encoder, 1, __ATOMIC_RELAXED) <= 10)
        LOG("BC conversion: no encoder for format %d (%ux%u): created as asked", info->format, info->extent.width,
            info->extent.height);
    if (!sub)
        return real(device, info, alloc, image);
    VkImageCreateInfo copy = *info;
    copy.format = sub;
    // A mutable image's list of view formats: its BC entries become their substitutes too (the caller's
    // own struct; the driver only reads it).
    for (VkBaseOutStructure* x = (VkBaseOutStructure*)info->pNext; x; x = x->pNext)
    {
        if (x->sType != VK_STRUCTURE_TYPE_IMAGE_FORMAT_LIST_CREATE_INFO)
            continue;
        VkImageFormatListCreateInfo* list = (VkImageFormatListCreateInfo*)x;
        for (uint32_t k = 0; k < list->viewFormatCount; k++)
        {
            VkFormat v = substitute(list->pViewFormats[k]);
            if (v)
                ((VkFormat*)list->pViewFormats)[k] = v;
        }
    }
    VkResult r = real(device, &copy, alloc, image);
    if (r == VK_SUCCESS)
    {
        pthread_mutex_lock(&g_mu);
        hmap_put(&g_images, (uint64_t)*image, (uint64_t)info->format, 0);
        g_image_count++;
        pthread_mutex_unlock(&g_mu);
    }
    else
        LOG("BC conversion: %ux%u format %d as %d: vkCreateImage -> %d", info->extent.width, info->extent.height,
            info->format, sub, r);
    return r;
}

void bcconv_destroy_image(VkImage image)
{
    if (!image || !g_images.live)
        return;
    pthread_mutex_lock(&g_mu);
    hmap_del(&g_images, (uint64_t)image);
    pthread_mutex_unlock(&g_mu);
}

static PFN_vkCreateImageView r_create_view;
static PFN_vkCmdCopyBufferToImage r_copy_b2i;
static PFN_vkCmdCopyBufferToImage2 r_copy_b2i2, r_copy_b2i2khr;
static PFN_vkCmdCopyBuffer r_copy_buf;
static PFN_vkCmdCopyBuffer2 r_copy_buf2, r_copy_buf2khr;
static PFN_vkBindBufferMemory r_bind;
static PFN_vkBindBufferMemory2 r_bind2, r_bind2khr;
static PFN_vkDestroyBuffer r_destroy_buffer;
static PFN_vkMapMemory r_map;
static PFN_vkUnmapMemory r_unmap;
static PFN_vkFreeMemory r_free;
static PFN_vkBeginCommandBuffer r_begin;
static PFN_vkResetCommandBuffer r_reset;
static PFN_vkFreeCommandBuffers r_free_cbs;

static VKAPI_ATTR VkResult VKAPI_CALL bc_CreateImageView(VkDevice d, const VkImageViewCreateInfo* info,
                                                        const VkAllocationCallbacks* a, VkImageView* out)
{
    VkFormat sub = is_bc(info->format) && bcconv_on() ? substitute(info->format) : VK_FORMAT_UNDEFINED;
    if (!sub)
        return r_create_view(d, info, a, out);
    VkImageViewCreateInfo copy = *info;
    copy.format = sub;
    return r_create_view(d, &copy, a, out);
}

static void note_copies(VkCommandBuffer cb, VkBuffer src, VkImage dst, uint32_t n, const VkBufferImageCopy* r)
{
    if (!bcconv_on() || !g_images.live)
        return;
    pthread_mutex_lock(&g_mu);
    VkFormat fmt = image_format(dst);
    for (uint32_t i = 0; fmt && i < n; i++)
        note_region(cb, fmt, src, r[i].bufferOffset, r[i].bufferRowLength, r[i].bufferImageHeight, r[i].imageExtent,
                    r[i].imageSubresource.layerCount);
    pthread_mutex_unlock(&g_mu);
}

static void note_copies2(VkCommandBuffer cb, const VkCopyBufferToImageInfo2* info)
{
    if (!bcconv_on() || !g_images.live)
        return;
    pthread_mutex_lock(&g_mu);
    VkFormat fmt = image_format(info->dstImage);
    for (uint32_t i = 0; fmt && i < info->regionCount; i++)
    {
        const VkBufferImageCopy2* r = &info->pRegions[i];
        note_region(cb, fmt, info->srcBuffer, r->bufferOffset, r->bufferRowLength, r->bufferImageHeight, r->imageExtent,
                    r->imageSubresource.layerCount);
    }
    pthread_mutex_unlock(&g_mu);
}

static VKAPI_ATTR void VKAPI_CALL bc_CmdCopyBufferToImage(VkCommandBuffer cb, VkBuffer src, VkImage dst,
                                                         VkImageLayout layout, uint32_t n, const VkBufferImageCopy* r)
{
    note_copies(cb, src, dst, n, r);
    r_copy_b2i(cb, src, dst, layout, n, r);
}

static VKAPI_ATTR void VKAPI_CALL bc_CmdCopyBufferToImage2(VkCommandBuffer cb, const VkCopyBufferToImageInfo2* info)
{
    note_copies2(cb, info);
    r_copy_b2i2(cb, info);
}

static VKAPI_ATTR void VKAPI_CALL bc_CmdCopyBufferToImage2KHR(VkCommandBuffer cb, const VkCopyBufferToImageInfo2* info)
{
    note_copies2(cb, info);
    r_copy_b2i2khr(cb, info);
}

static void note_relays(VkCommandBuffer cb, VkBuffer src, VkBuffer dst, uint32_t n, const VkBufferCopy* regions)
{
    if (!bcconv_on() || !g_image_count)
        return;
    pthread_mutex_lock(&g_mu);
    for (uint32_t i = 0; i < n; i++)
        note_relay(cb, src, dst, regions[i].srcOffset, regions[i].dstOffset, regions[i].size);
    pthread_mutex_unlock(&g_mu);
}

static void note_relays2(VkCommandBuffer cb, const VkCopyBufferInfo2* info)
{
    if (!bcconv_on() || !g_image_count)
        return;
    pthread_mutex_lock(&g_mu);
    for (uint32_t i = 0; i < info->regionCount; i++)
        note_relay(cb, info->srcBuffer, info->dstBuffer, info->pRegions[i].srcOffset, info->pRegions[i].dstOffset,
                   info->pRegions[i].size);
    pthread_mutex_unlock(&g_mu);
}

static VKAPI_ATTR void VKAPI_CALL bc_CmdCopyBuffer(VkCommandBuffer cb, VkBuffer src, VkBuffer dst, uint32_t n,
                                                  const VkBufferCopy* regions)
{
    note_relays(cb, src, dst, n, regions);
    r_copy_buf(cb, src, dst, n, regions);
}

static VKAPI_ATTR void VKAPI_CALL bc_CmdCopyBuffer2(VkCommandBuffer cb, const VkCopyBufferInfo2* info)
{
    note_relays2(cb, info);
    r_copy_buf2(cb, info);
}

static VKAPI_ATTR void VKAPI_CALL bc_CmdCopyBuffer2KHR(VkCommandBuffer cb, const VkCopyBufferInfo2* info)
{
    note_relays2(cb, info);
    r_copy_buf2khr(cb, info);
}

static VKAPI_ATTR VkResult VKAPI_CALL bc_BindBufferMemory(VkDevice d, VkBuffer b, VkDeviceMemory m, VkDeviceSize off)
{
    VkResult r = r_bind(d, b, m, off);
    if (r == VK_SUCCESS && bcconv_on())
    {
        pthread_mutex_lock(&g_mu);
        hmap_put(&g_binds, (uint64_t)b, (uint64_t)m, off);
        pthread_mutex_unlock(&g_mu);
    }
    return r;
}

static VkResult bind2(PFN_vkBindBufferMemory2 real, VkDevice d, uint32_t n, const VkBindBufferMemoryInfo* infos)
{
    VkResult r = real(d, n, infos);
    if (r == VK_SUCCESS && bcconv_on())
    {
        pthread_mutex_lock(&g_mu);
        for (uint32_t i = 0; i < n; i++)
            hmap_put(&g_binds, (uint64_t)infos[i].buffer, (uint64_t)infos[i].memory, infos[i].memoryOffset);
        pthread_mutex_unlock(&g_mu);
    }
    return r;
}

static VKAPI_ATTR VkResult VKAPI_CALL bc_BindBufferMemory2(VkDevice d, uint32_t n, const VkBindBufferMemoryInfo* i)
{ return bind2(r_bind2, d, n, i); }
static VKAPI_ATTR VkResult VKAPI_CALL bc_BindBufferMemory2KHR(VkDevice d, uint32_t n, const VkBindBufferMemoryInfo* i)
{ return bind2(r_bind2khr, d, n, i); }

static VKAPI_ATTR void VKAPI_CALL bc_DestroyBuffer(VkDevice d, VkBuffer b, const VkAllocationCallbacks* a)
{
    if (b && g_binds.live)
    {
        pthread_mutex_lock(&g_mu);
        hmap_del(&g_binds, (uint64_t)b);
        pthread_mutex_unlock(&g_mu);
    }
    r_destroy_buffer(d, b, a);
}

// Mapped whole on the first vkMapMemory and kept until the memory is freed; a later vkMapMemory of the
// same memory gets the same mapping at the offset asked for.
static VKAPI_ATTR VkResult VKAPI_CALL bc_MapMemory(VkDevice d, VkDeviceMemory m, VkDeviceSize off, VkDeviceSize size,
                                                  VkMemoryMapFlags flags, void** data)
{
    if (!bcconv_on())
        return r_map(d, m, off, size, flags, data);
    pthread_mutex_lock(&g_mu);
    MapSlot* kept = hmap_find(&g_maps, (uint64_t)m);
    void* base = kept ? (void*)(uintptr_t)kept->a : NULL;
    pthread_mutex_unlock(&g_mu);
    VkResult r = VK_SUCCESS;
    if (!base)
    {
        r = r_map(d, m, 0, VK_WHOLE_SIZE, flags, &base);
        if (r == VK_SUCCESS)
        {
            pthread_mutex_lock(&g_mu);
            hmap_put(&g_maps, (uint64_t)m, (uint64_t)(uintptr_t)base, (uint64_t)(uintptr_t)d);
            pthread_mutex_unlock(&g_mu);
        }
    }
    if (r == VK_SUCCESS)
        *data = (uint8_t*)base + off;
    return r;
}

static VKAPI_ATTR void VKAPI_CALL bc_UnmapMemory(VkDevice d, VkDeviceMemory m)
{
    if (bcconv_on())
        return;   // kept until the memory is freed (see bc_MapMemory)
    r_unmap(d, m);
}

static VKAPI_ATTR void VKAPI_CALL bc_FreeMemory(VkDevice d, VkDeviceMemory m, const VkAllocationCallbacks* a)
{
    if (m && g_maps.live)   // freeing a mapped memory object unmaps it
    {
        pthread_mutex_lock(&g_mu);
        hmap_del(&g_maps, (uint64_t)m);
        pthread_mutex_unlock(&g_mu);
    }
    r_free(d, m, a);
}

static VKAPI_ATTR VkResult VKAPI_CALL bc_BeginCommandBuffer(VkCommandBuffer cb, const VkCommandBufferBeginInfo* info)
{
    forget(cb);
    return r_begin(cb, info);
}

static VKAPI_ATTR VkResult VKAPI_CALL bc_ResetCommandBuffer(VkCommandBuffer cb, VkCommandBufferResetFlags flags)
{
    forget(cb);
    return r_reset(cb, flags);
}

static VKAPI_ATTR void VKAPI_CALL bc_FreeCommandBuffers(VkDevice d, VkCommandPool pool, uint32_t n,
                                                       const VkCommandBuffer* cbs)
{
    for (uint32_t i = 0; i < n; i++)
        forget(cbs[i]);
    r_free_cbs(d, pool, n, cbs);
}

bool bcconv_maybe(void)
{
    return atomic_load(&g_conv) != 0 && forced() != 0;
}

PFN_vkVoidFunction bcconv_device_hook(const char* name, PFN_vkVoidFunction real)
{
    // Not converting: nothing wrapped. Not decided yet (asked before the device): wrapped, and each
    // wrapper passes straight through unless the conversion turns out on.
    if (!bcconv_maybe())
        return NULL;
#define DEV(fn, slot, type) \
    if (!strcmp(name, "vk" #fn)) { slot = (type)real; return (PFN_vkVoidFunction)bc_##fn; }
    DEV(CreateImageView, r_create_view, PFN_vkCreateImageView)
    DEV(CmdCopyBufferToImage, r_copy_b2i, PFN_vkCmdCopyBufferToImage)
    DEV(CmdCopyBufferToImage2, r_copy_b2i2, PFN_vkCmdCopyBufferToImage2)
    DEV(CmdCopyBufferToImage2KHR, r_copy_b2i2khr, PFN_vkCmdCopyBufferToImage2)
    DEV(CmdCopyBuffer, r_copy_buf, PFN_vkCmdCopyBuffer)
    DEV(CmdCopyBuffer2, r_copy_buf2, PFN_vkCmdCopyBuffer2)
    DEV(CmdCopyBuffer2KHR, r_copy_buf2khr, PFN_vkCmdCopyBuffer2)
    DEV(BindBufferMemory, r_bind, PFN_vkBindBufferMemory)
    DEV(BindBufferMemory2, r_bind2, PFN_vkBindBufferMemory2)
    DEV(BindBufferMemory2KHR, r_bind2khr, PFN_vkBindBufferMemory2)
    DEV(DestroyBuffer, r_destroy_buffer, PFN_vkDestroyBuffer)
    DEV(MapMemory, r_map, PFN_vkMapMemory)
    DEV(UnmapMemory, r_unmap, PFN_vkUnmapMemory)
    DEV(FreeMemory, r_free, PFN_vkFreeMemory)
    DEV(BeginCommandBuffer, r_begin, PFN_vkBeginCommandBuffer)
    DEV(ResetCommandBuffer, r_reset, PFN_vkResetCommandBuffer)
    DEV(FreeCommandBuffers, r_free_cbs, PFN_vkFreeCommandBuffers)
#undef DEV
    return NULL;
}

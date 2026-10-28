// BC textures on GPUs without BC (Mali, Xclipse, PowerVR): see bcconv.c.
#ifndef STORMDROID_BCCONV_H
#define STORMDROID_BCCONV_H
#include <stdbool.h>
#include <vulkan/vulkan.h>

// The system Vulkan library vkshim loaded: the conversion asks the GPU's features and flushes
// re-encoded memory through its exports.
void bcconv_set_loader(void* lib);

// Whether BC textures are being converted. Decided once the GPU's own BC support is known (or at once
// when STORMDROID_BC_CONVERT forces it either way).
bool bcconv_on(void);

// False once the conversion is known to stay off: vkshim.c then wraps no extra device call.
bool bcconv_maybe(void);

// The instance-level queries the engine asks about BC (features, format and image format properties):
// a wrapper when `name` is one, else NULL. `real` is the loader's function for `name`.
PFN_vkVoidFunction bcconv_instance_hook(const char* name, PFN_vkVoidFunction real);

// The device-level calls the conversion follows (image views, buffer binds and maps, copies, command
// buffer recycling): a wrapper while converting, else NULL. vkCreateImage and vkDestroyImage are not
// among them: vkshim.c wraps those itself and calls bcconv_create_image / bcconv_destroy_image.
PFN_vkVoidFunction bcconv_device_hook(const char* name, PFN_vkVoidFunction real);

// vkCreateDevice: the create info to pass on. A GPU without BC would refuse the device with the
// textureCompressionBC the engine was told about, so it is left out of a copy (`copy`, `features`).
const VkDeviceCreateInfo* bcconv_device_info(VkPhysicalDevice gpu, const VkDeviceCreateInfo* info,
                                             VkDeviceCreateInfo* copy, VkPhysicalDeviceFeatures* features);

VkResult bcconv_create_image(PFN_vkCreateImage real, VkDevice device, const VkImageCreateInfo* info,
                             const VkAllocationCallbacks* alloc, VkImage* image);
void bcconv_destroy_image(VkImage image);

// Before command buffers go to the GPU: re-encode the blocks their copies into converted images read.
void bcconv_before_submit(const VkCommandBuffer* cbs, uint32_t n);

// Logs the conversion's totals when they changed since the last call (vkshim's watchdog, every 10 s).
void bcconv_log_summary(void);

#endif

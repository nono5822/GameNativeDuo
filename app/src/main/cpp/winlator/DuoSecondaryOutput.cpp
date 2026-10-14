// GameNativeDuo: a second screen for VulkanRendererContext.
//
// Dual-screen handhelds show one tall X screen across two displays. Instead of a second renderer (a second
// VkDevice reading the same game buffers, which faulted the GPU on Adreno), the existing renderer draws the
// frame's windows a second time into a swapchain on the second display's surface, with its own crop transform.
// Same device, same imported textures, same queue, so the game's buffers have exactly one reader.
//
// The second output never makes the main screen wait: if its previous frame is still in flight or no swapchain
// image is free, that frame is simply skipped on the second screen. In particular the GPU never waits on the
// second display: images are acquired with a fence that is polled on later frames, never with a semaphore the
// queue would wait on. (A semaphore wait that the second display's compositor did not release in time hung the
// shared queue, and the main screen with it: kgsl "GPU hang detected", context banned.)
//
// Kept in its own file to keep upstream merges cheap; VulkanRendererContext only has one-line hooks
// (marked GameNativeDuo) that declare these members and call duoRenderSecondary() after each present.

#include "VulkanRendererContext.h"
#include <jni.h>
#include <android/native_window_jni.h>

struct DuoSecondary {
    ANativeWindow* window = nullptr;
    VkSurfaceKHR surface = VK_NULL_HANDLE;
    VkSwapchainKHR swapchain = VK_NULL_HANDLE;
    VkExtent2D ext{};
    std::vector<VkImage> images;
    std::vector<VkImageView> views;
    std::vector<VkFramebuffer> fbs;
    std::vector<VkSemaphore> renderDone;  // one per swapchain image
    VkFence acquireFence = VK_NULL_HANDLE;  // signalled when the acquired image is really free
    int pendingImage = -1;                  // acquired, waiting for acquireFence
    VkFence fences[MAX_FRAMES_IN_FLIGHT]{};
    VkCommandBuffer cmd[MAX_FRAMES_IN_FLIGHT]{};
    uint32_t frame = 0;
    float ox = 0.f, oy = 0.f, sx = 1.f, sy = 1.f;
    bool broken = false;  // surface out of date: wait for the Java side to set it again
};

void VulkanRendererContext::duoDestroySecondary() {
    DuoSecondary* s = duoSecondary;
    if (!s) return;
    duoSecondary = nullptr;
    if (device != VK_NULL_HANDLE) {
        vk_.DeviceWaitIdle(device);
        for (auto fb : s->fbs) if (fb != VK_NULL_HANDLE) vk_.DestroyFramebuffer(device, fb, nullptr);
        for (auto v : s->views) if (v != VK_NULL_HANDLE) vk_.DestroyImageView(device, v, nullptr);
        for (auto sem : s->renderDone) if (sem != VK_NULL_HANDLE) vk_.DestroySemaphore(device, sem, nullptr);
        if (s->acquireFence != VK_NULL_HANDLE) {
            if (s->pendingImage >= 0) vk_.WaitForFences(device, 1, &s->acquireFence, VK_TRUE, 100000000ULL);  // 100 ms
            vk_.DestroyFence(device, s->acquireFence, nullptr);
        }
        for (uint32_t i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
            if (s->fences[i] != VK_NULL_HANDLE) vk_.DestroyFence(device, s->fences[i], nullptr);
            if (s->cmd[i] != VK_NULL_HANDLE) vk_.FreeCommandBuffers(device, cmdPool, 1, &s->cmd[i]);
        }
        if (s->swapchain != VK_NULL_HANDLE) vk_.DestroySwapchainKHR(device, s->swapchain, nullptr);
    }
    if (s->surface != VK_NULL_HANDLE) vk_.DestroySurfaceKHR(instance, s->surface, nullptr);
    if (s->window) ANativeWindow_release(s->window);
    delete s;
    RLOG_E("duo: second screen output removed");
}

void VulkanRendererContext::duoClearSecondary() {
    std::unique_lock<std::shared_mutex> fl(frameMutex);
    std::lock_guard<std::mutex> lk(renderMutex);
    duoDestroySecondary();
}

bool VulkanRendererContext::duoSetSecondary(ANativeWindow* win, float ox, float oy, float sx, float sy) {
    std::unique_lock<std::shared_mutex> fl(frameMutex);
    std::lock_guard<std::mutex> lk(renderMutex);
    if (device == VK_NULL_HANDLE || renderPass == VK_NULL_HANDLE || pipeline == VK_NULL_HANDLE) {
        ANativeWindow_release(win);
        return false;
    }

    // Same surface, still valid: only the crop changed.
    if (duoSecondary && duoSecondary->window == win && !duoSecondary->broken) {
        VkSurfaceCapabilitiesKHR caps{};
        vk_.GetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, duoSecondary->surface, &caps);
        if (caps.currentExtent.width == duoSecondary->ext.width && caps.currentExtent.height == duoSecondary->ext.height) {
            duoSecondary->ox = ox; duoSecondary->oy = oy; duoSecondary->sx = sx; duoSecondary->sy = sy;
            ANativeWindow_release(win);
            needsRender.store(true); dirtyCV.notify_one();
            return true;
        }
    }
    duoDestroySecondary();

    auto* s = new DuoSecondary();
    s->window = win;
    s->ox = ox; s->oy = oy; s->sx = sx; s->sy = sy;
    duoSecondary = s;
    auto fail = [&](const char* what) {
        RLOG_E("duo: second screen setup failed at %s", what);
        duoDestroySecondary();
        return false;
    };

    VkAndroidSurfaceCreateInfoKHR sci{};
    sci.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
    sci.window = win;
    if (vk_.CreateAndroidSurfaceKHR(instance, &sci, nullptr, &s->surface) != VK_SUCCESS) return fail("surface");
    VkBool32 presentOk = VK_FALSE;
    vk_.GetPhysicalDeviceSurfaceSupportKHR(physicalDevice, graphicsQueueFamilyIndex, s->surface, &presentOk);
    if (!presentOk) return fail("present support");

    VkSurfaceCapabilitiesKHR caps{};
    vk_.GetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, s->surface, &caps);
    s->ext = caps.currentExtent;
    if (s->ext.width == 0xFFFFFFFF || s->ext.width == 0 || s->ext.height == 0) {
        s->ext = {(uint32_t)ANativeWindow_getWidth(win), (uint32_t)ANativeWindow_getHeight(win)};
    }
    if (s->ext.width == 0 || s->ext.height == 0) return fail("extent");

    // Mailbox never blocks the render thread on the second display's vsync; FIFO is the fallback.
    uint32_t pmCount = 0;
    vk_.GetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, s->surface, &pmCount, nullptr);
    std::vector<VkPresentModeKHR> modes(pmCount);
    vk_.GetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, s->surface, &pmCount, modes.data());
    VkPresentModeKHR presentMode = VK_PRESENT_MODE_FIFO_KHR;
    for (auto m : modes) if (m == VK_PRESENT_MODE_MAILBOX_KHR) presentMode = m;

    uint32_t imgCount = caps.minImageCount + 1;
    if (caps.maxImageCount > 0 && imgCount > caps.maxImageCount) imgCount = caps.maxImageCount;
    VkSwapchainCreateInfoKHR ci{};
    ci.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
    ci.surface = s->surface;
    ci.minImageCount = imgCount;
    ci.imageFormat = swapchainFmt;  // must match renderPass/pipeline
    ci.imageColorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
    ci.imageExtent = s->ext;
    ci.imageArrayLayers = 1;
    ci.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    ci.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ci.preTransform = (caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)
        ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR : caps.currentTransform;
    ci.compositeAlpha = (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
        ? VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR : VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    ci.presentMode = presentMode;
    ci.clipped = VK_TRUE;
    if (vk_.CreateSwapchainKHR(device, &ci, nullptr, &s->swapchain) != VK_SUCCESS) return fail("swapchain");

    vk_.GetSwapchainImagesKHR(device, s->swapchain, &imgCount, nullptr);
    s->images.resize(imgCount);
    vk_.GetSwapchainImagesKHR(device, s->swapchain, &imgCount, s->images.data());
    s->views.assign(imgCount, VK_NULL_HANDLE);
    s->fbs.assign(imgCount, VK_NULL_HANDLE);
    s->renderDone.assign(imgCount, VK_NULL_HANDLE);
    VkSemaphoreCreateInfo semCi{}; semCi.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    for (uint32_t i = 0; i < imgCount; i++) {
        VkImageViewCreateInfo vi{};
        vi.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        vi.image = s->images[i];
        vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
        vi.format = swapchainFmt;
        vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        if (vk_.CreateImageView(device, &vi, nullptr, &s->views[i]) != VK_SUCCESS) return fail("image view");
        VkFramebufferCreateInfo fi{};
        fi.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
        fi.renderPass = renderPass;
        fi.attachmentCount = 1;
        fi.pAttachments = &s->views[i];
        fi.width = s->ext.width;
        fi.height = s->ext.height;
        fi.layers = 1;
        if (vk_.CreateFramebuffer(device, &fi, nullptr, &s->fbs[i]) != VK_SUCCESS) return fail("framebuffer");
        if (vk_.CreateSemaphore(device, &semCi, nullptr, &s->renderDone[i]) != VK_SUCCESS) return fail("semaphore");
    }
    VkFenceCreateInfo fenceCi{}; fenceCi.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO; fenceCi.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    VkCommandBufferAllocateInfo cai{};
    cai.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    cai.commandPool = cmdPool;
    cai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cai.commandBufferCount = 1;
    VkFenceCreateInfo acquireFenceCi{}; acquireFenceCi.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    if (vk_.CreateFence(device, &acquireFenceCi, nullptr, &s->acquireFence) != VK_SUCCESS) return fail("fence");
    for (uint32_t i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
        if (vk_.CreateFence(device, &fenceCi, nullptr, &s->fences[i]) != VK_SUCCESS) return fail("fence");
        if (vk_.AllocateCommandBuffers(device, &cai, &s->cmd[i]) != VK_SUCCESS) return fail("command buffer");
    }

    RLOG_E("duo: second screen output %ux%u, %u images, present mode %d", s->ext.width, s->ext.height, imgCount, (int)presentMode);
    needsRender.store(true); dirtyCV.notify_one();
    return true;
}

// Called on the render thread before window textures are destroyed: the main screen only waits for its own
// frames, but the second screen's last command buffer may still sample those textures.
void VulkanRendererContext::duoWaitSecondary() {
    DuoSecondary* s = duoSecondary;
    if (!s) return;
    for (uint32_t i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
        if (s->fences[i] == VK_NULL_HANDLE || vk_.GetFenceStatus(device, s->fences[i]) == VK_SUCCESS) continue;
        vk_.WaitForFences(device, 1, &s->fences[i], VK_TRUE, UINT64_MAX);
    }
}

// Called on the render thread right after the main screen's present (frameMutex held shared, frameDraws current).
void VulkanRendererContext::duoRenderSecondary() {
    DuoSecondary* s = duoSecondary;
    if (!s || s->broken || s->swapchain == VK_NULL_HANDLE) return;
    const uint32_t f = s->frame;
    if (vk_.GetFenceStatus(device, s->fences[f]) != VK_SUCCESS) return;  // previous frame still on the GPU: skip

    VkResult r;
    if (s->pendingImage < 0) {
        uint32_t acquiredImg = 0;
        vk_.ResetFences(device, 1, &s->acquireFence);
        r = vk_.AcquireNextImageKHR(device, s->swapchain, 0, VK_NULL_HANDLE, s->acquireFence, &acquiredImg);
        if (r == VK_ERROR_OUT_OF_DATE_KHR || r == VK_ERROR_SURFACE_LOST_KHR) {
            s->broken = true;
            RLOG_E("duo: second screen surface out of date (%d)", (int)r);
            return;
        }
        if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) return;  // no free image right now: skip
        if (acquiredImg >= s->fbs.size()) { s->broken = true; return; }
        s->pendingImage = (int)acquiredImg;
    }
    // The display may still be scanning the image out; draw into it on a later frame instead of making the GPU wait.
    if (vk_.GetFenceStatus(device, s->acquireFence) != VK_SUCCESS) return;
    const uint32_t img = (uint32_t)s->pendingImage;
    s->pendingImage = -1;

    VkCommandBuffer cb = s->cmd[f];
    vk_.ResetFences(device, 1, &s->fences[f]);
    vk_.ResetCommandBuffer(cb, 0);
    VkCommandBufferBeginInfo bi{}; bi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vk_.BeginCommandBuffer(cb, &bi);
    VkRenderPassBeginInfo rpi{}; rpi.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
    rpi.renderPass = renderPass;
    rpi.framebuffer = s->fbs[img];
    rpi.renderArea = {{0, 0}, s->ext};
    VkClearValue clr = {{{0.f, 0.f, 0.f, 1.f}}};
    rpi.clearValueCount = 1;
    rpi.pClearValues = &clr;
    vk_.CmdBeginRenderPass(cb, &rpi, VK_SUBPASS_CONTENTS_INLINE);
    VkViewport vp{0, 0, (float)s->ext.width, (float)s->ext.height, 0, 1};
    vk_.CmdSetViewport(cb, 0, 1, &vp);
    VkRect2D sc{{0, 0}, s->ext};
    vk_.CmdSetScissor(cb, 0, 1, &sc);
    vk_.CmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);

    // Same draws as the main screen (textures were imported and transitioned by its command buffer, submitted
    // earlier on this queue), placed with this screen's crop.
    const float cw = (float)std::max(1, containerWidth), ch = (float)std::max(1, containerHeight);
    for (auto& d : frameDraws) {
        if (d.ds == VK_NULL_HANDLE) continue;
        vk_.CmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeLayout, 0, 1, &d.ds, 0, nullptr);
        WindowPushConstants pc{};
        pc.ndcX0 = (s->ox + (float)d.x * s->sx) / cw * 2.f - 1.f;
        pc.ndcY0 = (s->oy + (float)d.y * s->sy) / ch * 2.f - 1.f;
        pc.ndcX1 = (s->ox + (float)(d.x + d.w) * s->sx) / cw * 2.f - 1.f;
        pc.ndcY1 = (s->oy + (float)(d.y + d.h) * s->sy) / ch * 2.f - 1.f;
        pc.useTexAlpha = 0;
        pc.effectId = 0;
        pc.sharpness = 0.f;
        pc.resW = (float)std::max(1, d.w);
        pc.resH = (float)std::max(1, d.h);
        pc.effectMask = 0;
        pc.brightness = 0.f;
        pc.contrast = 0.f;
        pc.gamma = 1.f;
        pc.outW = std::max(1.0f, (float)d.w * s->sx / cw * (float)s->ext.width);
        pc.outH = std::max(1.0f, (float)d.h * s->sy / ch * (float)s->ext.height);
        vk_.CmdPushConstants(cb, pipeLayout, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(pc), &pc);
        vk_.CmdDraw(cb, 4, 1, 0, 0);
    }
    vk_.CmdEndRenderPass(cb);
    if (vk_.EndCommandBuffer(cb) != VK_SUCCESS) {
        // The acquired image is lost to this frame; mark the output for re-creation rather than leak images.
        s->broken = true;
        RLOG_E("duo: second screen command buffer failed");
        return;
    }

    VkSubmitInfo si{}; si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;  // no wait: the image is already free (acquireFence)
    si.commandBufferCount = 1;
    si.pCommandBuffers = &cb;
    si.signalSemaphoreCount = 1;
    si.pSignalSemaphores = &s->renderDone[img];
    if (vk_.QueueSubmit(graphicsQueue, 1, &si, s->fences[f]) != VK_SUCCESS) {
        s->broken = true;
        RLOG_E("duo: second screen submit failed");
        return;
    }
    VkPresentInfoKHR pi{}; pi.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
    pi.waitSemaphoreCount = 1;
    pi.pWaitSemaphores = &s->renderDone[img];
    pi.swapchainCount = 1;
    pi.pSwapchains = &s->swapchain;
    pi.pImageIndices = &img;
    r = vk_.QueuePresentKHR(graphicsQueue, &pi);
    if (r == VK_ERROR_OUT_OF_DATE_KHR || r == VK_ERROR_SURFACE_LOST_KHR) {
        s->broken = true;
        RLOG_E("duo: second screen present out of date (%d)", (int)r);
    }
    s->frame = (f + 1) % MAX_FRAMES_IN_FLIGHT;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeDuoSetSecondary(JNIEnv* env, jobject, jlong handle, jobject surface,
                                                                jfloat ox, jfloat oy, jfloat sx, jfloat sy) {
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (!r || !surface) return JNI_FALSE;
    ANativeWindow* win = ANativeWindow_fromSurface(env, surface);
    if (!win) return JNI_FALSE;
    return r->duoSetSecondary(win, ox, oy, sx, sy) ? JNI_TRUE : JNI_FALSE;  // takes the window reference
}

extern "C" JNIEXPORT void JNICALL
Java_com_winlator_renderer_VulkanRenderer_nativeDuoClearSecondary(JNIEnv*, jobject, jlong handle) {
    auto* r = reinterpret_cast<VulkanRendererContext*>(handle);
    if (r) r->duoClearSecondary();
}

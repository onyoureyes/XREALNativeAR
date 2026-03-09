/**
 * VulkanContext -- vk-bootstrap 기반 Vulkan 초기화.
 *
 * 참조: charles-lunarg/vk-bootstrap, ktzevani/native-camera-vulkan
 */

#include "vulkan_context.h"
#include "VkBootstrap.h"

#include <android/log.h>
#include <android/native_window.h>
#include <vulkan/vulkan_android.h>

#define TAG "VulkanContext"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

VulkanContext::~VulkanContext() {
    destroy();
}

bool VulkanContext::init(ANativeWindow* window) {
    if (initialized_) {
        LOGE("Already initialized");
        return false;
    }
    window_ = window;

    // ── 1. Instance ──
    vkb::InstanceBuilder instanceBuilder;
    auto instRet = instanceBuilder
        .set_app_name("XREALVulkanRenderer")
        .set_engine_name("XREALEngine")
        .require_api_version(1, 1, 0)   // Vulkan 1.1 (Adreno 730 지원)
#ifndef NDEBUG
        .request_validation_layers(true)
        .use_default_debug_messenger()
#endif
        .build();

    if (!instRet) {
        LOGE("Failed to create Vulkan instance: %s", instRet.error().message().c_str());
        return false;
    }
    auto vkbInstance = instRet.value();
    instance_ = vkbInstance.instance;
    LOGI("Vulkan instance created (API 1.1)");

    // ── 2. Surface (Android) ──
    VkAndroidSurfaceCreateInfoKHR surfaceInfo = {};
    surfaceInfo.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
    surfaceInfo.window = window_;
    if (vkCreateAndroidSurfaceKHR(instance_, &surfaceInfo, nullptr, &surface_) != VK_SUCCESS) {
        LOGE("Failed to create Android surface");
        return false;
    }
    LOGI("Android surface created");

    // ── 3. Physical Device ──
    vkb::PhysicalDeviceSelector selector(vkbInstance);
    auto physRet = selector
        .set_surface(surface_)
        .set_minimum_version(1, 1)
        .prefer_gpu_device_type(vkb::PreferredDeviceType::discrete)
        .select();

    if (!physRet) {
        LOGE("Failed to select physical device: %s", physRet.error().message().c_str());
        return false;
    }
    auto vkbPhysDevice = physRet.value();
    physicalDevice_ = vkbPhysDevice.physical_device;

    VkPhysicalDeviceProperties props;
    vkGetPhysicalDeviceProperties(physicalDevice_, &props);
    LOGI("GPU: %s (Vulkan %d.%d.%d)",
         props.deviceName,
         VK_VERSION_MAJOR(props.apiVersion),
         VK_VERSION_MINOR(props.apiVersion),
         VK_VERSION_PATCH(props.apiVersion));

    // ── 4. Logical Device ──
    vkb::DeviceBuilder deviceBuilder(vkbPhysDevice);
    auto devRet = deviceBuilder.build();
    if (!devRet) {
        LOGE("Failed to create logical device: %s", devRet.error().message().c_str());
        return false;
    }
    auto vkbDevice = devRet.value();
    device_ = vkbDevice.device;

    auto queueRet = vkbDevice.get_queue(vkb::QueueType::graphics);
    if (!queueRet) {
        LOGE("Failed to get graphics queue");
        return false;
    }
    graphicsQueue_ = queueRet.value();
    graphicsQueueFamily_ = vkbDevice.get_queue_index(vkb::QueueType::graphics).value();
    LOGI("Logical device created, queue family: %u", graphicsQueueFamily_);

    // ── 5. Swapchain ──
    int width = ANativeWindow_getWidth(window_);
    int height = ANativeWindow_getHeight(window_);

    vkb::SwapchainBuilder swapchainBuilder(vkbDevice);
    auto swapRet = swapchainBuilder
        .set_desired_format({VK_FORMAT_R8G8B8A8_UNORM, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR})
        .set_desired_present_mode(VK_PRESENT_MODE_FIFO_KHR)  // VSync
        .set_desired_extent(width, height)
        .set_desired_min_image_count(2)
        .build();

    if (!swapRet) {
        LOGE("Failed to create swapchain: %s", swapRet.error().message().c_str());
        return false;
    }
    auto vkbSwapchain = swapRet.value();
    swapchain_ = vkbSwapchain.swapchain;
    swapchainFormat_ = vkbSwapchain.image_format;
    swapchainExtent_ = vkbSwapchain.extent;
    swapchainImages_ = vkbSwapchain.get_images().value();
    swapchainImageViews_ = vkbSwapchain.get_image_views().value();
    LOGI("Swapchain created: %dx%d, %zu images, format=%d",
         swapchainExtent_.width, swapchainExtent_.height,
         swapchainImages_.size(), swapchainFormat_);

    // ── 6. Render Pass ──
    if (!createRenderPass()) return false;

    // ── 7. Framebuffers ──
    if (!createFramebuffers()) return false;

    // ── 8. Command Pool & Buffer ──
    if (!createCommandPool()) return false;

    // ── 9. Sync Objects ──
    if (!createSyncObjects()) return false;

    initialized_ = true;
    LOGI("Vulkan initialization complete");
    return true;
}

bool VulkanContext::createRenderPass() {
    VkAttachmentDescription colorAttachment = {};
    colorAttachment.format = swapchainFormat_;
    colorAttachment.samples = VK_SAMPLE_COUNT_1_BIT;
    colorAttachment.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    colorAttachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    colorAttachment.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    colorAttachment.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    colorAttachment.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    colorAttachment.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;

    VkAttachmentReference colorRef = {};
    colorRef.attachment = 0;
    colorRef.layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;

    VkSubpassDescription subpass = {};
    subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    subpass.colorAttachmentCount = 1;
    subpass.pColorAttachments = &colorRef;

    VkSubpassDependency dependency = {};
    dependency.srcSubpass = VK_SUBPASS_EXTERNAL;
    dependency.dstSubpass = 0;
    dependency.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dependency.srcAccessMask = 0;
    dependency.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dependency.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;

    VkRenderPassCreateInfo renderPassInfo = {};
    renderPassInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
    renderPassInfo.attachmentCount = 1;
    renderPassInfo.pAttachments = &colorAttachment;
    renderPassInfo.subpassCount = 1;
    renderPassInfo.pSubpasses = &subpass;
    renderPassInfo.dependencyCount = 1;
    renderPassInfo.pDependencies = &dependency;

    if (vkCreateRenderPass(device_, &renderPassInfo, nullptr, &renderPass_) != VK_SUCCESS) {
        LOGE("Failed to create render pass");
        return false;
    }
    LOGI("Render pass created");
    return true;
}

bool VulkanContext::createFramebuffers() {
    framebuffers_.resize(swapchainImageViews_.size());
    for (size_t i = 0; i < swapchainImageViews_.size(); i++) {
        VkImageView attachments[] = {swapchainImageViews_[i]};

        VkFramebufferCreateInfo fbInfo = {};
        fbInfo.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
        fbInfo.renderPass = renderPass_;
        fbInfo.attachmentCount = 1;
        fbInfo.pAttachments = attachments;
        fbInfo.width = swapchainExtent_.width;
        fbInfo.height = swapchainExtent_.height;
        fbInfo.layers = 1;

        if (vkCreateFramebuffer(device_, &fbInfo, nullptr, &framebuffers_[i]) != VK_SUCCESS) {
            LOGE("Failed to create framebuffer %zu", i);
            return false;
        }
    }
    LOGI("Framebuffers created: %zu", framebuffers_.size());
    return true;
}

bool VulkanContext::createCommandPool() {
    VkCommandPoolCreateInfo poolInfo = {};
    poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    poolInfo.queueFamilyIndex = graphicsQueueFamily_;

    if (vkCreateCommandPool(device_, &poolInfo, nullptr, &commandPool_) != VK_SUCCESS) {
        LOGE("Failed to create command pool");
        return false;
    }

    VkCommandBufferAllocateInfo allocInfo = {};
    allocInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    allocInfo.commandPool = commandPool_;
    allocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocInfo.commandBufferCount = 1;

    if (vkAllocateCommandBuffers(device_, &allocInfo, &commandBuffer_) != VK_SUCCESS) {
        LOGE("Failed to allocate command buffer");
        return false;
    }
    LOGI("Command pool & buffer created");
    return true;
}

bool VulkanContext::createSyncObjects() {
    VkSemaphoreCreateInfo semInfo = {};
    semInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;

    VkFenceCreateInfo fenceInfo = {};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;  // 첫 프레임에서 대기하지 않도록

    if (vkCreateSemaphore(device_, &semInfo, nullptr, &imageAvailableSemaphore_) != VK_SUCCESS ||
        vkCreateSemaphore(device_, &semInfo, nullptr, &renderFinishedSemaphore_) != VK_SUCCESS ||
        vkCreateFence(device_, &fenceInfo, nullptr, &inFlightFence_) != VK_SUCCESS) {
        LOGE("Failed to create sync objects");
        return false;
    }
    LOGI("Sync objects created");
    return true;
}

bool VulkanContext::beginFrame(uint32_t& imageIndex) {
    // 이전 프레임 완료 대기
    vkWaitForFences(device_, 1, &inFlightFence_, VK_TRUE, UINT64_MAX);
    vkResetFences(device_, 1, &inFlightFence_);

    // 스왑체인 이미지 획득
    VkResult result = vkAcquireNextImageKHR(device_, swapchain_, UINT64_MAX,
                                             imageAvailableSemaphore_, VK_NULL_HANDLE, &imageIndex);
    if (result == VK_ERROR_OUT_OF_DATE_KHR) {
        LOGI("Swapchain out of date, need recreation");
        return false;
    }
    if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR) {
        LOGE("Failed to acquire swapchain image: %d", result);
        return false;
    }

    // 커맨드 버퍼 리셋 & 기록 시작
    vkResetCommandBuffer(commandBuffer_, 0);

    VkCommandBufferBeginInfo beginInfo = {};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vkBeginCommandBuffer(commandBuffer_, &beginInfo);

    // 렌더패스 시작
    VkClearValue clearColor = {{{0.0f, 0.0f, 0.0f, 1.0f}}};  // 검정 배경

    VkRenderPassBeginInfo rpBeginInfo = {};
    rpBeginInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
    rpBeginInfo.renderPass = renderPass_;
    rpBeginInfo.framebuffer = framebuffers_[imageIndex];
    rpBeginInfo.renderArea.offset = {0, 0};
    rpBeginInfo.renderArea.extent = swapchainExtent_;
    rpBeginInfo.clearValueCount = 1;
    rpBeginInfo.pClearValues = &clearColor;

    vkCmdBeginRenderPass(commandBuffer_, &rpBeginInfo, VK_SUBPASS_CONTENTS_INLINE);

    // 뷰포트 & 시저 설정
    VkViewport viewport = {};
    viewport.x = 0.0f;
    viewport.y = 0.0f;
    viewport.width = (float)swapchainExtent_.width;
    viewport.height = (float)swapchainExtent_.height;
    viewport.minDepth = 0.0f;
    viewport.maxDepth = 1.0f;
    vkCmdSetViewport(commandBuffer_, 0, 1, &viewport);

    VkRect2D scissor = {};
    scissor.offset = {0, 0};
    scissor.extent = swapchainExtent_;
    vkCmdSetScissor(commandBuffer_, 0, 1, &scissor);

    return true;
}

bool VulkanContext::endFrame(uint32_t imageIndex) {
    vkCmdEndRenderPass(commandBuffer_);
    vkEndCommandBuffer(commandBuffer_);

    // 제출
    VkSemaphore waitSemaphores[] = {imageAvailableSemaphore_};
    VkPipelineStageFlags waitStages[] = {VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT};
    VkSemaphore signalSemaphores[] = {renderFinishedSemaphore_};

    VkSubmitInfo submitInfo = {};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.waitSemaphoreCount = 1;
    submitInfo.pWaitSemaphores = waitSemaphores;
    submitInfo.pWaitDstStageMask = waitStages;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &commandBuffer_;
    submitInfo.signalSemaphoreCount = 1;
    submitInfo.pSignalSemaphores = signalSemaphores;

    if (vkQueueSubmit(graphicsQueue_, 1, &submitInfo, inFlightFence_) != VK_SUCCESS) {
        LOGE("Failed to submit draw command buffer");
        return false;
    }

    // 프레젠트
    VkSwapchainKHR swapchains[] = {swapchain_};
    VkPresentInfoKHR presentInfo = {};
    presentInfo.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
    presentInfo.waitSemaphoreCount = 1;
    presentInfo.pWaitSemaphores = signalSemaphores;
    presentInfo.swapchainCount = 1;
    presentInfo.pSwapchains = swapchains;
    presentInfo.pImageIndices = &imageIndex;

    VkResult result = vkQueuePresentKHR(graphicsQueue_, &presentInfo);
    if (result == VK_ERROR_OUT_OF_DATE_KHR || result == VK_SUBOPTIMAL_KHR) {
        LOGI("Swapchain suboptimal/out-of-date on present");
        // 다음 프레임에서 처리
    }

    return true;
}

void VulkanContext::cleanupSwapchain() {
    for (auto fb : framebuffers_) {
        if (fb != VK_NULL_HANDLE) vkDestroyFramebuffer(device_, fb, nullptr);
    }
    framebuffers_.clear();

    for (auto iv : swapchainImageViews_) {
        if (iv != VK_NULL_HANDLE) vkDestroyImageView(device_, iv, nullptr);
    }
    swapchainImageViews_.clear();
    swapchainImages_.clear();

    if (swapchain_ != VK_NULL_HANDLE) {
        vkDestroySwapchainKHR(device_, swapchain_, nullptr);
        swapchain_ = VK_NULL_HANDLE;
    }
}

bool VulkanContext::recreateSwapchain(int width, int height) {
    vkDeviceWaitIdle(device_);
    cleanupSwapchain();

    // vk-bootstrap으로 재생성은 복잡하므로 수동 생성
    VkSwapchainCreateInfoKHR createInfo = {};
    createInfo.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
    createInfo.surface = surface_;
    createInfo.minImageCount = 2;
    createInfo.imageFormat = swapchainFormat_;
    createInfo.imageColorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
    createInfo.imageExtent = {(uint32_t)width, (uint32_t)height};
    createInfo.imageArrayLayers = 1;
    createInfo.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    createInfo.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    createInfo.preTransform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
    createInfo.compositeAlpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    createInfo.presentMode = VK_PRESENT_MODE_FIFO_KHR;
    createInfo.clipped = VK_TRUE;

    if (vkCreateSwapchainKHR(device_, &createInfo, nullptr, &swapchain_) != VK_SUCCESS) {
        LOGE("Failed to recreate swapchain");
        return false;
    }

    uint32_t imageCount;
    vkGetSwapchainImagesKHR(device_, swapchain_, &imageCount, nullptr);
    swapchainImages_.resize(imageCount);
    vkGetSwapchainImagesKHR(device_, swapchain_, &imageCount, swapchainImages_.data());

    swapchainExtent_ = {(uint32_t)width, (uint32_t)height};

    swapchainImageViews_.resize(imageCount);
    for (uint32_t i = 0; i < imageCount; i++) {
        VkImageViewCreateInfo viewInfo = {};
        viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        viewInfo.image = swapchainImages_[i];
        viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
        viewInfo.format = swapchainFormat_;
        viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        viewInfo.subresourceRange.baseMipLevel = 0;
        viewInfo.subresourceRange.levelCount = 1;
        viewInfo.subresourceRange.baseArrayLayer = 0;
        viewInfo.subresourceRange.layerCount = 1;

        if (vkCreateImageView(device_, &viewInfo, nullptr, &swapchainImageViews_[i]) != VK_SUCCESS) {
            LOGE("Failed to create image view %u", i);
            return false;
        }
    }

    if (!createFramebuffers()) return false;
    LOGI("Swapchain recreated: %dx%d", width, height);
    return true;
}

void VulkanContext::destroy() {
    if (!initialized_ && instance_ == VK_NULL_HANDLE) return;

    if (device_ != VK_NULL_HANDLE) {
        vkDeviceWaitIdle(device_);

        // 동기화 객체
        if (imageAvailableSemaphore_ != VK_NULL_HANDLE)
            vkDestroySemaphore(device_, imageAvailableSemaphore_, nullptr);
        if (renderFinishedSemaphore_ != VK_NULL_HANDLE)
            vkDestroySemaphore(device_, renderFinishedSemaphore_, nullptr);
        if (inFlightFence_ != VK_NULL_HANDLE)
            vkDestroyFence(device_, inFlightFence_, nullptr);

        // 커맨드 풀
        if (commandPool_ != VK_NULL_HANDLE)
            vkDestroyCommandPool(device_, commandPool_, nullptr);

        // 렌더패스
        if (renderPass_ != VK_NULL_HANDLE)
            vkDestroyRenderPass(device_, renderPass_, nullptr);

        // 스왑체인
        cleanupSwapchain();

        // 디바이스
        vkDestroyDevice(device_, nullptr);
    }

    // 서피스 & 인스턴스
    if (surface_ != VK_NULL_HANDLE)
        vkDestroySurfaceKHR(instance_, surface_, nullptr);
    if (instance_ != VK_NULL_HANDLE)
        vkDestroyInstance(instance_, nullptr);

    initialized_ = false;
    LOGI("Vulkan destroyed");
}

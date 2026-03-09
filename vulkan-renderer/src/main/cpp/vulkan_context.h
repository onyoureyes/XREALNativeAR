#pragma once

/**
 * VulkanContext -- Vulkan 인스턴스/디바이스/스왑체인 관리.
 *
 * vk-bootstrap을 사용하여 보일러플레이트를 최소화.
 * Galaxy Fold 4 (Adreno 730, Vulkan 1.1) 타겟.
 */

#include <android/native_window.h>
#include <vulkan/vulkan.h>
#include <vector>

class VulkanContext {
public:
    VulkanContext() = default;
    ~VulkanContext();

    /** ANativeWindow에서 Vulkan 초기화 */
    bool init(ANativeWindow* window);

    /** 스왑체인 재생성 (크기 변경 시) */
    bool recreateSwapchain(int width, int height);

    /** 리소스 정리 */
    void destroy();

    // ── Getters ──
    VkDevice device() const { return device_; }
    VkPhysicalDevice physicalDevice() const { return physicalDevice_; }
    VkQueue graphicsQueue() const { return graphicsQueue_; }
    uint32_t graphicsQueueFamily() const { return graphicsQueueFamily_; }
    VkRenderPass renderPass() const { return renderPass_; }
    VkExtent2D swapchainExtent() const { return swapchainExtent_; }
    VkFormat swapchainFormat() const { return swapchainFormat_; }
    uint32_t swapchainImageCount() const { return static_cast<uint32_t>(swapchainImageViews_.size()); }
    VkFramebuffer framebuffer(uint32_t index) const { return framebuffers_[index]; }
    VkCommandBuffer commandBuffer() const { return commandBuffer_; }
    VkCommandPool commandPool() const { return commandPool_; }
    bool isInitialized() const { return initialized_; }

    /** 프레임 시작: 스왑체인 이미지 획득 → 커맨드버퍼 시작 → 렌더패스 시작 */
    bool beginFrame(uint32_t& imageIndex);

    /** 프레임 종료: 렌더패스 종료 → 커맨드버퍼 종료 → 제출 → 프레젠트 */
    bool endFrame(uint32_t imageIndex);

private:
    bool createRenderPass();
    bool createFramebuffers();
    bool createCommandPool();
    bool createSyncObjects();
    void cleanupSwapchain();

    ANativeWindow* window_ = nullptr;
    bool initialized_ = false;

    // Vulkan 핵심 객체
    VkInstance instance_ = VK_NULL_HANDLE;
    VkSurfaceKHR surface_ = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice_ = VK_NULL_HANDLE;
    VkDevice device_ = VK_NULL_HANDLE;
    VkQueue graphicsQueue_ = VK_NULL_HANDLE;
    uint32_t graphicsQueueFamily_ = 0;

    // 스왑체인
    VkSwapchainKHR swapchain_ = VK_NULL_HANDLE;
    VkFormat swapchainFormat_ = VK_FORMAT_R8G8B8A8_UNORM;
    VkExtent2D swapchainExtent_ = {0, 0};
    std::vector<VkImage> swapchainImages_;
    std::vector<VkImageView> swapchainImageViews_;

    // 렌더패스 & 프레임버퍼
    VkRenderPass renderPass_ = VK_NULL_HANDLE;
    std::vector<VkFramebuffer> framebuffers_;

    // 커맨드 버퍼
    VkCommandPool commandPool_ = VK_NULL_HANDLE;
    VkCommandBuffer commandBuffer_ = VK_NULL_HANDLE;

    // 동기화
    VkSemaphore imageAvailableSemaphore_ = VK_NULL_HANDLE;
    VkSemaphore renderFinishedSemaphore_ = VK_NULL_HANDLE;
    VkFence inFlightFence_ = VK_NULL_HANDLE;
};

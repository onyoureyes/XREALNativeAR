#pragma once

/**
 * CameraBackground -- MJPEG 카메라 프레임을 Vulkan 텍스처로 렌더링.
 *
 * Phase 2: USB ISO 카메라 MJPEG → stb_image 디코드 → VkImage 텍스처 → 풀스크린 삼각형.
 * 스레드 안전: updateFrame()은 카메라 스레드, draw()는 렌더 스레드에서 호출.
 */

#include <vulkan/vulkan.h>
#include <mutex>
#include <atomic>
#include <cstdint>

class VulkanContext;

class CameraBackground {
public:
    CameraBackground() = default;
    ~CameraBackground();

    /** Vulkan 리소스 초기화 (텍스처, 스테이징 버퍼, 파이프라인) */
    bool init(VulkanContext& ctx);

    /**
     * 카메라 프레임 업데이트 (카메라 스레드에서 호출).
     * MJPEG 바이트를 RGBA로 디코드하여 내부 버퍼에 저장.
     * @param jpegData MJPEG 프레임 데이터
     * @param jpegSize 데이터 크기 (bytes)
     * @return true if decode 성공
     */
    bool updateFrame(const uint8_t* jpegData, int jpegSize);

    /**
     * 텍스처 GPU 업로드 + 풀스크린 렌더 (렌더 스레드에서 호출).
     * beginFrame()과 endFrame() 사이에서 호출.
     * @param cmd 현재 프레임의 커맨드 버퍼
     */
    void draw(VkCommandBuffer cmd);

    /** 새 프레임이 대기 중인지 확인 */
    bool hasNewFrame() const { return frameReady_.load(); }

    /** 카메라 텍스처가 유효한지 (한 번이라도 프레임 수신) */
    bool hasTexture() const { return textureValid_.load(); }

    /** 리소스 정리 */
    void destroy();

private:
    bool createTexture(int width, int height);
    bool createStagingBuffer(VkDeviceSize size);
    bool createDescriptorSetLayout();
    bool createDescriptorPool();
    bool createDescriptorSet();
    bool createPipeline();
    bool createSampler();
    void transitionImageLayout(VkCommandBuffer cmd, VkImage image,
                                VkImageLayout oldLayout, VkImageLayout newLayout);
    uint32_t findMemoryType(uint32_t typeFilter, VkMemoryPropertyFlags properties);

    VulkanContext* ctx_ = nullptr;
    VkDevice device_ = VK_NULL_HANDLE;

    // 텍스처
    VkImage textureImage_ = VK_NULL_HANDLE;
    VkDeviceMemory textureMemory_ = VK_NULL_HANDLE;
    VkImageView textureView_ = VK_NULL_HANDLE;
    VkSampler sampler_ = VK_NULL_HANDLE;
    int texWidth_ = 0;
    int texHeight_ = 0;

    // 스테이징 버퍼 (CPU→GPU 전송용)
    VkBuffer stagingBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory stagingMemory_ = VK_NULL_HANDLE;
    void* stagingMapped_ = nullptr;
    VkDeviceSize stagingSize_ = 0;

    // 파이프라인
    VkPipelineLayout pipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout descriptorSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet descriptorSet_ = VK_NULL_HANDLE;

    // 프레임 데이터 (스레드 간 공유)
    std::mutex frameMutex_;
    uint8_t* pendingPixels_ = nullptr;      // RGBA 디코드 결과
    int pendingWidth_ = 0;
    int pendingHeight_ = 0;
    std::atomic<bool> frameReady_{false};
    std::atomic<bool> textureValid_{false};
    bool textureInitialized_ = false;        // VkImage 이미지 레이아웃 전환 완료 여부
};

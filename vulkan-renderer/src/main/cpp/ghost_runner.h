#pragma once

/**
 * GhostRunner -- 프로시저럴 캡슐 메쉬 고스트 러너.
 *
 * Phase 4: PacemakerService SpatialAnchorEvent("pacemaker_dot") → 3D 캡슐 렌더링.
 * 프레넬 가장자리 글로우 + 시간 기반 펄스 효과.
 */

#include <vulkan/vulkan.h>
#include <mutex>
#include <atomic>
#include <vector>
#include "math_utils.h"

class VulkanContext;

struct GhostVertex {
    float pos[3];
    float normal[3];
};

class GhostRunner {
public:
    GhostRunner() = default;
    ~GhostRunner();

    bool init(VulkanContext& ctx);

    /** 고스트 러너 포즈 설정 (EventBus 스레드에서 호출 가능) */
    void setPose(float x, float y, float z, float yaw, float animPhase);

    /** 가시성 설정 */
    void setVisible(bool visible) { visible_.store(visible); }

    /** 색상 설정 (RGBA) */
    void setColor(float r, float g, float b, float a);

    /** 렌더 (렌더 스레드에서 호출) */
    void draw(VkCommandBuffer cmd);

    void destroy();

private:
    void generateCapsuleMesh(float radius, float height, int segments);
    bool createVertexBuffer();
    bool createIndexBuffer();
    bool createUniformBuffer();
    bool createDescriptorSetLayout();
    bool createDescriptorPool();
    bool createDescriptorSet();
    bool createPipeline();
    uint32_t findMemoryType(uint32_t typeFilter, VkMemoryPropertyFlags properties);
    void updateUBO();

    VulkanContext* ctx_ = nullptr;
    VkDevice device_ = VK_NULL_HANDLE;

    // 파이프라인
    VkPipelineLayout pipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout descriptorSetLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet descriptorSet_ = VK_NULL_HANDLE;

    // 버퍼
    VkBuffer vertexBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory vertexMemory_ = VK_NULL_HANDLE;
    VkBuffer indexBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory indexMemory_ = VK_NULL_HANDLE;
    VkBuffer uniformBuffer_ = VK_NULL_HANDLE;
    VkDeviceMemory uniformMemory_ = VK_NULL_HANDLE;
    void* uniformMapped_ = nullptr;

    // 메쉬 데이터
    std::vector<GhostVertex> vertices_;
    std::vector<uint16_t> indices_;

    // UBO 구조체 (ghost.vert 레이아웃과 일치)
    struct GhostUBO {
        float model[16];
        float view[16];
        float projection[16];
        float ghostColor[4];   // RGBA
        float animPhase;
        float padding[3];
    };

    // 포즈 + 상태 (스레드 간 공유)
    std::mutex poseMutex_;
    float ghostX_ = 0, ghostY_ = 0, ghostZ_ = 0;
    float ghostYaw_ = 0;
    float animPhase_ = 0;
    float colorR_ = 0.2f, colorG_ = 0.8f, colorB_ = 1.0f, colorA_ = 0.7f;
    std::atomic<bool> visible_{false};
    std::atomic<bool> poseSet_{false};
};

#pragma once

/**
 * MeshRenderer -- MVP 기반 3D 메쉬 렌더링.
 *
 * Phase 3: VIO 포즈 → 뷰 매트릭스 → 월드 공간 고정 3D 큐브.
 * UBO: model + view + projection (3×mat4).
 * 버텍스: position(vec3) + color(vec3).
 */

#include <vulkan/vulkan.h>
#include <mutex>
#include <atomic>
#include "math_utils.h"

class VulkanContext;

struct MeshVertex {
    float pos[3];
    float color[3];
};

class MeshRenderer {
public:
    MeshRenderer() = default;
    ~MeshRenderer();

    bool init(VulkanContext& ctx);

    /** VIO 포즈 업데이트 (EventBus 스레드에서 호출) */
    void setPose(float x, float y, float z,
                 float qx, float qy, float qz, float qw);

    /** 프레임 렌더 (렌더 스레드에서 호출) */
    void draw(VkCommandBuffer cmd);

    void destroy();

private:
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
    uint32_t indexCount_ = 0;

    // MVP
    struct MVPData {
        float model[16];
        float view[16];
        float projection[16];
    };

    // 포즈 (스레드 간 공유)
    std::mutex poseMutex_;
    math::Mat4 viewMatrix_ = math::Mat4::identity();
    math::Mat4 projMatrix_ = math::Mat4::identity();
    std::atomic<bool> poseValid_{false};
};

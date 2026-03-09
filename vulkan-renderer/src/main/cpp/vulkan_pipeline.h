#pragma once

/**
 * VulkanPipeline -- 그래픽 파이프라인 관리.
 *
 * Phase 1: 삼각형 렌더링용 단일 파이프라인.
 * 셰이더는 SPIR-V 바이트코드로 임베딩 (빌드 타임 도구 불필요).
 */

#include <vulkan/vulkan.h>

class VulkanContext;

class VulkanPipeline {
public:
    VulkanPipeline() = default;
    ~VulkanPipeline();

    /** 삼각형 파이프라인 생성 */
    bool createTrianglePipeline(VulkanContext& ctx);

    /** 삼각형 그리기 */
    void drawTriangle(VkCommandBuffer cmd);

    /** 리소스 정리 */
    void destroy(VkDevice device);

private:
    VkShaderModule createShaderModule(VkDevice device, const uint32_t* code, size_t codeSize);

    VkPipelineLayout pipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
    VkDevice device_ = VK_NULL_HANDLE;
};

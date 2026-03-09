/**
 * GhostRunner -- 프로시저럴 캡슐 메쉬 고스트 러너 렌더링.
 *
 * 캡슐: 반지름 0.2m, 높이 1.6m (인체 비율), 24 세그먼트.
 * 프레넬 가장자리 글로우 + 펄스 효과.
 */

#include "ghost_runner.h"
#include "vulkan_context.h"

#include <android/log.h>
#include <cstring>
#include <cmath>

#define TAG "GhostRunner"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#include "shaders/ghost_vert_spv.h"
#include "shaders/ghost_frag_spv.h"

#ifndef M_PI
#define M_PI 3.14159265358979323846f
#endif

GhostRunner::~GhostRunner() {
    destroy();
}

// ── 프로시저럴 캡슐 메쉬 생성 ──

void GhostRunner::generateCapsuleMesh(float radius, float height, int segments) {
    vertices_.clear();
    indices_.clear();

    float halfHeight = height / 2.0f - radius;
    int rings = segments / 2;

    // 상단 반구 (Y 위쪽 = 음수 Y, OpenCV 프레임)
    for (int i = 0; i <= rings; i++) {
        float phi = (float)i / rings * (M_PI / 2.0f);
        float y = -halfHeight - radius * cosf(phi);
        float r = radius * sinf(phi);

        for (int j = 0; j <= segments; j++) {
            float theta = (float)j / segments * 2.0f * M_PI;
            float x = r * cosf(theta);
            float z = r * sinf(theta);

            float nx = sinf(phi) * cosf(theta);
            float ny = -cosf(phi);
            float nz = sinf(phi) * sinf(theta);

            GhostVertex v;
            v.pos[0] = x; v.pos[1] = y; v.pos[2] = z;
            v.normal[0] = nx; v.normal[1] = ny; v.normal[2] = nz;
            vertices_.push_back(v);
        }
    }

    // 원통 몸체
    for (int i = 0; i <= 1; i++) {
        float y = (i == 0) ? -halfHeight : halfHeight;

        for (int j = 0; j <= segments; j++) {
            float theta = (float)j / segments * 2.0f * M_PI;
            float x = radius * cosf(theta);
            float z = radius * sinf(theta);

            GhostVertex v;
            v.pos[0] = x; v.pos[1] = y; v.pos[2] = z;
            v.normal[0] = cosf(theta); v.normal[1] = 0; v.normal[2] = sinf(theta);
            vertices_.push_back(v);
        }
    }

    // 하단 반구
    for (int i = 0; i <= rings; i++) {
        float phi = (float)i / rings * (M_PI / 2.0f);
        float y = halfHeight + radius * cosf(M_PI / 2.0f - phi);
        float r = radius * cosf(phi);

        for (int j = 0; j <= segments; j++) {
            float theta = (float)j / segments * 2.0f * M_PI;
            float x = r * cosf(theta);
            float z = r * sinf(theta);

            float nx = cosf(phi) * cosf(theta);
            float ny = sinf(phi);
            float nz = cosf(phi) * sinf(theta);

            GhostVertex v;
            v.pos[0] = x; v.pos[1] = y; v.pos[2] = z;
            v.normal[0] = nx; v.normal[1] = ny; v.normal[2] = nz;
            vertices_.push_back(v);
        }
    }

    // 인덱스 생성
    int totalRings = rings + 1 + 1 + rings + 1;
    int vertsPerRing = segments + 1;

    for (int i = 0; i < totalRings - 1; i++) {
        for (int j = 0; j < segments; j++) {
            uint16_t a = i * vertsPerRing + j;
            uint16_t b = a + 1;
            uint16_t c = a + vertsPerRing;
            uint16_t d = c + 1;

            if (a < vertices_.size() && b < vertices_.size() &&
                c < vertices_.size() && d < vertices_.size()) {
                indices_.push_back(a);
                indices_.push_back(c);
                indices_.push_back(b);
                indices_.push_back(b);
                indices_.push_back(c);
                indices_.push_back(d);
            }
        }
    }

    LOGI("Capsule mesh: %zu vertices, %zu indices", vertices_.size(), indices_.size());
}

bool GhostRunner::init(VulkanContext& ctx) {
    ctx_ = &ctx;
    device_ = ctx.device();

    generateCapsuleMesh(0.2f, 1.6f, 24);

    if (!createVertexBuffer()) return false;
    if (!createIndexBuffer()) return false;
    if (!createUniformBuffer()) return false;
    if (!createDescriptorSetLayout()) return false;
    if (!createDescriptorPool()) return false;
    if (!createDescriptorSet()) return false;
    if (!createPipeline()) return false;

    LOGI("GhostRunner initialized (capsule 0.2m radius, 1.6m height)");
    return true;
}

void GhostRunner::setPose(float x, float y, float z, float yaw, float animPhase) {
    std::lock_guard<std::mutex> lock(poseMutex_);
    ghostX_ = x; ghostY_ = y; ghostZ_ = z;
    ghostYaw_ = yaw;
    animPhase_ = animPhase;
    poseSet_.store(true);
}

void GhostRunner::setColor(float r, float g, float b, float a) {
    std::lock_guard<std::mutex> lock(poseMutex_);
    colorR_ = r; colorG_ = g; colorB_ = b; colorA_ = a;
}

void GhostRunner::draw(VkCommandBuffer cmd) {
    if (!visible_.load() || !poseSet_.load() || pipeline_ == VK_NULL_HANDLE) return;

    updateUBO();

    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline_);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                             pipelineLayout_, 0, 1, &descriptorSet_, 0, nullptr);

    VkDeviceSize offset = 0;
    vkCmdBindVertexBuffers(cmd, 0, 1, &vertexBuffer_, &offset);
    vkCmdBindIndexBuffer(cmd, indexBuffer_, 0, VK_INDEX_TYPE_UINT16);
    vkCmdDrawIndexed(cmd, (uint32_t)indices_.size(), 1, 0, 0, 0);
}

void GhostRunner::updateUBO() {
    GhostUBO ubo;

    {
        std::lock_guard<std::mutex> lock(poseMutex_);
        // 모델 행렬: 위치 + Y축 회전
        math::Mat4 trans = math::translate(ghostX_, ghostY_, ghostZ_);
        math::Mat4 rot = math::rotateY(ghostYaw_);
        math::Mat4 model = math::multiply(trans, rot);
        memcpy(ubo.model, model.m, sizeof(float) * 16);

        ubo.ghostColor[0] = colorR_;
        ubo.ghostColor[1] = colorG_;
        ubo.ghostColor[2] = colorB_;
        ubo.ghostColor[3] = colorA_;
        ubo.animPhase = animPhase_;
    }

    // View + Projection은 MeshRenderer와 동일한 카메라 매트릭스 사용
    // 여기서는 identity view + RGB 카메라 projection 사용 (setPose에서 월드 좌표 직접 전달)
    math::Mat4 identity = math::Mat4::identity();
    memcpy(ubo.view, identity.m, sizeof(float) * 16);
    math::Mat4 proj = math::intrinsicsToProjection(
        914.0f, 914.0f, 640.0f, 480.0f, 1280.0f, 960.0f, 0.05f, 100.0f);
    memcpy(ubo.projection, proj.m, sizeof(float) * 16);

    memset(ubo.padding, 0, sizeof(ubo.padding));
    memcpy(uniformMapped_, &ubo, sizeof(GhostUBO));
}

// ── 리소스 생성 (MeshRenderer와 동일한 패턴) ──

uint32_t GhostRunner::findMemoryType(uint32_t typeFilter, VkMemoryPropertyFlags properties) {
    VkPhysicalDeviceMemoryProperties memProps;
    vkGetPhysicalDeviceMemoryProperties(ctx_->physicalDevice(), &memProps);
    for (uint32_t i = 0; i < memProps.memoryTypeCount; i++) {
        if ((typeFilter & (1 << i)) &&
            (memProps.memoryTypes[i].propertyFlags & properties) == properties)
            return i;
    }
    return 0;
}

bool GhostRunner::createVertexBuffer() {
    VkDeviceSize size = sizeof(GhostVertex) * vertices_.size();
    VkBufferCreateInfo bufInfo = {};
    bufInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufInfo.size = size;
    bufInfo.usage = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
    if (vkCreateBuffer(device_, &bufInfo, nullptr, &vertexBuffer_) != VK_SUCCESS) return false;

    VkMemoryRequirements memReqs;
    vkGetBufferMemoryRequirements(device_, vertexBuffer_, &memReqs);
    VkMemoryAllocateInfo allocInfo = {};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memReqs.size;
    allocInfo.memoryTypeIndex = findMemoryType(memReqs.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (vkAllocateMemory(device_, &allocInfo, nullptr, &vertexMemory_) != VK_SUCCESS) return false;
    vkBindBufferMemory(device_, vertexBuffer_, vertexMemory_, 0);

    void* data;
    vkMapMemory(device_, vertexMemory_, 0, size, 0, &data);
    memcpy(data, vertices_.data(), size);
    vkUnmapMemory(device_, vertexMemory_);
    return true;
}

bool GhostRunner::createIndexBuffer() {
    VkDeviceSize size = sizeof(uint16_t) * indices_.size();
    VkBufferCreateInfo bufInfo = {};
    bufInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufInfo.size = size;
    bufInfo.usage = VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
    if (vkCreateBuffer(device_, &bufInfo, nullptr, &indexBuffer_) != VK_SUCCESS) return false;

    VkMemoryRequirements memReqs;
    vkGetBufferMemoryRequirements(device_, indexBuffer_, &memReqs);
    VkMemoryAllocateInfo allocInfo = {};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memReqs.size;
    allocInfo.memoryTypeIndex = findMemoryType(memReqs.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (vkAllocateMemory(device_, &allocInfo, nullptr, &indexMemory_) != VK_SUCCESS) return false;
    vkBindBufferMemory(device_, indexBuffer_, indexMemory_, 0);

    void* data;
    vkMapMemory(device_, indexMemory_, 0, size, 0, &data);
    memcpy(data, indices_.data(), size);
    vkUnmapMemory(device_, indexMemory_);
    return true;
}

bool GhostRunner::createUniformBuffer() {
    VkDeviceSize size = sizeof(GhostUBO);
    VkBufferCreateInfo bufInfo = {};
    bufInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufInfo.size = size;
    bufInfo.usage = VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
    if (vkCreateBuffer(device_, &bufInfo, nullptr, &uniformBuffer_) != VK_SUCCESS) return false;

    VkMemoryRequirements memReqs;
    vkGetBufferMemoryRequirements(device_, uniformBuffer_, &memReqs);
    VkMemoryAllocateInfo allocInfo = {};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memReqs.size;
    allocInfo.memoryTypeIndex = findMemoryType(memReqs.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (vkAllocateMemory(device_, &allocInfo, nullptr, &uniformMemory_) != VK_SUCCESS) return false;
    vkBindBufferMemory(device_, uniformBuffer_, uniformMemory_, 0);
    vkMapMemory(device_, uniformMemory_, 0, size, 0, &uniformMapped_);
    return true;
}

bool GhostRunner::createDescriptorSetLayout() {
    VkDescriptorSetLayoutBinding binding = {};
    binding.binding = 0;
    binding.descriptorCount = 1;
    binding.descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    binding.stageFlags = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;

    VkDescriptorSetLayoutCreateInfo info = {};
    info.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    info.bindingCount = 1;
    info.pBindings = &binding;
    return vkCreateDescriptorSetLayout(device_, &info, nullptr, &descriptorSetLayout_) == VK_SUCCESS;
}

bool GhostRunner::createDescriptorPool() {
    VkDescriptorPoolSize poolSize = {};
    poolSize.type = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    poolSize.descriptorCount = 1;
    VkDescriptorPoolCreateInfo info = {};
    info.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    info.poolSizeCount = 1;
    info.pPoolSizes = &poolSize;
    info.maxSets = 1;
    return vkCreateDescriptorPool(device_, &info, nullptr, &descriptorPool_) == VK_SUCCESS;
}

bool GhostRunner::createDescriptorSet() {
    VkDescriptorSetAllocateInfo allocInfo = {};
    allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocInfo.descriptorPool = descriptorPool_;
    allocInfo.descriptorSetCount = 1;
    allocInfo.pSetLayouts = &descriptorSetLayout_;
    if (vkAllocateDescriptorSets(device_, &allocInfo, &descriptorSet_) != VK_SUCCESS) return false;

    VkDescriptorBufferInfo bufInfo = {};
    bufInfo.buffer = uniformBuffer_;
    bufInfo.offset = 0;
    bufInfo.range = sizeof(GhostUBO);

    VkWriteDescriptorSet write = {};
    write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    write.dstSet = descriptorSet_;
    write.dstBinding = 0;
    write.descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    write.descriptorCount = 1;
    write.pBufferInfo = &bufInfo;
    vkUpdateDescriptorSets(device_, 1, &write, 0, nullptr);
    return true;
}

bool GhostRunner::createPipeline() {
    VkShaderModuleCreateInfo vertInfo = {};
    vertInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    vertInfo.codeSize = ghost_vert_spv_size;
    vertInfo.pCode = ghost_vert_spv;
    VkShaderModule vertModule;
    if (vkCreateShaderModule(device_, &vertInfo, nullptr, &vertModule) != VK_SUCCESS) return false;

    VkShaderModuleCreateInfo fragInfo = {};
    fragInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    fragInfo.codeSize = ghost_frag_spv_size;
    fragInfo.pCode = ghost_frag_spv;
    VkShaderModule fragModule;
    if (vkCreateShaderModule(device_, &fragInfo, nullptr, &fragModule) != VK_SUCCESS) {
        vkDestroyShaderModule(device_, vertModule, nullptr);
        return false;
    }

    VkPipelineShaderStageCreateInfo stages[2] = {};
    stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
    stages[0].module = vertModule;
    stages[0].pName = "main";
    stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
    stages[1].module = fragModule;
    stages[1].pName = "main";

    // 버텍스 인풋: position(vec3) + normal(vec3)
    VkVertexInputBindingDescription bindingDesc = {};
    bindingDesc.stride = sizeof(GhostVertex);
    VkVertexInputAttributeDescription attrDescs[2] = {};
    attrDescs[0].location = 0; attrDescs[0].format = VK_FORMAT_R32G32B32_SFLOAT; attrDescs[0].offset = offsetof(GhostVertex, pos);
    attrDescs[1].location = 1; attrDescs[1].format = VK_FORMAT_R32G32B32_SFLOAT; attrDescs[1].offset = offsetof(GhostVertex, normal);

    VkPipelineVertexInputStateCreateInfo vertexInput = {};
    vertexInput.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
    vertexInput.vertexBindingDescriptionCount = 1;
    vertexInput.pVertexBindingDescriptions = &bindingDesc;
    vertexInput.vertexAttributeDescriptionCount = 2;
    vertexInput.pVertexAttributeDescriptions = attrDescs;

    VkPipelineInputAssemblyStateCreateInfo inputAssembly = {};
    inputAssembly.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
    inputAssembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;

    VkPipelineViewportStateCreateInfo viewportState = {};
    viewportState.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
    viewportState.viewportCount = 1;
    viewportState.scissorCount = 1;

    VkPipelineRasterizationStateCreateInfo rasterizer = {};
    rasterizer.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
    rasterizer.polygonMode = VK_POLYGON_MODE_FILL;
    rasterizer.lineWidth = 1.0f;
    rasterizer.cullMode = VK_CULL_MODE_BACK_BIT;
    rasterizer.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;

    VkPipelineMultisampleStateCreateInfo multisampling = {};
    multisampling.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
    multisampling.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

    VkPipelineDepthStencilStateCreateInfo depthStencil = {};
    depthStencil.sType = VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO;
    depthStencil.depthTestEnable = VK_FALSE;
    depthStencil.depthWriteEnable = VK_FALSE;

    // 알파 블렌딩 (반투명 고스트)
    VkPipelineColorBlendAttachmentState colorAttachment = {};
    colorAttachment.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
                                     VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
    colorAttachment.blendEnable = VK_TRUE;
    colorAttachment.srcColorBlendFactor = VK_BLEND_FACTOR_SRC_ALPHA;
    colorAttachment.dstColorBlendFactor = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
    colorAttachment.colorBlendOp = VK_BLEND_OP_ADD;
    colorAttachment.srcAlphaBlendFactor = VK_BLEND_FACTOR_ONE;
    colorAttachment.dstAlphaBlendFactor = VK_BLEND_FACTOR_ZERO;
    colorAttachment.alphaBlendOp = VK_BLEND_OP_ADD;

    VkPipelineColorBlendStateCreateInfo colorBlending = {};
    colorBlending.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
    colorBlending.attachmentCount = 1;
    colorBlending.pAttachments = &colorAttachment;

    VkDynamicState dynamicStates[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo dynamicState = {};
    dynamicState.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
    dynamicState.dynamicStateCount = 2;
    dynamicState.pDynamicStates = dynamicStates;

    VkPipelineLayoutCreateInfo layoutInfo = {};
    layoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    layoutInfo.setLayoutCount = 1;
    layoutInfo.pSetLayouts = &descriptorSetLayout_;
    if (vkCreatePipelineLayout(device_, &layoutInfo, nullptr, &pipelineLayout_) != VK_SUCCESS) {
        vkDestroyShaderModule(device_, vertModule, nullptr);
        vkDestroyShaderModule(device_, fragModule, nullptr);
        return false;
    }

    VkGraphicsPipelineCreateInfo pipelineInfo = {};
    pipelineInfo.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
    pipelineInfo.stageCount = 2;
    pipelineInfo.pStages = stages;
    pipelineInfo.pVertexInputState = &vertexInput;
    pipelineInfo.pInputAssemblyState = &inputAssembly;
    pipelineInfo.pViewportState = &viewportState;
    pipelineInfo.pRasterizationState = &rasterizer;
    pipelineInfo.pMultisampleState = &multisampling;
    pipelineInfo.pDepthStencilState = &depthStencil;
    pipelineInfo.pColorBlendState = &colorBlending;
    pipelineInfo.pDynamicState = &dynamicState;
    pipelineInfo.layout = pipelineLayout_;
    pipelineInfo.renderPass = ctx_->renderPass();
    pipelineInfo.subpass = 0;

    VkResult result = vkCreateGraphicsPipelines(device_, VK_NULL_HANDLE, 1, &pipelineInfo,
                                                  nullptr, &pipeline_);
    vkDestroyShaderModule(device_, vertModule, nullptr);
    vkDestroyShaderModule(device_, fragModule, nullptr);

    if (result != VK_SUCCESS) { LOGE("Failed to create ghost pipeline"); return false; }
    LOGI("Ghost runner pipeline created");
    return true;
}

void GhostRunner::destroy() {
    if (device_ == VK_NULL_HANDLE) return;
    vkDeviceWaitIdle(device_);

    if (pipeline_) { vkDestroyPipeline(device_, pipeline_, nullptr); pipeline_ = VK_NULL_HANDLE; }
    if (pipelineLayout_) { vkDestroyPipelineLayout(device_, pipelineLayout_, nullptr); pipelineLayout_ = VK_NULL_HANDLE; }
    if (descriptorPool_) { vkDestroyDescriptorPool(device_, descriptorPool_, nullptr); descriptorPool_ = VK_NULL_HANDLE; }
    if (descriptorSetLayout_) { vkDestroyDescriptorSetLayout(device_, descriptorSetLayout_, nullptr); descriptorSetLayout_ = VK_NULL_HANDLE; }
    if (uniformBuffer_) { vkUnmapMemory(device_, uniformMemory_); vkDestroyBuffer(device_, uniformBuffer_, nullptr); vkFreeMemory(device_, uniformMemory_, nullptr); uniformBuffer_ = VK_NULL_HANDLE; }
    if (indexBuffer_) { vkDestroyBuffer(device_, indexBuffer_, nullptr); vkFreeMemory(device_, indexMemory_, nullptr); indexBuffer_ = VK_NULL_HANDLE; }
    if (vertexBuffer_) { vkDestroyBuffer(device_, vertexBuffer_, nullptr); vkFreeMemory(device_, vertexMemory_, nullptr); vertexBuffer_ = VK_NULL_HANDLE; }

    device_ = VK_NULL_HANDLE;
    LOGI("GhostRunner destroyed");
}

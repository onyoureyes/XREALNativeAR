/**
 * MeshRenderer -- 3D 큐브 렌더링 (MVP UBO 기반).
 *
 * Phase 3 검증용: 월드 원점 (0,0,-2)에 0.3m 큐브 배치.
 * VIO 포즈 업데이트 시 뷰 매트릭스 갱신 → 큐브가 월드 공간에 고정.
 */

#include "mesh_renderer.h"
#include "vulkan_context.h"

#include <android/log.h>
#include <cstring>

#define TAG "MeshRenderer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#include "shaders/mesh_vert_spv.h"
#include "shaders/mesh_frag_spv.h"

// ── 큐브 메쉬 데이터 ──

static const MeshVertex cubeVertices[] = {
    // Front face (Z+) — cyan
    {{-0.15f, -0.15f,  0.15f}, {0.0f, 1.0f, 1.0f}},
    {{ 0.15f, -0.15f,  0.15f}, {0.0f, 1.0f, 1.0f}},
    {{ 0.15f,  0.15f,  0.15f}, {0.0f, 0.8f, 0.8f}},
    {{-0.15f,  0.15f,  0.15f}, {0.0f, 0.8f, 0.8f}},
    // Back face (Z-) — magenta
    {{ 0.15f, -0.15f, -0.15f}, {1.0f, 0.0f, 1.0f}},
    {{-0.15f, -0.15f, -0.15f}, {1.0f, 0.0f, 1.0f}},
    {{-0.15f,  0.15f, -0.15f}, {0.8f, 0.0f, 0.8f}},
    {{ 0.15f,  0.15f, -0.15f}, {0.8f, 0.0f, 0.8f}},
    // Top face (Y-) — green
    {{-0.15f, -0.15f, -0.15f}, {0.0f, 1.0f, 0.0f}},
    {{ 0.15f, -0.15f, -0.15f}, {0.0f, 1.0f, 0.0f}},
    {{ 0.15f, -0.15f,  0.15f}, {0.0f, 0.8f, 0.0f}},
    {{-0.15f, -0.15f,  0.15f}, {0.0f, 0.8f, 0.0f}},
    // Bottom face (Y+) — red
    {{-0.15f,  0.15f,  0.15f}, {1.0f, 0.0f, 0.0f}},
    {{ 0.15f,  0.15f,  0.15f}, {1.0f, 0.0f, 0.0f}},
    {{ 0.15f,  0.15f, -0.15f}, {0.8f, 0.0f, 0.0f}},
    {{-0.15f,  0.15f, -0.15f}, {0.8f, 0.0f, 0.0f}},
    // Right face (X+) — yellow
    {{ 0.15f, -0.15f,  0.15f}, {1.0f, 1.0f, 0.0f}},
    {{ 0.15f, -0.15f, -0.15f}, {1.0f, 1.0f, 0.0f}},
    {{ 0.15f,  0.15f, -0.15f}, {0.8f, 0.8f, 0.0f}},
    {{ 0.15f,  0.15f,  0.15f}, {0.8f, 0.8f, 0.0f}},
    // Left face (X-) — blue
    {{-0.15f, -0.15f, -0.15f}, {0.0f, 0.0f, 1.0f}},
    {{-0.15f, -0.15f,  0.15f}, {0.0f, 0.0f, 1.0f}},
    {{-0.15f,  0.15f,  0.15f}, {0.0f, 0.0f, 0.8f}},
    {{-0.15f,  0.15f, -0.15f}, {0.0f, 0.0f, 0.8f}},
};

static const uint16_t cubeIndices[] = {
     0,  1,  2,  2,  3,  0,  // front
     4,  5,  6,  6,  7,  4,  // back
     8,  9, 10, 10, 11,  8,  // top
    12, 13, 14, 14, 15, 12,  // bottom
    16, 17, 18, 18, 19, 16,  // right
    20, 21, 22, 22, 23, 20,  // left
};

MeshRenderer::~MeshRenderer() {
    destroy();
}

bool MeshRenderer::init(VulkanContext& ctx) {
    ctx_ = &ctx;
    device_ = ctx.device();

    // 기본 투영 매트릭스 (RGB 카메라 내재 파라미터)
    // fx=914, fy=914, cx=640, cy=480, 1280x960
    projMatrix_ = math::intrinsicsToProjection(
        914.0f, 914.0f, 640.0f, 480.0f, 1280.0f, 960.0f, 0.05f, 100.0f);

    if (!createVertexBuffer()) return false;
    if (!createIndexBuffer()) return false;
    if (!createUniformBuffer()) return false;
    if (!createDescriptorSetLayout()) return false;
    if (!createDescriptorPool()) return false;
    if (!createDescriptorSet()) return false;
    if (!createPipeline()) return false;

    LOGI("MeshRenderer initialized (cube at origin, waiting for VIO pose)");
    return true;
}

void MeshRenderer::setPose(float x, float y, float z,
                            float qx, float qy, float qz, float qw) {
    // camera-to-world → world-to-camera (view matrix)
    math::Mat4 cam2world = math::poseToMatrix(x, y, z, qx, qy, qz, qw);
    math::Mat4 view = math::invertRigid(cam2world);

    {
        std::lock_guard<std::mutex> lock(poseMutex_);
        viewMatrix_ = view;
        poseValid_.store(true);
    }
}

void MeshRenderer::draw(VkCommandBuffer cmd) {
    if (!poseValid_.load() || pipeline_ == VK_NULL_HANDLE) return;

    updateUBO();

    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline_);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                             pipelineLayout_, 0, 1, &descriptorSet_, 0, nullptr);

    VkDeviceSize offset = 0;
    vkCmdBindVertexBuffers(cmd, 0, 1, &vertexBuffer_, &offset);
    vkCmdBindIndexBuffer(cmd, indexBuffer_, 0, VK_INDEX_TYPE_UINT16);
    vkCmdDrawIndexed(cmd, indexCount_, 1, 0, 0, 0);
}

void MeshRenderer::updateUBO() {
    MVPData mvp;

    // 모델: 카메라 전방 2m 위치에 큐브 배치
    math::Mat4 model = math::translate(0.0f, 0.0f, 2.0f);
    memcpy(mvp.model, model.m, sizeof(float) * 16);

    {
        std::lock_guard<std::mutex> lock(poseMutex_);
        memcpy(mvp.view, viewMatrix_.m, sizeof(float) * 16);
    }
    memcpy(mvp.projection, projMatrix_.m, sizeof(float) * 16);

    memcpy(uniformMapped_, &mvp, sizeof(MVPData));
}

// ── 리소스 생성 ──

uint32_t MeshRenderer::findMemoryType(uint32_t typeFilter, VkMemoryPropertyFlags properties) {
    VkPhysicalDeviceMemoryProperties memProps;
    vkGetPhysicalDeviceMemoryProperties(ctx_->physicalDevice(), &memProps);
    for (uint32_t i = 0; i < memProps.memoryTypeCount; i++) {
        if ((typeFilter & (1 << i)) &&
            (memProps.memoryTypes[i].propertyFlags & properties) == properties) {
            return i;
        }
    }
    LOGE("Failed to find suitable memory type!");
    return 0;
}

bool MeshRenderer::createVertexBuffer() {
    VkDeviceSize bufferSize = sizeof(cubeVertices);

    VkBufferCreateInfo bufferInfo = {};
    bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufferInfo.size = bufferSize;
    bufferInfo.usage = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    if (vkCreateBuffer(device_, &bufferInfo, nullptr, &vertexBuffer_) != VK_SUCCESS) return false;

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
    vkMapMemory(device_, vertexMemory_, 0, bufferSize, 0, &data);
    memcpy(data, cubeVertices, bufferSize);
    vkUnmapMemory(device_, vertexMemory_);

    return true;
}

bool MeshRenderer::createIndexBuffer() {
    VkDeviceSize bufferSize = sizeof(cubeIndices);
    indexCount_ = sizeof(cubeIndices) / sizeof(cubeIndices[0]);

    VkBufferCreateInfo bufferInfo = {};
    bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufferInfo.size = bufferSize;
    bufferInfo.usage = VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    if (vkCreateBuffer(device_, &bufferInfo, nullptr, &indexBuffer_) != VK_SUCCESS) return false;

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
    vkMapMemory(device_, indexMemory_, 0, bufferSize, 0, &data);
    memcpy(data, cubeIndices, bufferSize);
    vkUnmapMemory(device_, indexMemory_);

    return true;
}

bool MeshRenderer::createUniformBuffer() {
    VkDeviceSize bufferSize = sizeof(MVPData);

    VkBufferCreateInfo bufferInfo = {};
    bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufferInfo.size = bufferSize;
    bufferInfo.usage = VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    if (vkCreateBuffer(device_, &bufferInfo, nullptr, &uniformBuffer_) != VK_SUCCESS) return false;

    VkMemoryRequirements memReqs;
    vkGetBufferMemoryRequirements(device_, uniformBuffer_, &memReqs);

    VkMemoryAllocateInfo allocInfo = {};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memReqs.size;
    allocInfo.memoryTypeIndex = findMemoryType(memReqs.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

    if (vkAllocateMemory(device_, &allocInfo, nullptr, &uniformMemory_) != VK_SUCCESS) return false;
    vkBindBufferMemory(device_, uniformBuffer_, uniformMemory_, 0);
    vkMapMemory(device_, uniformMemory_, 0, bufferSize, 0, &uniformMapped_);

    return true;
}

bool MeshRenderer::createDescriptorSetLayout() {
    VkDescriptorSetLayoutBinding uboBinding = {};
    uboBinding.binding = 0;
    uboBinding.descriptorCount = 1;
    uboBinding.descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    uboBinding.stageFlags = VK_SHADER_STAGE_VERTEX_BIT;

    VkDescriptorSetLayoutCreateInfo layoutInfo = {};
    layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    layoutInfo.bindingCount = 1;
    layoutInfo.pBindings = &uboBinding;

    return vkCreateDescriptorSetLayout(device_, &layoutInfo, nullptr, &descriptorSetLayout_) == VK_SUCCESS;
}

bool MeshRenderer::createDescriptorPool() {
    VkDescriptorPoolSize poolSize = {};
    poolSize.type = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    poolSize.descriptorCount = 1;

    VkDescriptorPoolCreateInfo poolInfo = {};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.poolSizeCount = 1;
    poolInfo.pPoolSizes = &poolSize;
    poolInfo.maxSets = 1;

    return vkCreateDescriptorPool(device_, &poolInfo, nullptr, &descriptorPool_) == VK_SUCCESS;
}

bool MeshRenderer::createDescriptorSet() {
    VkDescriptorSetAllocateInfo allocInfo = {};
    allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocInfo.descriptorPool = descriptorPool_;
    allocInfo.descriptorSetCount = 1;
    allocInfo.pSetLayouts = &descriptorSetLayout_;

    if (vkAllocateDescriptorSets(device_, &allocInfo, &descriptorSet_) != VK_SUCCESS) return false;

    VkDescriptorBufferInfo bufferInfo = {};
    bufferInfo.buffer = uniformBuffer_;
    bufferInfo.offset = 0;
    bufferInfo.range = sizeof(MVPData);

    VkWriteDescriptorSet descriptorWrite = {};
    descriptorWrite.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    descriptorWrite.dstSet = descriptorSet_;
    descriptorWrite.dstBinding = 0;
    descriptorWrite.descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    descriptorWrite.descriptorCount = 1;
    descriptorWrite.pBufferInfo = &bufferInfo;

    vkUpdateDescriptorSets(device_, 1, &descriptorWrite, 0, nullptr);
    return true;
}

bool MeshRenderer::createPipeline() {
    // 셰이더
    VkShaderModuleCreateInfo vertInfo = {};
    vertInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    vertInfo.codeSize = mesh_vert_spv_size;
    vertInfo.pCode = mesh_vert_spv;

    VkShaderModule vertModule;
    if (vkCreateShaderModule(device_, &vertInfo, nullptr, &vertModule) != VK_SUCCESS) return false;

    VkShaderModuleCreateInfo fragInfo = {};
    fragInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    fragInfo.codeSize = mesh_frag_spv_size;
    fragInfo.pCode = mesh_frag_spv;

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

    // 버텍스 인풋: position(vec3) + color(vec3)
    VkVertexInputBindingDescription bindingDesc = {};
    bindingDesc.binding = 0;
    bindingDesc.stride = sizeof(MeshVertex);
    bindingDesc.inputRate = VK_VERTEX_INPUT_RATE_VERTEX;

    VkVertexInputAttributeDescription attrDescs[2] = {};
    attrDescs[0].binding = 0;
    attrDescs[0].location = 0;
    attrDescs[0].format = VK_FORMAT_R32G32B32_SFLOAT;
    attrDescs[0].offset = offsetof(MeshVertex, pos);

    attrDescs[1].binding = 0;
    attrDescs[1].location = 1;
    attrDescs[1].format = VK_FORMAT_R32G32B32_SFLOAT;
    attrDescs[1].offset = offsetof(MeshVertex, color);

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

    // 깊이 테스트 ON (3D 오브젝트용) — 현재 렌더패스에 깊이 없으므로 비활성
    VkPipelineDepthStencilStateCreateInfo depthStencil = {};
    depthStencil.sType = VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO;
    depthStencil.depthTestEnable = VK_FALSE;
    depthStencil.depthWriteEnable = VK_FALSE;

    // 알파 블렌딩 (반투명 큐브)
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

    if (result != VK_SUCCESS) {
        LOGE("Failed to create mesh pipeline");
        return false;
    }

    LOGI("Mesh pipeline created");
    return true;
}

void MeshRenderer::destroy() {
    if (device_ == VK_NULL_HANDLE) return;

    vkDeviceWaitIdle(device_);

    if (pipeline_) { vkDestroyPipeline(device_, pipeline_, nullptr); pipeline_ = VK_NULL_HANDLE; }
    if (pipelineLayout_) { vkDestroyPipelineLayout(device_, pipelineLayout_, nullptr); pipelineLayout_ = VK_NULL_HANDLE; }
    if (descriptorPool_) { vkDestroyDescriptorPool(device_, descriptorPool_, nullptr); descriptorPool_ = VK_NULL_HANDLE; descriptorSet_ = VK_NULL_HANDLE; }
    if (descriptorSetLayout_) { vkDestroyDescriptorSetLayout(device_, descriptorSetLayout_, nullptr); descriptorSetLayout_ = VK_NULL_HANDLE; }
    if (uniformBuffer_) { vkUnmapMemory(device_, uniformMemory_); vkDestroyBuffer(device_, uniformBuffer_, nullptr); vkFreeMemory(device_, uniformMemory_, nullptr); uniformBuffer_ = VK_NULL_HANDLE; }
    if (indexBuffer_) { vkDestroyBuffer(device_, indexBuffer_, nullptr); vkFreeMemory(device_, indexMemory_, nullptr); indexBuffer_ = VK_NULL_HANDLE; }
    if (vertexBuffer_) { vkDestroyBuffer(device_, vertexBuffer_, nullptr); vkFreeMemory(device_, vertexMemory_, nullptr); vertexBuffer_ = VK_NULL_HANDLE; }

    device_ = VK_NULL_HANDLE;
    LOGI("MeshRenderer destroyed");
}

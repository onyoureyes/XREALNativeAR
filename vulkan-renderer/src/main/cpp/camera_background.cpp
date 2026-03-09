/**
 * CameraBackground -- MJPEG → Vulkan 텍스처 배경 렌더링.
 *
 * 프레임 흐름:
 *   카메라 스레드: updateFrame(jpegData) → stb_image 디코드 → pendingPixels_
 *   렌더 스레드:  draw(cmd) → 스테이징 버퍼 복사 → VkImage 업로드 → 풀스크린 드로우
 */

#include "camera_background.h"
#include "vulkan_context.h"

#include <android/log.h>
#include <cstring>
#include <algorithm>

#define STB_IMAGE_IMPLEMENTATION
#define STBI_ONLY_JPEG
#define STBI_NO_STDIO
#include "third_party/stb/stb_image.h"

#define TAG "CameraBackground"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ── Pre-compiled SPIR-V ──
#include "shaders/camera_bg_vert_spv.h"
#include "shaders/camera_bg_frag_spv.h"

CameraBackground::~CameraBackground() {
    destroy();
}

bool CameraBackground::init(VulkanContext& ctx) {
    ctx_ = &ctx;
    device_ = ctx.device();

    if (!createDescriptorSetLayout()) return false;
    if (!createPipeline()) return false;
    if (!createSampler()) return false;
    if (!createDescriptorPool()) return false;

    LOGI("CameraBackground initialized (texture will be created on first frame)");
    return true;
}

// ── 카메라 스레드: JPEG 디코드 ──

bool CameraBackground::updateFrame(const uint8_t* jpegData, int jpegSize) {
    int w, h, channels;
    // stb_image: JPEG → RGBA (4 channels)
    uint8_t* pixels = stbi_load_from_memory(jpegData, jpegSize, &w, &h, &channels, 4);
    if (!pixels) {
        LOGE("JPEG decode failed: %s", stbi_failure_reason());
        return false;
    }

    {
        std::lock_guard<std::mutex> lock(frameMutex_);
        // 기존 버퍼 크기 불일치 시 재할당
        if (pendingPixels_ && (pendingWidth_ != w || pendingHeight_ != h)) {
            free(pendingPixels_);
            pendingPixels_ = nullptr;
        }
        if (!pendingPixels_) {
            pendingPixels_ = (uint8_t*)malloc(w * h * 4);
        }
        memcpy(pendingPixels_, pixels, w * h * 4);
        pendingWidth_ = w;
        pendingHeight_ = h;
        frameReady_.store(true);
    }

    stbi_image_free(pixels);
    return true;
}

// ── 렌더 스레드: GPU 업로드 + 드로우 ──

void CameraBackground::draw(VkCommandBuffer cmd) {
    // 새 프레임 대기 중이면 업로드
    if (frameReady_.load()) {
        std::lock_guard<std::mutex> lock(frameMutex_);
        if (pendingPixels_) {
            int w = pendingWidth_;
            int h = pendingHeight_;
            VkDeviceSize imageSize = (VkDeviceSize)w * h * 4;

            // 텍스처 크기 변경 또는 최초 생성
            if (texWidth_ != w || texHeight_ != h) {
                // 기존 텍스처 정리
                if (textureImage_ != VK_NULL_HANDLE) {
                    vkDeviceWaitIdle(device_);
                    vkDestroyImageView(device_, textureView_, nullptr);
                    vkDestroyImage(device_, textureImage_, nullptr);
                    vkFreeMemory(device_, textureMemory_, nullptr);
                    textureImage_ = VK_NULL_HANDLE;
                    textureView_ = VK_NULL_HANDLE;
                    textureMemory_ = VK_NULL_HANDLE;
                }
                if (stagingBuffer_ != VK_NULL_HANDLE) {
                    vkDestroyBuffer(device_, stagingBuffer_, nullptr);
                    vkFreeMemory(device_, stagingMemory_, nullptr);
                    stagingBuffer_ = VK_NULL_HANDLE;
                    stagingMemory_ = VK_NULL_HANDLE;
                    stagingMapped_ = nullptr;
                }

                if (!createTexture(w, h)) {
                    LOGE("Failed to create texture %dx%d", w, h);
                    frameReady_.store(false);
                    return;
                }
                if (!createStagingBuffer(imageSize)) {
                    LOGE("Failed to create staging buffer");
                    frameReady_.store(false);
                    return;
                }
                if (!createDescriptorSet()) {
                    LOGE("Failed to create descriptor set");
                    frameReady_.store(false);
                    return;
                }

                texWidth_ = w;
                texHeight_ = h;
                textureInitialized_ = false;
                LOGI("Camera texture created: %dx%d (%zu bytes)", w, h, (size_t)imageSize);
            }

            // CPU → 스테이징 버퍼 복사
            memcpy(stagingMapped_, pendingPixels_, imageSize);

            // 이미지 레이아웃 전환: UNDEFINED → TRANSFER_DST
            transitionImageLayout(cmd, textureImage_,
                textureInitialized_ ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                                     : VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

            // 스테이징 버퍼 → VkImage 복사
            VkBufferImageCopy region = {};
            region.bufferOffset = 0;
            region.bufferRowLength = 0;
            region.bufferImageHeight = 0;
            region.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
            region.imageSubresource.mipLevel = 0;
            region.imageSubresource.baseArrayLayer = 0;
            region.imageSubresource.layerCount = 1;
            region.imageOffset = {0, 0, 0};
            region.imageExtent = {(uint32_t)w, (uint32_t)h, 1};

            vkCmdCopyBufferToImage(cmd, stagingBuffer_, textureImage_,
                                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);

            // TRANSFER_DST → SHADER_READ_ONLY
            transitionImageLayout(cmd, textureImage_,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

            textureInitialized_ = true;
            textureValid_.store(true);
            frameReady_.store(false);
        }
    }

    // 텍스처가 유효할 때만 드로우
    if (!textureValid_.load() || pipeline_ == VK_NULL_HANDLE || descriptorSet_ == VK_NULL_HANDLE) {
        return;
    }

    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline_);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS,
                             pipelineLayout_, 0, 1, &descriptorSet_, 0, nullptr);
    vkCmdDraw(cmd, 3, 1, 0, 0);  // 풀스크린 삼각형
}

// ── Vulkan 리소스 생성 ──

bool CameraBackground::createTexture(int width, int height) {
    // VkImage 생성
    VkImageCreateInfo imageInfo = {};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.extent = {(uint32_t)width, (uint32_t)height, 1};
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    imageInfo.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;

    if (vkCreateImage(device_, &imageInfo, nullptr, &textureImage_) != VK_SUCCESS) {
        LOGE("Failed to create texture image");
        return false;
    }

    // 메모리 할당
    VkMemoryRequirements memReqs;
    vkGetImageMemoryRequirements(device_, textureImage_, &memReqs);

    VkMemoryAllocateInfo allocInfo = {};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memReqs.size;
    allocInfo.memoryTypeIndex = findMemoryType(memReqs.memoryTypeBits,
        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);

    if (vkAllocateMemory(device_, &allocInfo, nullptr, &textureMemory_) != VK_SUCCESS) {
        LOGE("Failed to allocate texture memory");
        return false;
    }
    vkBindImageMemory(device_, textureImage_, textureMemory_, 0);

    // ImageView
    VkImageViewCreateInfo viewInfo = {};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = textureImage_;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    viewInfo.subresourceRange.levelCount = 1;
    viewInfo.subresourceRange.layerCount = 1;

    if (vkCreateImageView(device_, &viewInfo, nullptr, &textureView_) != VK_SUCCESS) {
        LOGE("Failed to create texture image view");
        return false;
    }

    return true;
}

bool CameraBackground::createStagingBuffer(VkDeviceSize size) {
    VkBufferCreateInfo bufferInfo = {};
    bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufferInfo.size = size;
    bufferInfo.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;

    if (vkCreateBuffer(device_, &bufferInfo, nullptr, &stagingBuffer_) != VK_SUCCESS) {
        LOGE("Failed to create staging buffer");
        return false;
    }

    VkMemoryRequirements memReqs;
    vkGetBufferMemoryRequirements(device_, stagingBuffer_, &memReqs);

    VkMemoryAllocateInfo allocInfo = {};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memReqs.size;
    allocInfo.memoryTypeIndex = findMemoryType(memReqs.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

    if (vkAllocateMemory(device_, &allocInfo, nullptr, &stagingMemory_) != VK_SUCCESS) {
        LOGE("Failed to allocate staging memory");
        return false;
    }

    vkBindBufferMemory(device_, stagingBuffer_, stagingMemory_, 0);
    vkMapMemory(device_, stagingMemory_, 0, size, 0, &stagingMapped_);
    stagingSize_ = size;

    return true;
}

bool CameraBackground::createSampler() {
    VkSamplerCreateInfo samplerInfo = {};
    samplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    samplerInfo.magFilter = VK_FILTER_LINEAR;
    samplerInfo.minFilter = VK_FILTER_LINEAR;
    samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.anisotropyEnable = VK_FALSE;
    samplerInfo.maxAnisotropy = 1.0f;
    samplerInfo.borderColor = VK_BORDER_COLOR_INT_OPAQUE_BLACK;
    samplerInfo.unnormalizedCoordinates = VK_FALSE;
    samplerInfo.compareEnable = VK_FALSE;
    samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_LINEAR;

    if (vkCreateSampler(device_, &samplerInfo, nullptr, &sampler_) != VK_SUCCESS) {
        LOGE("Failed to create sampler");
        return false;
    }
    return true;
}

bool CameraBackground::createDescriptorSetLayout() {
    VkDescriptorSetLayoutBinding samplerBinding = {};
    samplerBinding.binding = 0;
    samplerBinding.descriptorCount = 1;
    samplerBinding.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    samplerBinding.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;

    VkDescriptorSetLayoutCreateInfo layoutInfo = {};
    layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    layoutInfo.bindingCount = 1;
    layoutInfo.pBindings = &samplerBinding;

    if (vkCreateDescriptorSetLayout(device_, &layoutInfo, nullptr, &descriptorSetLayout_) != VK_SUCCESS) {
        LOGE("Failed to create descriptor set layout");
        return false;
    }
    return true;
}

bool CameraBackground::createDescriptorPool() {
    VkDescriptorPoolSize poolSize = {};
    poolSize.type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    poolSize.descriptorCount = 1;

    VkDescriptorPoolCreateInfo poolInfo = {};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.flags = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;
    poolInfo.poolSizeCount = 1;
    poolInfo.pPoolSizes = &poolSize;
    poolInfo.maxSets = 1;

    if (vkCreateDescriptorPool(device_, &poolInfo, nullptr, &descriptorPool_) != VK_SUCCESS) {
        LOGE("Failed to create descriptor pool");
        return false;
    }
    return true;
}

bool CameraBackground::createDescriptorSet() {
    // 기존 descriptor set 해제
    if (descriptorSet_ != VK_NULL_HANDLE) {
        vkFreeDescriptorSets(device_, descriptorPool_, 1, &descriptorSet_);
        descriptorSet_ = VK_NULL_HANDLE;
    }

    VkDescriptorSetAllocateInfo allocInfo = {};
    allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocInfo.descriptorPool = descriptorPool_;
    allocInfo.descriptorSetCount = 1;
    allocInfo.pSetLayouts = &descriptorSetLayout_;

    if (vkAllocateDescriptorSets(device_, &allocInfo, &descriptorSet_) != VK_SUCCESS) {
        LOGE("Failed to allocate descriptor set");
        return false;
    }

    // Descriptor 업데이트: sampler + imageView
    VkDescriptorImageInfo imageInfo = {};
    imageInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    imageInfo.imageView = textureView_;
    imageInfo.sampler = sampler_;

    VkWriteDescriptorSet descriptorWrite = {};
    descriptorWrite.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    descriptorWrite.dstSet = descriptorSet_;
    descriptorWrite.dstBinding = 0;
    descriptorWrite.dstArrayElement = 0;
    descriptorWrite.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    descriptorWrite.descriptorCount = 1;
    descriptorWrite.pImageInfo = &imageInfo;

    vkUpdateDescriptorSets(device_, 1, &descriptorWrite, 0, nullptr);
    return true;
}

bool CameraBackground::createPipeline() {
    // 셰이더 모듈
    VkShaderModuleCreateInfo vertInfo = {};
    vertInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    vertInfo.codeSize = camera_bg_vert_spv_size;
    vertInfo.pCode = camera_bg_vert_spv;

    VkShaderModule vertModule;
    if (vkCreateShaderModule(device_, &vertInfo, nullptr, &vertModule) != VK_SUCCESS) {
        LOGE("Failed to create camera_bg vert shader module");
        return false;
    }

    VkShaderModuleCreateInfo fragInfo = {};
    fragInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    fragInfo.codeSize = camera_bg_frag_spv_size;
    fragInfo.pCode = camera_bg_frag_spv;

    VkShaderModule fragModule;
    if (vkCreateShaderModule(device_, &fragInfo, nullptr, &fragModule) != VK_SUCCESS) {
        LOGE("Failed to create camera_bg frag shader module");
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

    // 버텍스 인풋 (없음 — 풀스크린 삼각형은 셰이더 내부 생성)
    VkPipelineVertexInputStateCreateInfo vertexInput = {};
    vertexInput.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;

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
    rasterizer.cullMode = VK_CULL_MODE_NONE;
    rasterizer.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;

    VkPipelineMultisampleStateCreateInfo multisampling = {};
    multisampling.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
    multisampling.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

    // 깊이 쓰기 OFF (배경이므로)
    VkPipelineDepthStencilStateCreateInfo depthStencil = {};
    depthStencil.sType = VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO;
    depthStencil.depthTestEnable = VK_FALSE;
    depthStencil.depthWriteEnable = VK_FALSE;

    VkPipelineColorBlendAttachmentState colorAttachment = {};
    colorAttachment.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
                                     VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
    colorAttachment.blendEnable = VK_FALSE;

    VkPipelineColorBlendStateCreateInfo colorBlending = {};
    colorBlending.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
    colorBlending.attachmentCount = 1;
    colorBlending.pAttachments = &colorAttachment;

    VkDynamicState dynamicStates[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo dynamicState = {};
    dynamicState.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
    dynamicState.dynamicStateCount = 2;
    dynamicState.pDynamicStates = dynamicStates;

    // 파이프라인 레이아웃 (텍스처 sampler descriptor)
    VkPipelineLayoutCreateInfo layoutInfo = {};
    layoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    layoutInfo.setLayoutCount = 1;
    layoutInfo.pSetLayouts = &descriptorSetLayout_;

    if (vkCreatePipelineLayout(device_, &layoutInfo, nullptr, &pipelineLayout_) != VK_SUCCESS) {
        LOGE("Failed to create camera_bg pipeline layout");
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

    if (vkCreateGraphicsPipelines(device_, VK_NULL_HANDLE, 1, &pipelineInfo,
                                   nullptr, &pipeline_) != VK_SUCCESS) {
        LOGE("Failed to create camera_bg graphics pipeline");
        vkDestroyShaderModule(device_, vertModule, nullptr);
        vkDestroyShaderModule(device_, fragModule, nullptr);
        return false;
    }

    vkDestroyShaderModule(device_, vertModule, nullptr);
    vkDestroyShaderModule(device_, fragModule, nullptr);

    LOGI("Camera background pipeline created");
    return true;
}

void CameraBackground::transitionImageLayout(VkCommandBuffer cmd, VkImage image,
                                               VkImageLayout oldLayout, VkImageLayout newLayout) {
    VkImageMemoryBarrier barrier = {};
    barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrier.oldLayout = oldLayout;
    barrier.newLayout = newLayout;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = image;
    barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    barrier.subresourceRange.baseMipLevel = 0;
    barrier.subresourceRange.levelCount = 1;
    barrier.subresourceRange.baseArrayLayer = 0;
    barrier.subresourceRange.layerCount = 1;

    VkPipelineStageFlags srcStage, dstStage;

    if (oldLayout == VK_IMAGE_LAYOUT_UNDEFINED) {
        barrier.srcAccessMask = 0;
        srcStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
    } else if (oldLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
        barrier.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
        srcStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
    } else {
        barrier.srcAccessMask = 0;
        srcStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
    }

    if (newLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
        barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        dstStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
    } else if (newLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
        barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        dstStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
    } else {
        barrier.dstAccessMask = 0;
        dstStage = VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT;
    }

    vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0,
                          0, nullptr, 0, nullptr, 1, &barrier);
}

uint32_t CameraBackground::findMemoryType(uint32_t typeFilter, VkMemoryPropertyFlags properties) {
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

void CameraBackground::destroy() {
    if (device_ == VK_NULL_HANDLE) return;

    vkDeviceWaitIdle(device_);

    if (pipeline_ != VK_NULL_HANDLE) {
        vkDestroyPipeline(device_, pipeline_, nullptr);
        pipeline_ = VK_NULL_HANDLE;
    }
    if (pipelineLayout_ != VK_NULL_HANDLE) {
        vkDestroyPipelineLayout(device_, pipelineLayout_, nullptr);
        pipelineLayout_ = VK_NULL_HANDLE;
    }
    if (descriptorPool_ != VK_NULL_HANDLE) {
        vkDestroyDescriptorPool(device_, descriptorPool_, nullptr);
        descriptorPool_ = VK_NULL_HANDLE;
        descriptorSet_ = VK_NULL_HANDLE;
    }
    if (descriptorSetLayout_ != VK_NULL_HANDLE) {
        vkDestroyDescriptorSetLayout(device_, descriptorSetLayout_, nullptr);
        descriptorSetLayout_ = VK_NULL_HANDLE;
    }
    if (sampler_ != VK_NULL_HANDLE) {
        vkDestroySampler(device_, sampler_, nullptr);
        sampler_ = VK_NULL_HANDLE;
    }
    if (textureView_ != VK_NULL_HANDLE) {
        vkDestroyImageView(device_, textureView_, nullptr);
        textureView_ = VK_NULL_HANDLE;
    }
    if (textureImage_ != VK_NULL_HANDLE) {
        vkDestroyImage(device_, textureImage_, nullptr);
        textureImage_ = VK_NULL_HANDLE;
    }
    if (textureMemory_ != VK_NULL_HANDLE) {
        vkFreeMemory(device_, textureMemory_, nullptr);
        textureMemory_ = VK_NULL_HANDLE;
    }
    if (stagingBuffer_ != VK_NULL_HANDLE) {
        vkUnmapMemory(device_, stagingMemory_);
        vkDestroyBuffer(device_, stagingBuffer_, nullptr);
        vkFreeMemory(device_, stagingMemory_, nullptr);
        stagingBuffer_ = VK_NULL_HANDLE;
        stagingMemory_ = VK_NULL_HANDLE;
        stagingMapped_ = nullptr;
    }

    {
        std::lock_guard<std::mutex> lock(frameMutex_);
        if (pendingPixels_) {
            free(pendingPixels_);
            pendingPixels_ = nullptr;
        }
    }

    textureValid_.store(false);
    frameReady_.store(false);
    device_ = VK_NULL_HANDLE;

    LOGI("CameraBackground destroyed");
}

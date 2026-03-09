package com.xreal.nativear.edge

import android.util.Log
import com.xreal.nativear.ai.AIMessage
import com.xreal.nativear.core.ErrorReporter
import com.xreal.nativear.core.ErrorSeverity
import com.xreal.nativear.ai.AIResponse
import com.xreal.nativear.ai.AIToolDefinition
import com.xreal.nativear.ai.IAIProvider
import com.xreal.nativear.ai.ProviderId
import com.xreal.nativear.ai.TokenUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * EdgeLLMProvider — llama.cpp CPU 모드 기반 IAIProvider 구현체.
 *
 * LiteRT-LM은 Galaxy Fold 4 (Snapdragon 8 Gen 1)에서 NPU 미지원 → llama.cpp CPU 모드로 교체.
 * JNI 브릿지를 통해 llama.cpp 네이티브 추론 직접 호출.
 *
 * ## 3-Tier 사용
 * - ROUTER_270M: EdgeContextJudge ACT/SKIP 분류 (maxTokens=20)
 * - AGENT_1B: 일반 대화/작업 처리 (maxTokens=512)
 * - EMERGENCY_E2B: 전체 API 불가 시 (maxTokens=1024)
 */
class EdgeLLMProvider(
    private val tier: EdgeModelTier,
    private val edgeModelManager: EdgeModelManager
) : IAIProvider {

    companion object {
        private const val TAG = "EdgeLLMProvider"

        // 전역 Mutex — tier에 관계없이 llama.cpp 추론 완전 직렬화
        // 각 tier가 별도 모델 핸들을 사용하지만, llama.cpp 내부 메모리 풀 공유 가능
        // → 안전을 위해 전역 직렬화 유지
        val globalInferenceMutex = Mutex()
    }

    override val providerId: ProviderId = when (tier) {
        EdgeModelTier.ROUTER_270M -> ProviderId.EDGE_ROUTER
        EdgeModelTier.AGENT_1B -> ProviderId.EDGE_AGENT
        EdgeModelTier.EMERGENCY_E2B -> ProviderId.EDGE_EMERGENCY
    }

    override val isAvailable: Boolean
        get() = edgeModelManager.isReady(tier)

    override fun updateApiKey(apiKey: String) {
        // 엣지 LLM은 API 키 불필요
    }

    override fun getModelName(): String = when (tier) {
        EdgeModelTier.ROUTER_270M -> "Qwen3-0.6B-Q8 (llama.cpp CPU)"
        EdgeModelTier.AGENT_1B -> "Qwen3-1.7B-Q4 (llama.cpp CPU)"
        EdgeModelTier.EMERGENCY_E2B -> "Qwen3-1.7B-Q8 (llama.cpp CPU)"
    }

    override suspend fun sendMessage(
        messages: List<AIMessage>,
        systemPrompt: String?,
        tools: List<AIToolDefinition>,
        temperature: Float?,
        maxTokens: Int?
    ): AIResponse = withContext(Dispatchers.Default) {
        val startMs = System.currentTimeMillis()

        // E2B 유휴 타이머 리셋
        if (tier == EdgeModelTier.EMERGENCY_E2B) {
            edgeModelManager.resetE2bIdleTimer()
        }

        // 모델 핸들 획득 (캐시 or 로딩)
        val handle = edgeModelManager.getOrLoad(tier)
        if (handle == 0L) {
            Log.w(TAG, "${tier.name} 준비 안 됨 — fallback 응답 반환")
            return@withContext AIResponse(
                text = "[엣지 모델 준비 중... 잠시 후 다시 시도해주세요]",
                providerId = providerId,
                latencyMs = System.currentTimeMillis() - startMs
            )
        }

        // Qwen3 ChatML 템플릿 빌드
        val prompt = buildGemmaChatPrompt(systemPrompt, messages)
        val tokenLimit = maxTokens ?: when (tier) {
            EdgeModelTier.ROUTER_270M -> 20
            EdgeModelTier.AGENT_1B -> 512
            EdgeModelTier.EMERGENCY_E2B -> 1024
        }
        val temp = temperature ?: 0.7f

        // 전역 Mutex: 모든 tier 간 직렬화
        globalInferenceMutex.withLock {
            Log.d(TAG, "${tier.name} GlobalMutex 획득 — 추론 시작 (prompt=${prompt.length}자)")
            try {
                val responseText = withContext(Dispatchers.IO) {
                    LlamaCppBridge.generate(
                        handle = handle,
                        prompt = prompt,
                        maxTokens = tokenLimit,
                        temperature = temp,
                        topP = 0.9f
                    )
                }

                val latency = System.currentTimeMillis() - startMs
                val text = responseText?.trim() ?: ""

                if (text.isNotEmpty()) {
                    Log.i(TAG, "${tier.name} 응답 완료: ${latency}ms — ${text.take(60)}")
                } else {
                    Log.e(TAG, "${tier.name} 응답 없음: ${latency}ms")
                }

                val approxTokens = if (text.isNotEmpty()) {
                    text.split(" ", "\n").count { it.isNotEmpty() }.coerceAtLeast(1)
                } else 0

                AIResponse(
                    text = text.ifEmpty { "[엣지 AI 응답 생성 실패 — 잠시 후 다시 시도해주세요]" },
                    providerId = providerId,
                    latencyMs = latency,
                    usage = TokenUsage(
                        promptTokens = (prompt.length / 4).coerceAtLeast(1),
                        completionTokens = approxTokens,
                        totalTokens = (prompt.length / 4) + approxTokens
                    )
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                ErrorReporter.report(TAG, "엣지 LLM 추론 실패: ${tier.name}", e, ErrorSeverity.WARNING)
                AIResponse(
                    text = "[엣지 AI 오류: ${e.message}]",
                    providerId = providerId,
                    latencyMs = System.currentTimeMillis() - startMs
                )
            }
        }
    }

    // =========================================================================
    // E2B 비전 추론 (텍스트 전용 폴백)
    // =========================================================================

    /**
     * 비전 추론 — llama.cpp는 텍스트 전용이므로 OCR 힌트를 텍스트로 포함.
     * Qwen3는 텍스트 전용 → 비전 미지원.
     */
    suspend fun sendVisionMessage(
        bitmap: android.graphics.Bitmap,
        textPrompt: String,
        systemPrompt: String? = null,
        ocrHint: String? = null
    ): AIResponse {
        // llama.cpp CPU 모드: 비전 미지원 → 텍스트 전용 폴백
        val textContent = buildString {
            if (!ocrHint.isNullOrBlank()) append("화면 텍스트: $ocrHint\n")
            append(textPrompt)
        }
        return sendMessage(
            messages = listOf(AIMessage(role = "user", content = textContent)),
            systemPrompt = systemPrompt
        )
    }

    // =========================================================================
    // Qwen3 ChatML 템플릿 빌드
    // =========================================================================

    /**
     * Qwen3 ChatML 채팅 템플릿 빌드.
     *
     * 라우터(ROUTER_270M)는 /no_think 모드 (빠른 분류),
     * 에이전트/비상(AGENT_1B, E2B)은 기본 모드 (필요 시 thinking).
     *
     * ChatML 형식:
     * <|im_start|>system\n...<|im_end|>\n
     * <|im_start|>user\n...<|im_end|>\n
     * <|im_start|>assistant\n
     */
    private fun buildGemmaChatPrompt(systemPrompt: String?, messages: List<AIMessage>): String {
        val sb = StringBuilder()

        // system prompt
        if (!systemPrompt.isNullOrBlank()) {
            val sysContent = if (tier == EdgeModelTier.ROUTER_270M) {
                "$systemPrompt\n/no_think"  // 라우터: thinking 비활성 (속도 우선)
            } else {
                systemPrompt
            }
            sb.append("<|im_start|>system\n$sysContent<|im_end|>\n")
        } else if (tier == EdgeModelTier.ROUTER_270M) {
            sb.append("<|im_start|>system\n/no_think<|im_end|>\n")
        }

        // 대화 히스토리
        for (msg in messages) {
            when (msg.role) {
                "user" -> sb.append("<|im_start|>user\n${msg.content}<|im_end|>\n")
                "assistant" -> sb.append("<|im_start|>assistant\n${msg.content}<|im_end|>\n")
                "system" -> sb.append("<|im_start|>system\n${msg.content}<|im_end|>\n")
            }
        }

        // 어시스턴트 응답 시작
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }
}

package com.xreal.nativear.ai

import okhttp3.OkHttpClient

/**
 * 로컬 LLM 서버 프로바이더 (llama.cpp / KoboldCpp, Tailscale 경유)
 * OpenAI-호환 API 사용 → OpenAICompatibleProvider 재사용
 *
 * 서버:
 * - 데스크톱 PC (100.101.127.124:8080) — gemma-3-12b Q4_K_M (~39 tok/s)
 * - 스팀덱 (100.98.177.14:8080) — gemma-3-4b Q4_K_M (~20 tok/s)
 *
 * 비용: $0 (무제한), Tailscale VPN 내에서만 접근 가능
 */
class LocalServerProvider(
    config: ProviderConfig,
    httpClient: OkHttpClient
) : OpenAICompatibleProvider(config, httpClient, DEFAULT_URL) {

    override val providerId = config.providerId

    companion object {
        const val DEFAULT_URL = "http://100.101.127.124:8080/v1/chat/completions"
        const val STEAMDECK_URL = "http://100.98.177.14:8080/v1/chat/completions"
    }
}

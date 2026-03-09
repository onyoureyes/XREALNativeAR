package com.xreal.nativear.ai

import okhttp3.OkHttpClient

class GrokProvider(
    config: ProviderConfig,
    httpClient: OkHttpClient
) : OpenAICompatibleProvider(config, httpClient, BASE_URL) {

    override val providerId = ProviderId.GROK

    companion object {
        private const val BASE_URL = "https://api.x.ai/v1/chat/completions"
    }
}

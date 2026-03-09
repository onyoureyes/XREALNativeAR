package com.xreal.nativear.ai

import okhttp3.OkHttpClient

class OpenAIProvider(
    config: ProviderConfig,
    httpClient: OkHttpClient
) : OpenAICompatibleProvider(config, httpClient, BASE_URL) {

    override val providerId = ProviderId.OPENAI

    companion object {
        private const val BASE_URL = "https://api.openai.com/v1/chat/completions"
    }
}

/**
 * llama_bridge.cpp — llama.cpp JNI 브릿지.
 *
 * Galaxy Fold 4 (Snapdragon 8 Gen 1) CPU 모드 전용.
 * LiteRT-LM 대체용 — GGUF 모델 로딩 + 텍스트 생성.
 *
 * 빌드 전 필요:
 *   1. scripts/build_llama_android.sh 실행 → 정적 라이브러리 + 헤더 복사
 *   2. ./gradlew :app:assembleDebug
 */

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cstring>

#include "llama.h"

#define LOG_TAG "LlamaBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ── 모델 핸들 (model + context + vocab 쌍) ──

struct LlamaHandle {
    llama_model         *model;
    llama_context       *ctx;
    const llama_vocab   *vocab;
    int                  n_ctx;
};

// ── JNI 함수 ──

extern "C" {

// backend 초기화 (앱 시작 시 1회)
JNIEXPORT void JNICALL
Java_com_xreal_nativear_edge_LlamaCppBridge_nativeInit(JNIEnv *env, jclass clazz) {
    llama_backend_init();
    LOGI("llama.cpp backend initialized");
}

// backend 해제 (앱 종료 시)
JNIEXPORT void JNICALL
Java_com_xreal_nativear_edge_LlamaCppBridge_nativeCleanup(JNIEnv *env, jclass clazz) {
    llama_backend_free();
    LOGI("llama.cpp backend freed");
}

/**
 * GGUF 모델 로딩.
 * @param modelPath GGUF 파일 절대 경로
 * @param nCtx      컨텍스트 크기 (256~2048)
 * @param nThreads  CPU 스레드 수 (4~8)
 * @return 핸들 (jlong, 0=실패)
 */
JNIEXPORT jlong JNICALL
Java_com_xreal_nativear_edge_LlamaCppBridge_nativeLoadModel(
        JNIEnv *env, jclass clazz,
        jstring modelPath, jint nCtx, jint nThreads) {

    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    LOGI("Loading GGUF model: %s (ctx=%d, threads=%d)", path, nCtx, nThreads);

    // 모델 파라미터
    auto model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;  // CPU only — Galaxy Fold 4 NPU 미지원

    llama_model *model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!model) {
        LOGE("Failed to load model");
        return 0;
    }

    // vocab 획득 (새 API: llama_model_get_vocab)
    const llama_vocab *vocab = llama_model_get_vocab(model);

    // 컨텍스트 파라미터
    auto ctx_params = llama_context_default_params();
    ctx_params.n_ctx    = nCtx;
    ctx_params.n_threads = nThreads;
    ctx_params.n_threads_batch = nThreads;
    ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;  // CPU 모드 — flash attention 비활성

    llama_context *ctx = llama_init_from_model(model, ctx_params);
    if (!ctx) {
        LOGE("Failed to create context");
        llama_model_free(model);
        return 0;
    }

    auto *handle = new LlamaHandle{model, ctx, vocab, nCtx};
    LOGI("Model loaded successfully (ctx=%d, vocab=%d)", nCtx, llama_vocab_n_tokens(vocab));
    return reinterpret_cast<jlong>(handle);
}

/**
 * 텍스트 생성 (동기).
 * @param handle      nativeLoadModel() 반환값
 * @param prompt      프롬프트 (Gemma chat template 포함)
 * @param maxTokens   최대 생성 토큰 수
 * @param temperature 온도 (0.0=greedy, 0.7=default)
 * @param topP        nucleus sampling (0.9 default)
 * @return 생성된 텍스트 (null=오류)
 */
JNIEXPORT jstring JNICALL
Java_com_xreal_nativear_edge_LlamaCppBridge_nativeGenerate(
        JNIEnv *env, jclass clazz,
        jlong handle, jstring prompt,
        jint maxTokens, jfloat temperature, jfloat topP) {

    auto *h = reinterpret_cast<LlamaHandle *>(handle);
    if (!h || !h->model || !h->ctx || !h->vocab) {
        LOGE("Invalid handle");
        return nullptr;
    }

    const char *promptStr = env->GetStringUTFChars(prompt, nullptr);
    int promptLen = strlen(promptStr);

    // KV 캐시 초기화 (매 호출마다 새 대화)
    llama_memory_clear(llama_get_memory(h->ctx), true);

    // 토큰화 (새 API: vocab 기반)
    int n_prompt_max = promptLen + 128;
    std::vector<llama_token> tokens(n_prompt_max);
    int n_tokens = llama_tokenize(
        h->vocab, promptStr, promptLen,
        tokens.data(), n_prompt_max,
        true,   // add_special (BOS)
        true    // parse_special
    );
    env->ReleaseStringUTFChars(prompt, promptStr);

    if (n_tokens < 0) {
        LOGE("Tokenization failed (n_tokens=%d)", n_tokens);
        return nullptr;
    }
    tokens.resize(n_tokens);

    // 컨텍스트 초과 체크
    if (n_tokens + maxTokens > h->n_ctx) {
        LOGW("Prompt too long (%d tokens + %d max > %d ctx), truncating", n_tokens, maxTokens, h->n_ctx);
        int keep = h->n_ctx - maxTokens - 16;
        if (keep < 4) keep = 4;
        tokens.resize(keep);
        n_tokens = keep;
    }

    // 프롬프트 디코딩
    llama_batch batch = llama_batch_get_one(tokens.data(), n_tokens);
    if (llama_decode(h->ctx, batch) != 0) {
        LOGE("Prompt decode failed");
        return nullptr;
    }

    // 샘플러 초기화
    auto sparams = llama_sampler_chain_default_params();
    llama_sampler *sampler = llama_sampler_chain_init(sparams);

    if (temperature < 0.01f) {
        // Greedy
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    } else {
        // Top-p + temperature
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(topP, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(0));
    }

    // 생성 루프
    std::string result;
    result.reserve(maxTokens * 8);  // 평균 토큰 길이 ~4-8 bytes
    char piece_buf[256];

    int n_decoded = 0;
    for (int i = 0; i < maxTokens; i++) {
        llama_token token = llama_sampler_sample(sampler, h->ctx, -1);

        // EOS 체크 (새 API: vocab 기반)
        if (llama_vocab_is_eog(h->vocab, token)) {
            break;
        }

        // 토큰 → 텍스트 (새 API: vocab 기반)
        int n_piece = llama_token_to_piece(h->vocab, token, piece_buf, sizeof(piece_buf), 0, true);
        if (n_piece > 0) {
            result.append(piece_buf, n_piece);
        }

        // 다음 토큰 디코딩
        llama_batch next = llama_batch_get_one(&token, 1);
        if (llama_decode(h->ctx, next) != 0) {
            LOGE("Decode failed at token %d", i);
            break;
        }
        n_decoded++;
    }

    llama_sampler_free(sampler);
    LOGI("Generated %d tokens (%zu bytes)", n_decoded, result.size());

    return env->NewStringUTF(result.c_str());
}

/**
 * 모델 언로드.
 */
JNIEXPORT void JNICALL
Java_com_xreal_nativear_edge_LlamaCppBridge_nativeUnloadModel(
        JNIEnv *env, jclass clazz, jlong handle) {

    auto *h = reinterpret_cast<LlamaHandle *>(handle);
    if (!h) return;

    if (h->ctx) {
        llama_free(h->ctx);
        LOGI("Context freed");
    }
    if (h->model) {
        llama_model_free(h->model);
        LOGI("Model freed");
    }
    delete h;
}

/**
 * 모델 vocab 크기 (로딩 확인용).
 */
JNIEXPORT jint JNICALL
Java_com_xreal_nativear_edge_LlamaCppBridge_nativeGetVocabSize(
        JNIEnv *env, jclass clazz, jlong handle) {
    auto *h = reinterpret_cast<LlamaHandle *>(handle);
    if (!h || !h->vocab) return 0;
    return llama_vocab_n_tokens(h->vocab);
}

} // extern "C"

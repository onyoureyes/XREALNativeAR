package com.xreal.nativear.edge

/**
 * EdgeModelTier — 엣지 LLM 3-티어 모델 정의 (Qwen3 시리즈).
 *
 * ## 3-Tier 역할 분담
 * | 티어 | 모델 | 역할 | 로딩 |
 * |------|------|------|------|
 * | ROUTER_270M | Qwen3 0.6B Q8 | 쿼리 복잡도 분류 → 서버/엣지 분기 | 항상 |
 * | AGENT_1B | Qwen3 1.7B Q4 | 단순 작업 처리 (온라인 서브에이전트 + 오프라인 1차) | 항상 |
 * | EMERGENCY_E2B | Qwen3 1.7B Q8 | 전체 API 불가 시 전면 대체 (고품질) | 지연 |
 *
 * ## Qwen3 장점 (Gemma 3 대비)
 * - 인증 불필요 (HuggingFace 공개 다운로드)
 * - 한국어 지원 우수
 * - thinking/non-thinking 모드 전환 (/think, /no_think)
 * - 0.6B급 최고 벤치마크 성능
 *
 * ## 런타임: llama.cpp CPU 모드
 * - `.gguf` = llama.cpp 전용 모델 형식
 * - CPU 전용 (n_gpu_layers = 0)
 *
 * ## 모델 파일 위치 (EdgeModelManager 탐색 순서)
 * 1. `filesDir/edge_models/{fileName}` — WorkManager 자동 다운로드 (배포)
 * 2. `/data/local/tmp/edge_models/{fileName}` — adb push (개발, SELinux 허용 시)
 * 3. `/sdcard/edge_models/{fileName}` — 외부 저장소 (대부분 접근 가능)
 * 4. `getExternalFilesDir("edge_models")/{fileName}` — 앱 전용 외부 저장소
 *    `/sdcard/Android/data/com.xreal.nativear/files/edge_models/`
 *
 * ## 개발 단계 모델 설치 (adb push 권장 경로)
 * ```bash
 * # 방법 1: /sdcard/ (권장 — 앱에서 항상 접근 가능)
 * adb shell mkdir -p /sdcard/edge_models/
 * adb push Qwen3-0.6B-Q8_0.gguf /sdcard/edge_models/
 * adb push Qwen3-1.7B-Q4_K_M.gguf /sdcard/edge_models/
 *
 * # 방법 2: /data/local/tmp/ (기존 — SELinux가 차단할 수 있음)
 * adb shell mkdir -p /data/local/tmp/edge_models/
 * adb push Qwen3-0.6B-Q8_0.gguf /data/local/tmp/edge_models/
 * adb push Qwen3-1.7B-Q4_K_M.gguf /data/local/tmp/edge_models/
 * ```
 */
enum class EdgeModelTier(
    /** 모델 파일명 (filesDir/edge_models/ 또는 /data/local/tmp/edge_models/ 아래) */
    val modelFileName: String,
    /** Hugging Face 저장소 경로 (다운로드에 사용) */
    val huggingFaceRepo: String,
    /** 모델 파일 크기 (MB) */
    val estimatedSizeMb: Int,
    /** 런타임 RAM 사용량 (MB) */
    val estimatedRamMb: Int,
    /** 앱 시작 시 자동 로딩 여부 */
    val alwaysLoaded: Boolean,
    /** 추론 백엔드. llama.cpp CPU 전용. */
    val backend: String,
    /** llama.cpp 컨텍스트 크기 */
    val contextSize: Int,
    /** llama.cpp CPU 스레드 수 */
    val nThreads: Int
) {
    ROUTER_270M(
        modelFileName = "Qwen3-0.6B-Q8_0.gguf",
        huggingFaceRepo = "Qwen/Qwen3-0.6B-GGUF",
        estimatedSizeMb = 660,
        estimatedRamMb = 800,
        alwaysLoaded = true,
        backend = "CPU",
        contextSize = 512,   // 라우터 전용 — Qwen3 0.6B는 여유 있으므로 256→512
        nThreads = 4
    ),
    AGENT_1B(
        modelFileName = "Qwen3-1.7B-Q4_K_M.gguf",
        huggingFaceRepo = "bartowski/Qwen_Qwen3-1.7B-GGUF",
        estimatedSizeMb = 1170,
        estimatedRamMb = 1400,
        alwaysLoaded = true,
        backend = "CPU",
        contextSize = 2048,  // 일반 대화/작업 처리 — Qwen3는 32K 지원하지만 메모리 제한
        nThreads = 4
    ),
    EMERGENCY_E2B(
        modelFileName = "Qwen3-1.7B-Q8_0.gguf",
        huggingFaceRepo = "Qwen/Qwen3-1.7B-GGUF",  // 공식 레포 (Q8_0만 제공)
        estimatedSizeMb = 1890,
        estimatedRamMb = 2200,
        alwaysLoaded = false,  // 지연 로딩 — 비상 시에만
        backend = "CPU",
        contextSize = 4096,  // 비상 전면 대체 — 충분한 컨텍스트
        nThreads = 6
    );

    /** 모델 파일 HuggingFace 다운로드 URL */
    val downloadUrl: String
        get() = "https://huggingface.co/$huggingFaceRepo/resolve/main/$remoteFileName"

    /** HuggingFace 원격 파일명 (로컬 파일명과 다를 수 있음) */
    val remoteFileName: String
        get() = when (this) {
            AGENT_1B -> "Qwen_Qwen3-1.7B-Q4_K_M.gguf"  // bartowski 네이밍
            EMERGENCY_E2B -> "Qwen3-1.7B-Q8_0.gguf"
            else -> modelFileName
        }
}

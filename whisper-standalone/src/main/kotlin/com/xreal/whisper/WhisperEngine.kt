package com.xreal.whisper

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.*
import org.tensorflow.lite.Interpreter

/**
 * WhisperEngine: Facade for on-device Speech-to-Text.
 * 
 * Modularized Architecture:
 * - AudioCaptureManager: Handles Mic & VAD.
 * - WhisperTokenizer: Handles decoding & filtering.
 * - WhisperInference: Handles TFLite model execution (Single or Split).
 */
class WhisperEngine(private val context: Context) {
    private val TAG = "WhisperEngine"
    
    private val audioCapture = AudioCaptureManager(context)
    private val tokenizer = WhisperTokenizer(context)
    private var inference: WhisperInference? = null
    
    private val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var onResultListener: ((String) -> Unit)? = null
    private var onVadStatusListener: ((Boolean) -> Unit)? = null
    private var onAudioSegmentListener: ((ShortArray) -> Unit)? = null

    /** ★ Phase S — SenseVoice 감정/언어 포함 전체 결과 콜백. */
    private var onSpeechResultListener: ((SpeechResult) -> Unit)? = null

    // ★ 반복 환각 감지: 최근 4개 결과 추적 (같은 텍스트 연속 2회 이상 → 환각)
    private val recentResults = ArrayDeque<String>()
    private val MAX_RECENT = 4

    // ★ TFLite Interpreter는 스레드 안전하지 않음 → Mutex로 동시 추론 방지 (SIGSEGV 방지)
    private val inferenceMutex = kotlinx.coroutines.sync.Mutex()

    // ★ Phase W: 현재 활성 백엔드 (TFLITE / WHISPER_CPP / SHERPA_ONNX)
    private var currentBackend: WhisperBackend = WhisperBackend.TFLITE

    init {
        // Wire up callbacks
        audioCapture.setOnVadStatusListener { isSpeech ->
            onVadStatusListener?.invoke(isSpeech)
        }

        audioCapture.setOnAudioSegmentReadyListener { audioData ->
            onAudioSegmentListener?.invoke(audioData)
            processAudio(audioData)
        }
    }

    fun setOnResultListener(l: (String) -> Unit) {
        onResultListener = l
    }

    fun setOnAudioSegmentListener(l: (ShortArray) -> Unit) {
        onAudioSegmentListener = l
    }

    fun setOnVadStatusListener(l: (Boolean) -> Unit) {
        onVadStatusListener = l
        audioCapture.setOnVadStatusListener(l) // Forward to manager
    }

    /**
     * ★ Phase S — SenseVoice 감정/언어 포함 결과 콜백 등록.
     * SenseVoice 백엔드 사용 시 emotion, lang, audioEvent 필드가 채워짐.
     * TFLite/whisper.cpp 백엔드는 emotion=null.
     */
    fun setOnSpeechResultListener(l: (SpeechResult) -> Unit) {
        onSpeechResultListener = l
    }

    companion object {
        // ── 백엔드 선택 상수 ──────────────────────────────────────────────────
        /** 자동 선택: GGML 발견 시 whisper.cpp → sherpa-onnx → TFLite 순 */
        const val BACKEND_AUTO        = "auto"
        /** sherpa-onnx 강제 (SenseVoice 모델 필요, Android .so 필요) */
        const val BACKEND_SHERPA      = "sherpa_onnx"
        /** whisper.cpp 강제 (GGML 모델 + Android .so 필요) */
        const val BACKEND_WHISPER_CPP = "whisper_cpp"
        /** TFLite 강제 (영어 전용, 항상 사용 가능) */
        const val BACKEND_TFLITE      = "tflite"

        private const val PREF_NAME = "whisper_config"
        private const val PREF_KEY  = "backend_pref"

        /** 백엔드 선택을 SharedPreferences에 저장. reinitialize() 호출 후 적용. */
        fun setBackendPreference(context: Context, backend: String) {
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putString(PREF_KEY, backend).apply()
            Log.i("WhisperEngine", "백엔드 설정 → $backend (reinitialize() 후 적용됨)")
        }

        /** 현재 저장된 백엔드 선택 반환. */
        fun getBackendPreference(context: Context): String =
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(PREF_KEY, BACKEND_AUTO) ?: BACKEND_AUTO

        // 외부 저장소 TFLite 모델 검색 후보
        private val EXTERNAL_MODEL_CANDIDATES = listOf(
            "whisper-small.tflite",
            "whisper-base.tflite",
            "whisper-tiny.tflite"
        )
    }

    /** 현재 활성 백엔드 이름 반환 (로깅/디버그용). */
    fun getCurrentBackendName(): String = currentBackend.name

    /**
     * 백엔드 선택 변경 후 재초기화.
     * 예) WhisperEngine.setBackendPreference(ctx, BACKEND_SHERPA); engine.reinitialize(options)
     */
    fun reinitialize(options: Interpreter.Options = Interpreter.Options(), modelType: ModelType = ModelType.BASE) {
        Log.i(TAG, "reinitialize() 호출 → 백엔드: ${getBackendPreference(context)}")
        inference?.close()
        inference = null
        initialize(options, modelType)
    }

    /**
     * 외부 저장소에서 Whisper 모델 파일 검색.
     * 앱 외부 파일 디렉토리: /sdcard/Android/data/com.xreal.nativear/files/models/
     * @return 발견된 모델 파일 경로 또는 null
     */
    private fun findExternalModel(preferredSuffix: String? = null): Pair<String, String>? {
        val dirs = listOf(
            context.getExternalFilesDir("models"),
            context.filesDir.resolve("models")
        ).filterNotNull()

        for (dir in dirs) {
            if (!dir.exists()) continue
            // preferred suffix 먼저 검색 (e.g. "small")
            if (preferredSuffix != null) {
                val preferred = dir.listFiles()?.firstOrNull {
                    it.name.contains("whisper") && it.name.contains(preferredSuffix) && it.name.endsWith(".tflite")
                }
                if (preferred != null) return preferred.name to preferred.absolutePath
            }
            // 후보 파일 순서대로 검색
            for (candidate in EXTERNAL_MODEL_CANDIDATES) {
                val file = java.io.File(dir, candidate)
                if (file.exists()) return candidate to file.absolutePath
            }
        }
        return null
    }

    // Default to BASE for better Korean accuracy
    fun initialize(options: Interpreter.Options, modelType: ModelType = ModelType.BASE) {
        val backendPref = getBackendPreference(context)
        Log.i(TAG, "Initializing WhisperEngine type=$modelType, backend_pref=$backendPref")

        val modelsDir = context.getExternalFilesDir("models")

        // ── 비-TFLite 백엔드 탐색 (FORCE_TFLITE 아닐 때) ────────────────────
        if (backendPref != BACKEND_TFLITE && modelsDir != null) {

            // 1순위: whisper.cpp GGML (pref=auto 또는 whisper_cpp)
            if (backendPref == BACKEND_AUTO || backendPref == BACKEND_WHISPER_CPP) {
                val ggmlModel = WhisperCppInference.findModel(modelsDir)
                if (ggmlModel != null) {
                    try {
                        val cppInference = WhisperCppInference(ggmlModel.absolutePath)
                        cppInference.initialize(options)
                        inference = cppInference
                        currentBackend = WhisperBackend.WHISPER_CPP
                        Log.i(TAG, "✅ 백엔드: whisper.cpp (${ggmlModel.name}) [pref=$backendPref]")
                        return
                    } catch (e: Exception) {
                        Log.w(TAG, "whisper.cpp 초기화 실패 → 다음 시도: ${e.message}")
                    }
                } else if (backendPref == BACKEND_WHISPER_CPP) {
                    Log.w(TAG, "⚠️ FORCE_WHISPER_CPP: GGML 모델 없음 (models/ 에 ggml-*.bin 필요)")
                }
            }

            // 2순위: sherpa-onnx (pref=auto 또는 sherpa_onnx)
            if (backendPref == BACKEND_AUTO || backendPref == BACKEND_SHERPA) {
                val sherpaDir = SherpaOnnxInference.findModelDir(modelsDir)
                if (sherpaDir != null) {
                    try {
                        val sherpaInference = SherpaOnnxInference(sherpaDir)
                        sherpaInference.initialize(options)
                        inference = sherpaInference
                        currentBackend = WhisperBackend.SHERPA_ONNX
                        Log.i(TAG, "✅ 백엔드: sherpa-onnx (${sherpaDir.name}) [pref=$backendPref]")
                        return
                    } catch (e: Exception) {
                        Log.w(TAG, "sherpa-onnx 초기화 실패 → TFLite 폴백: ${e.message}")
                    }
                } else if (backendPref == BACKEND_SHERPA) {
                    Log.w(TAG, "⚠️ FORCE_SHERPA: sherpa-onnx 모델 없음 (models/ 에 sherpa-onnx-* 디렉터리 필요)")
                }
            }
        }

        if (backendPref == BACKEND_WHISPER_CPP || backendPref == BACKEND_SHERPA) {
            Log.w(TAG, "⚠️ 선택한 백엔드($backendPref) 사용 불가 → TFLite 폴백")
        } else {
            Log.w(TAG, "GGML/sherpa 모델 없음 → TFLite 폴백 (영어 전용)")
        }
        currentBackend = WhisperBackend.TFLITE

        try {
            val typeSuffix = when(modelType) {
                ModelType.TINY -> "tiny"
                ModelType.BASE -> "base"
                ModelType.SMALL -> "small"
            }

            // ★ 전략 1: 외부 저장소에서 검색 (whisper-small.tflite, whisper-base.tflite, ...)
            val externalModel = findExternalModel(typeSuffix) ?: findExternalModel(null)
            if (externalModel != null) {
                val (modelName, modelAbsPath) = externalModel
                Log.i(TAG, "🚀 Strategy: External Single Model — $modelName (${java.io.File(modelAbsPath).length() / 1_048_576}MB)")
                inference = WhisperSingleInference(context, modelName, externalFilePath = modelAbsPath)
                inference?.initialize(options)
                Log.i(TAG, "✅ WhisperEngine Initialized (외부 모델: $modelName)")
                return
            }

            // ★ 전략 2: Assets에서 TFLite encoder 검색 → Split 모드
            val assetManager = context.assets
            val modelList = assetManager.list("") ?: emptyArray()

            // 인코더 파일이 있는지 확인 (QNN proxy 바이너리는 TFLite 헤더가 없으므로 검증)
            val hasValidEncoder = modelList.any { assetName ->
                if (!(assetName.contains("whisper") && assetName.contains(typeSuffix) &&
                        assetName.contains("encoder") && assetName.endsWith(".tflite"))) return@any false
                // ★ TFLite 유효성 검증: 헤더가 "TFL" 또는 적절한 flatbuffer 시그니처인지 확인
                try {
                    val fd = assetManager.openFd(assetName)
                    val header = ByteArray(8)
                    java.io.FileInputStream(fd.fileDescriptor).use { it.read(header) }
                    fd.close()
                    // TFLite flatbuffer: offset 4에 "TFL" 문자열 또는 적합한 매직바이트
                    val isTflite = String(header.sliceArray(4..7)).startsWith("TFL") ||
                            (header[0] == 0x18.toByte() && header[1] == 0x00.toByte())
                    if (!isTflite) Log.w(TAG, "⚠️ $assetName: TFLite가 아님 (QNN proxy?) — 건너뜀")
                    isTflite
                } catch (e: Exception) {
                    false
                }
            }

            if (hasValidEncoder) {
                Log.i(TAG, "🚀 Strategy: Split Model ($modelType)")
                inference = WhisperSplitInference(context, modelType)
            } else {
                // 전략 3: assets에서 단일 모델 검색
                val singleFile = modelList.firstOrNull { name ->
                    name.contains("whisper") && name.endsWith(".tflite") &&
                    !name.contains("encoder") && !name.contains("decoder")
                }
                if (singleFile != null) {
                    Log.i(TAG, "🚀 Strategy: Single Model from assets ($singleFile)")
                    inference = WhisperSingleInference(context, singleFile)
                } else {
                    Log.e(TAG, "❌ Whisper 모델 없음: assets 또는 외부 저장소(/sdcard/Android/data/com.xreal.nativear/files/models/)에 whisper*.tflite 파일이 필요합니다")
                    return
                }
            }

            inference?.initialize(options)
            Log.i(TAG, "✅ WhisperEngine Initialized")

        } catch (e: Exception) {
            Log.e(TAG, "Init Failed: ${e.message}")
            e.printStackTrace()
        }
    }
    
    fun initializeManual(options: Interpreter.Options) {
        initialize(options, ModelType.BASE)
    }

    fun startListening() {
        audioCapture.start()
    }

    fun stopListening() {
        audioCapture.stop()
    }

    fun release() {
        close()
    }

    /**
     * Read the most recent [numSamples] from the audio capture buffer.
     * Used by YAMNet for periodic ambient sound classification (15600 samples = 0.975s @ 16kHz).
     */
    fun getRecentAudio(numSamples: Int): ShortArray? {
        return audioCapture.getRecentAudio(numSamples)
    }

    /**
     * Extract audio embedding from raw PCM audio.
     * Uses the inference engine's embedding extraction capability.
     * @param audioData: Raw PCM audio data
     * @return FloatArray embedding (80-dim for Single, 384-dim for Split), or null if extraction fails
     */
    fun extractEmbedding(audioData: ShortArray): FloatArray? {
        return inference?.getEmbedding(audioData)
    }

    /**
     * 오디오 데이터를 직접 전사 (비-스트리밍).
     * 활성 대화 모드에서 캡처한 오디오 버퍼를 직접 전사할 때 사용.
     * @return SpeechResult (텍스트 + 감정 + 언어) 또는 null
     */
    fun transcribeDirect(audioData: ShortArray): SpeechResult? {
        val currentInference = inference ?: return null
        return when (currentBackend) {
            WhisperBackend.SHERPA_ONNX -> currentInference.transcribeFull(audioData)
            WhisperBackend.WHISPER_CPP -> {
                val t = currentInference.transcribeText(audioData)
                if (t != null) SpeechResult(t) else null
            }
            WhisperBackend.TFLITE -> {
                val tokens = currentInference.transcribe(audioData)
                val decoded = tokenizer.decode(tokens)
                if (decoded.isNotBlank()) SpeechResult(decoded) else null
            }
        }
    }

    fun close() {
        stopListening()
        audioCapture.close()
        inference?.close()
        inference = null
        engineScope.cancel()
    }

    private fun processAudio(audioData: ShortArray) {
        // ★ 1단계: RMS 에너지 필터 — 300 이하면 무음/잡음으로 간주 (이전 50에서 강화)
        val sumSq = audioData.fold(0L) { acc, s -> acc + s.toLong() * s }
        val rms = kotlin.math.sqrt(sumSq.toDouble() / audioData.size)
        if (rms < 300.0) {
            Log.v(TAG, "Audio skip: RMS=${rms.toInt()} < 300 (무음)")
            return
        }

        // ★ 2단계: 제로크로싱율 체크 — 실제 음성 신호 확인 (너무 낮으면 DC 오프셋/잡음)
        var zcr = 0
        for (i in 1 until audioData.size) {
            if ((audioData[i] >= 0) != (audioData[i - 1] >= 0)) zcr++
        }
        val zcrRate = zcr.toFloat() / audioData.size
        if (zcrRate < 0.005f) {
            Log.v(TAG, "Audio skip: ZCR=${"%.4f".format(zcrRate)} (DC 오프셋/잡음 의심)")
            return
        }

        Log.d(TAG, "Audio OK → Whisper 추론 시작 (RMS=${rms.toInt()}, ZCR=${"%.3f".format(zcrRate)}, samples=${audioData.size})")

        engineScope.launch {
            // ★ TFLite 동시 추론 방지: 이미 추론 중이면 이 세그먼트 건너뜀 (SIGSEGV 예방)
            if (!inferenceMutex.tryLock()) {
                Log.d(TAG, "Whisper 추론 중 — 이 세그먼트 건너뜀 (concurrent skip)")
                return@launch
            }
            try {
                val currentInference = inference ?: return@launch

                // ★ Phase W+S: transcribeFull() 호출 — 텍스트 + 감정 + 언어 한번에 획득
                val speechResult: SpeechResult? = when (currentBackend) {
                    WhisperBackend.SHERPA_ONNX -> {
                        // SenseVoice: transcribeFull()로 감정+언어 포함 결과
                        currentInference.transcribeFull(audioData)
                    }
                    WhisperBackend.WHISPER_CPP -> {
                        // whisper.cpp: 텍스트만 반환 (감정 없음)
                        val t = currentInference.transcribeText(audioData)
                        if (t != null) SpeechResult(t) else null
                    }
                    WhisperBackend.TFLITE -> {
                        // TFLite: 토큰 배열 → WhisperTokenizer.decode()
                        val tokens = currentInference.transcribe(audioData)
                        val decoded = tokenizer.decode(tokens)
                        if (decoded.isNotBlank()) SpeechResult(decoded) else null
                    }
                }

                val text = speechResult?.text ?: run {
                    Log.d(TAG, "${currentBackend.name} 결과: (비어있음)")
                    return@launch
                }

                if (text.isBlank()) {
                    Log.d(TAG, "Whisper 결과: (비어있음 — 필터링됨)")
                    return@launch
                }

                // 최소 길이 필터
                if (text.length < 3) {
                    Log.d(TAG, "Whisper 결과: \"$text\" (너무 짧음 — 스킵)")
                    return@launch
                }

                // ★ 3단계: 반복 환각 감지 — 최근 결과와 동일하면 스킵 (모든 백엔드 공통)
                synchronized(recentResults) {
                    val recentCount = recentResults.count { it == text }
                    if (recentCount >= 2) {
                        Log.w(TAG, "환각 감지 (반복 ${recentCount+1}회): \"$text\" — 스킵")
                        return@launch
                    }
                    recentResults.addLast(text)
                    if (recentResults.size > MAX_RECENT) recentResults.removeFirst()
                }

                val emotionTag = speechResult.emotion?.let { " [${speechResult.lang}/$it]" } ?: ""
                Log.i(TAG, "[${currentBackend.name}] 결과: \"$text\"$emotionTag")

                withContext(Dispatchers.Main) {
                    onResultListener?.invoke(text)                    // 기존 호환 콜백
                    onSpeechResultListener?.invoke(speechResult)      // ★ 감정 포함 전체 결과
                }
            } catch (e: Exception) {
                Log.e(TAG, "Processing Error: ${e.message}", e)
            } finally {
                inferenceMutex.unlock()
            }
        }
    }
}

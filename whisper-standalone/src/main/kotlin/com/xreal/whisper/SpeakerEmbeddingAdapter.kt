package com.xreal.whisper

import android.util.Log
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File

/**
 * SpeakerEmbeddingAdapter — 3D-Speaker ECAPA-TDNN 기반 화자 임베딩 추출.
 *
 * WavLM (370MB, 768-dim) → ECAPA-TDNN (20MB, 192-dim) 대체.
 * sherpa-onnx JNI를 통해 동작.
 *
 * ## 모델 경로
 * /sdcard/Android/data/com.xreal.nativear/files/models/3dspeaker-ecapa-tdnn/
 *   ├── model.onnx (또는 model.int8.onnx)
 *
 * ## 사용법
 * ```kotlin
 * val adapter = SpeakerEmbeddingAdapter()
 * adapter.initialize(modelDir)
 * val embedding = adapter.extractEmbedding(audioData)  // FloatArray(192)
 * ```
 */
class SpeakerEmbeddingAdapter {
    private val TAG = "SpeakerEmbedding"
    private var extractor: SpeakerEmbeddingExtractor? = null
    var isReady: Boolean = false
        private set
    var embeddingDim: Int = 192
        private set

    /**
     * sherpa-onnx 모델 디렉터리에서 ECAPA-TDNN 모델 초기화.
     * @param modelDir 모델 파일이 있는 디렉터리 (model.onnx 또는 model.int8.onnx)
     */
    fun initialize(modelDir: File) {
        val modelFile = modelDir.listFiles()
            ?.filter { it.name.endsWith(".onnx") && it.name.contains("model", ignoreCase = true) }
            ?.minByOrNull { if (it.name.contains("int8")) 0 else 1 }

        if (modelFile == null) {
            Log.w(TAG, "ECAPA-TDNN 모델 없음: ${modelDir.absolutePath}")
            return
        }

        try {
            val config = SpeakerEmbeddingExtractorConfig(
                model = modelFile.absolutePath,
                numThreads = 2,
                provider = "cpu",
            )
            extractor = SpeakerEmbeddingExtractor(assetManager = null, config = config)
            embeddingDim = extractor?.dim ?: 192
            isReady = true
            Log.i(TAG, "ECAPA-TDNN 초기화 완료: ${modelFile.name} (dim=$embeddingDim)")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "libsherpa-onnx-jni.so 로드 실패: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "ECAPA-TDNN 초기화 실패: ${e.message}", e)
        }
    }

    /**
     * 16kHz mono PCM에서 화자 임베딩 추출.
     * @param audioData 16kHz ShortArray (최소 0.5초 = 8000 samples 권장)
     * @return FloatArray (192-dim) 또는 null
     */
    fun extractEmbedding(audioData: ShortArray): FloatArray? {
        val ext = extractor ?: return null
        if (!isReady) return null
        if (audioData.size < 4000) return null  // 최소 0.25초

        return try {
            val floatAudio = FloatArray(audioData.size) { audioData[it] / 32768f }
            val stream = ext.createStream()
            stream.acceptWaveform(floatAudio, sampleRate = 16000)
            stream.inputFinished()
            val embedding = ext.compute(stream)
            stream.release()
            embedding
        } catch (e: Exception) {
            Log.e(TAG, "임베딩 추출 실패: ${e.message}", e)
            null
        }
    }

    /**
     * ByteArray 변환 (SceneDatabase vec 저장용).
     */
    fun extractEmbeddingAsBytes(audioData: ShortArray): ByteArray? {
        val embedding = extractEmbedding(audioData) ?: return null
        val buffer = java.nio.ByteBuffer.allocate(embedding.size * 4)
        buffer.order(java.nio.ByteOrder.nativeOrder())
        for (f in embedding) buffer.putFloat(f)
        return buffer.array()
    }

    fun release() {
        extractor?.release()
        extractor = null
        isReady = false
    }

    companion object {
        /**
         * 외부 저장소에서 ECAPA-TDNN 모델 디렉터리 검색.
         * 후보: 3dspeaker*, ecapa*, speaker-embed*
         */
        fun findModelDir(baseDir: File): File? {
            val dirs = baseDir.listFiles()?.filter { it.isDirectory } ?: return null
            val candidates = listOf("3dspeaker", "ecapa", "speaker-embed", "speaker_embed")
            for (keyword in candidates) {
                val dir = dirs.firstOrNull { it.name.contains(keyword, ignoreCase = true) }
                if (dir != null) {
                    Log.i("SpeakerEmbedding", "화자 모델 발견: ${dir.name}")
                    return dir
                }
            }
            return null
        }
    }
}

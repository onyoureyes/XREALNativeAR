package com.xreal.nativear

import com.xreal.nativear.core.IAssetLoader
import com.xreal.nativear.core.XRealLogger
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AudioEventClassifier: YAMNet-based ambient sound classification (521 AudioSet classes).
 *
 * Runs on 0.975s of 16kHz audio (15,600 samples) and outputs:
 *   - [1, 521] class scores (Speech, Music, Traffic, Dog, etc.)
 *   - [1, 1024] audio embedding vector for similarity search
 */
class AudioEventClassifier(private val assetLoader: IAssetLoader) : com.xreal.ai.IAIModel {

    private val TAG = "AudioEventClassifier"
    private val MODEL_FILE = "yamnet.tflite"
    private val LABELS_FILE = "yamnet_labels.txt"
    private val INPUT_SAMPLES = 15600  // 0.975s @ 16kHz
    private val NUM_CLASSES = 521
    private val EMBEDDING_DIM = 1024
    private val SCORE_THRESHOLD = 0.3f

    private var interpreter: Interpreter? = null
    private var labels: List<String> = emptyList()

    override val priority: Int = 2
    override var isReady: Boolean = false
        private set
    override var isLoaded: Boolean = false
        private set

    data class AudioEvent(val label: String, val score: Float)

    override suspend fun prepare(options: Interpreter.Options): Boolean {
        return try {
            val buffer = assetLoader.loadModelBuffer(MODEL_FILE)

            interpreter = Interpreter(buffer, options)

            labels = assetLoader.loadTextAsset(LABELS_FILE).lines().filter { it.isNotBlank() }
            XRealLogger.impl.i(TAG, "YAMNet loaded: ${labels.size} classes")

            isLoaded = true
            isReady = true
            true
        } catch (e: Exception) {
            XRealLogger.impl.e(TAG, "Failed to load YAMNet: ${e.message}", e)
            false
        }
    }

    override fun release() {
        interpreter?.close()
        interpreter = null
        isLoaded = false
        isReady = false
    }

    /**
     * Classify ambient audio into AudioSet categories.
     * @param pcm16kHz ShortArray of 16kHz PCM audio (at least 15600 samples)
     * @return Top events with score > 0.3, sorted by score descending
     */
    fun classify(pcm16kHz: ShortArray): List<AudioEvent> {
        val interp = interpreter ?: return emptyList()

        val inputBuffer = prepareInput(pcm16kHz)
        val scoresOutput = Array(1) { FloatArray(NUM_CLASSES) }
        val embeddingOutput = Array(1) { FloatArray(EMBEDDING_DIM) }
        val spectrogramOutput = Array(1) { FloatArray(96 * 64) }

        val outputMap = HashMap<Int, Any>()
        outputMap[0] = scoresOutput
        outputMap[1] = embeddingOutput
        outputMap[2] = spectrogramOutput

        try {
            interp.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputMap)
        } catch (e: Exception) {
            XRealLogger.impl.e(TAG, "YAMNet inference failed: ${e.message}")
            return emptyList()
        }

        val scores = scoresOutput[0]
        val results = mutableListOf<AudioEvent>()
        for (i in scores.indices) {
            if (scores[i] >= SCORE_THRESHOLD && i < labels.size) {
                results.add(AudioEvent(labels[i], scores[i]))
            }
        }
        results.sortByDescending { it.score }
        return results.take(10)
    }

    /**
     * Extract 1024-dim audio embedding from YAMNet's intermediate layer.
     */
    fun getEmbedding(pcm16kHz: ShortArray): FloatArray {
        val interp = interpreter ?: return FloatArray(0)

        val inputBuffer = prepareInput(pcm16kHz)
        val scoresOutput = Array(1) { FloatArray(NUM_CLASSES) }
        val embeddingOutput = Array(1) { FloatArray(EMBEDDING_DIM) }
        val spectrogramOutput = Array(1) { FloatArray(96 * 64) }

        val outputMap = HashMap<Int, Any>()
        outputMap[0] = scoresOutput
        outputMap[1] = embeddingOutput
        outputMap[2] = spectrogramOutput

        try {
            interp.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputMap)
        } catch (e: Exception) {
            XRealLogger.impl.e(TAG, "YAMNet embedding extraction failed: ${e.message}")
            return FloatArray(0)
        }

        return embeddingOutput[0]
    }

    /**
     * Run both classify + embedding in a single inference pass.
     */
    fun classifyWithEmbedding(pcm16kHz: ShortArray): Pair<List<AudioEvent>, FloatArray> {
        val interp = interpreter ?: return emptyList<AudioEvent>() to FloatArray(0)

        val inputBuffer = prepareInput(pcm16kHz)
        val scoresOutput = Array(1) { FloatArray(NUM_CLASSES) }
        val embeddingOutput = Array(1) { FloatArray(EMBEDDING_DIM) }
        val spectrogramOutput = Array(1) { FloatArray(96 * 64) }

        val outputMap = HashMap<Int, Any>()
        outputMap[0] = scoresOutput
        outputMap[1] = embeddingOutput
        outputMap[2] = spectrogramOutput

        try {
            interp.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputMap)
        } catch (e: Exception) {
            XRealLogger.impl.e(TAG, "YAMNet inference failed: ${e.message}")
            return emptyList<AudioEvent>() to FloatArray(0)
        }

        val scores = scoresOutput[0]
        val results = mutableListOf<AudioEvent>()
        for (i in scores.indices) {
            if (scores[i] >= SCORE_THRESHOLD && i < labels.size) {
                results.add(AudioEvent(labels[i], scores[i]))
            }
        }
        results.sortByDescending { it.score }
        return results.take(10) to embeddingOutput[0]
    }

    /**
     * Convert ShortArray PCM to float [-1, 1] and pad/truncate to INPUT_SAMPLES.
     */
    private fun prepareInput(pcm16kHz: ShortArray): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(INPUT_SAMPLES * 4).order(ByteOrder.nativeOrder())
        val samplesToUse = minOf(pcm16kHz.size, INPUT_SAMPLES)
        for (i in 0 until samplesToUse) {
            buffer.putFloat(pcm16kHz[i].toFloat() / 32768.0f)
        }
        // Zero-pad if audio is shorter than 0.975s
        for (i in samplesToUse until INPUT_SAMPLES) {
            buffer.putFloat(0f)
        }
        buffer.rewind()
        return buffer
    }
}

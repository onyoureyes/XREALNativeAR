package com.xreal.nativear

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import org.json.JSONObject

class WhisperEngine(private val context: Context) {
    private val TAG = "WhisperEngine"
    private var interpreter: Interpreter? = null
    private val melSpectrogram = MelSpectrogram()
    private var vocab: Map<Int, String>? = null
    
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isListening = false
    private var onResultListener: ((String) -> Unit)? = null

    init {
        initialize()
    }

    private fun initialize() {
        try {
            // 1. Load Whisper-Tiny Model (Back to Basics)
            loadModel("whisper-tiny-en.tflite")
            
            // 2. Load Filters (Asset)
            context.assets.open("filters.bin").use { stream ->
                val bytes = stream.readBytes()
                val floatBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asFloatBuffer()
                val filters = FloatArray(floatBuffer.remaining())
                floatBuffer.get(filters)
                melSpectrogram.loadFilters(filters)
                Log.i(TAG, "Mel Filters Loaded: ${filters.size} floats")
            }

            // 3. Load Vocab (Asset)
            context.assets.open("vocab.json").use { stream ->
                val jsonStr = stream.bufferedReader().use { it.readText() }
                val json = JSONObject(jsonStr)
                val map = mutableMapOf<Int, String>()
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val id = json.getInt(key)
                    map[id] = key
                }
                vocab = map
                Log.i(TAG, "Vocab Loaded: ${map.size} tokens")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Init Failed: ${e.message}")
        }
    }

    private fun loadModel(fileName: String) {
        val assetFileDescriptor = context.assets.openFd(fileName)
        val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = fileInputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        val modelBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        
        val options = Interpreter.Options()
        options.setNumThreads(4)
        interpreter = Interpreter(modelBuffer, options)
        Log.i(TAG, "Model Loaded: $fileName")
        logTensorShapes()
    }

    fun setOnResultListener(l: (String) -> Unit) {
        this.onResultListener = l
    }

    fun startListening() {
        if (isListening) return
        isListening = true
        
        engineScope.launch {
            Log.i(TAG, "Starting Basic 3s Capture Loop...")
            
            while (isListening) {
                try {
                    val sampleRate = 16000
                    val bufferSize = android.media.AudioRecord.getMinBufferSize(
                        sampleRate, 
                        android.media.AudioFormat.CHANNEL_IN_MONO, 
                        android.media.AudioFormat.ENCODING_PCM_16BIT
                    ) * 2
                    
                    val audioRecord = android.media.AudioRecord(
                        android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        sampleRate, 
                        android.media.AudioFormat.CHANNEL_IN_MONO, 
                        android.media.AudioFormat.ENCODING_PCM_16BIT, 
                        bufferSize
                    )

                    if (audioRecord.state != android.media.AudioRecord.STATE_INITIALIZED) {
                        Log.e(TAG, "AudioRecord init failed")
                        delay(2000)
                        continue
                    }

                    val buffer = ShortArray(sampleRate * 3) // 3 seconds
                    audioRecord.startRecording()
                    Log.i(TAG, "Recording 3s...")
                    
                    var totalRead = 0
                    val temp = ShortArray(1024)
                    while (totalRead < buffer.size && isListening) {
                        val read = audioRecord.read(temp, 0, temp.size)
                        if (read > 0) {
                            val copyLen = kotlin.math.min(read, buffer.size - totalRead)
                            System.arraycopy(temp, 0, buffer, totalRead, copyLen)
                            totalRead += copyLen
                        }
                    }
                    
                    audioRecord.stop()
                    audioRecord.release()
                    
                    if (isListening && totalRead >= sampleRate) {
                        Log.i(TAG, "Transcribing...")
                        val result = transcribe(buffer)
                        if (result.isNotBlank()) {
                            Log.e(TAG, "Final Transcript: $result")
                            withContext(Dispatchers.Main) {
                                onResultListener?.invoke(result)
                            }
                            
                            // Visual Feedback via Broadcast
                            val intent = android.content.Intent("com.xreal.nativear.TRANSCRIPT_UPDATE")
                            intent.putExtra("text", "STT: $result")
                            context.sendBroadcast(intent)
                        }
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Loop Error: ${e.message}")
                    delay(1000)
                }
            }
        }
    }

    fun stopListening() {
        isListening = false
    }

    fun close() {
        interpreter?.close()
        engineScope.cancel()
    }

    private suspend fun transcribe(audioData: ShortArray): String = withContext(Dispatchers.Default) {
        if (interpreter == null) return@withContext ""

        try {
            val melData = melSpectrogram.process(audioData) 
            
            // Input: [1, 80, 3000]
            val inputBuffer = arrayOf(Array(80) { m ->
                FloatArray(3000) { t ->
                    melData[m * 3000 + t]
                }
            })
            
            // Output: [1, 448] for Tiny
            val outputBuffer = Array(1) { IntArray(448) } 
            
            interpreter?.run(inputBuffer, outputBuffer)
            
            val sb = StringBuilder()
            for (tokenId in outputBuffer[0]) {
                if (tokenId == 50257) break 
                val word = vocab?.get(tokenId) ?: ""
                sb.append(word)
            }
            
            val result = sb.toString().replace("Ġ", " ").trim()
            
            // Minimal filter for common noise
            val hallucinations = setOf("you", "oh", "one", "transcript", "by", "thank")
            if (hallucinations.contains(result.lowercase()) || result.length < 2) {
                return@withContext ""
            }

            result
        } catch (e: Exception) {
            Log.e(TAG, "Transcribe Error: ${e.message}")
            ""
        }
    }

    fun logTensorShapes() {
        val inputCount = interpreter?.inputTensorCount ?: 0
        Log.i(TAG, "Model Input Count: $inputCount")
        for (i in 0 until inputCount) {
            val t = interpreter?.getInputTensor(i)
            Log.i(TAG, "Input $i: ${t?.shape()?.contentToString()} (${t?.dataType()})")
        }
        val outputCount = interpreter?.outputTensorCount ?: 0
        Log.i(TAG, "Model Output Count: $outputCount")
        for (i in 0 until outputCount) {
            val t = interpreter?.getOutputTensor(i)
            Log.i(TAG, "Output $i: ${t?.shape()?.contentToString()} (${t?.dataType()})")
        }
    }
}

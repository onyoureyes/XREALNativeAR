package com.xreal.nativear

import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import org.jtransforms.fft.FloatFFT_1D

// Pure Kotlin implementation of Log-Mel Spectrogram for Whisper
// Reference: Adapted from OpenAI's Whisper Python implementation
class MelSpectrogram {
    private val SAMPLE_RATE = 16000
    private val N_FFT = 400
    private val HOP_LENGTH = 160
    private val N_MELS = 80
    
    // To be loaded from filters.bin
    private var melFilters: FloatArray? = null 
    private var fft: FloatFFT_1D? = null
    private var window: FloatArray? = null

    // Load flattened [80 * 201] filterbank
    fun loadFilters(filters: FloatArray) {
        this.melFilters = filters
    }

    fun loadFiltersFromAssets(context: Context, fileName: String = "filters.bin") {
        try {
            val inputStream = context.assets.open(fileName)
            val bytes = inputStream.readBytes()
            inputStream.close()
            
            val floatBuffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val filters = FloatArray(floatBuffer.capacity())
            floatBuffer.get(filters)
            
            this.melFilters = filters
            android.util.Log.i("MelSpectrogram", "✅ Loaded $fileName (${filters.size} floats)")
        } catch (e: Exception) {
            android.util.Log.e("MelSpectrogram", "❌ Failed to load $fileName: ${e.message}")
        }
    }

    init {
        fft = FloatFFT_1D(N_FFT.toLong())
        window = FloatArray(N_FFT) { i ->
            (0.5 * (1 - cos(2 * Math.PI * i / N_FFT))).toFloat()
        }
    }

    fun process(audioData: ShortArray): FloatArray {
        if (melFilters == null) return FloatArray(0)

        // 1. Convert Short to Float & Pad to 30s + Extra for FFT
        val audioFloats = FloatArray(480000 + N_FFT) // Padding for last frame
        val copyLen = kotlin.math.min(audioData.size, 480000)
        for (i in 0 until copyLen) {
            audioFloats[i] = audioData[i] / 32768.0f
        }
        
        // 2. STFT Loop
        val numFrames = 3000
        val fftSize = N_FFT / 2 + 1 // 201
        
        // Output: [80 x 3000] flattened
        // Whisper expects [1, 80, 3000]
        val melSpectrogram = FloatArray(N_MELS * numFrames) 
        
        val fftBuffer = FloatArray(N_FFT * 2) // JTransforms requires 2x size for complex output
        val powerSpectrum = FloatArray(fftSize)
        
        for (frame in 0 until numFrames) {
            val startSample = frame * HOP_LENGTH
            if (startSample + N_FFT > audioFloats.size) break
            
            // A. Windowing
            for (i in 0 until N_FFT) {
                fftBuffer[i] = audioFloats[startSample + i] * window!![i]
                fftBuffer[N_FFT + i] = 0f // Imaginary part 0
            }
            
            // B. FFT
            fft?.complexForward(fftBuffer)
            
            // C. Power Spectrum (|X|^2)
            // JTransforms output: [Re0, Im0, Re1, Im1, ...]
            for (k in 0 until fftSize) {
                val re = fftBuffer[2 * k]
                val im = fftBuffer[2 * k + 1]
                powerSpectrum[k] = (re * re + im * im)
            }
            
            // D. Mel Filterbank Application (Matrix Mul)
            for (m in 0 until N_MELS) {
                var sum = 0.0f
                for (k in 0 until fftSize) {
                    sum += powerSpectrum[k] * melFilters!![m * fftSize + k]
                }
                
                // E. Log Compression
                val logMel = log10(max(sum, 1e-10f))
                
                // F. Map to 1D Array
                val index = m * numFrames + frame
                melSpectrogram[index] = logMel
            }
        }
        
        // G. Global Max Scaling (Whisper Logic)
        var maxVal = -10000.0f
        for (v in melSpectrogram) {
            if (v > maxVal) maxVal = v
        }
        
        for (i in melSpectrogram.indices) {
            melSpectrogram[i] = max(melSpectrogram[i], maxVal - 8.0f)
            // Normalizing range [maxVal - 8, maxVal] to [-1.0, 1.0]
            melSpectrogram[i] = (melSpectrogram[i] - maxVal + 4.0f) / 4.0f
        }
        
        return melSpectrogram
    }
}

        
        return melSpectrogram
    }
}

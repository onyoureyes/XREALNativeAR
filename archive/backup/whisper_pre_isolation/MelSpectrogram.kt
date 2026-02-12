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
                powerSpectrum[k] = (re * re + im * im) // + 1.0f/N_FFT normalization omitted (usually implicit)
            }
            
            // D. Mel Filterbank Application (Matrix Mul)
            // mel_spec[m] = sum(power[k] * filter[m][k])
            for (m in 0 until N_MELS) {
                var sum = 0.0f
                for (k in 0 until fftSize) {
                    // filter index: m * fftSize + k
                    sum += powerSpectrum[k] * melFilters!![m * fftSize + k]
                }
                
                // E. Log Compression
                val logMel = log10(max(sum, 1e-10f))
                
                // F. Enhance (Scale/Shift as per Whisper training)
                // Whisper: log_spec = (log_spec + 4.0) / 4.0 usually? 
                // Actually standard Whisper just takes log10. 
                // We store in Col-Major or Row-Major? 
                // Whisper TFLite usually expects [1, 80, 3000]. 
                // Flat array layout: [m=0, t=0], [m=0, t=1]... is row major?
                // Tensor layout is [Batch, Channels, Time] usually or [Batch, Time, Channels]?
                // PyTorch Whisper is (Batch, 80, 3000). 
                // So index = m * 3000 + frame
                
                val index = m * numFrames + frame
                
                // Value clamping (optional approx)
                var value = (logMel.toFloat() + 4.0f) / 4.0f // Standard normalization? 
                // Actually pure log10 is often used, let's stick to simple log10 first.
                // Reverting normalization to raw log10 for safety unless specific TFLite model demands it.
                // The standard Whisper preprocessing is just log10(max(x, 1e-10)).
                melSpectrogram[index] = logMel
            }
        }
        
        // G. Global Max Scaling (Whisper Logic)
        // max_val = max(mel_spec)
        // mel_spec = max(mel_spec, max_val - 8.0)
        // mel_spec = (mel_spec + 4.0) / 4.0
        
        var maxVal = -10000.0f
        for (v in melSpectrogram) {
            if (v > maxVal) maxVal = v
        }
        
        for (i in melSpectrogram.indices) {
            melSpectrogram[i] = max(melSpectrogram[i], maxVal - 8.0f)
            melSpectrogram[i] = (melSpectrogram[i] + 4.0f) / 4.0f
        }
        
        return melSpectrogram
    }
}

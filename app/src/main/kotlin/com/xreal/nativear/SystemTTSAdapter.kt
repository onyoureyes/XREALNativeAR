package com.xreal.nativear

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import com.xreal.ai.IAIModel
import org.tensorflow.lite.Interpreter
import java.util.*

/**
 * SystemTTSAdapter: Android TextToSpeech wrapper implementing IAIModel for unified management.
 */
class SystemTTSAdapter(context: Context) : IAIModel {
    private val TAG = "SystemTTSAdapter"
    private var tts: TextToSpeech? = null
    override val priority: Int = 1
    override var isReady: Boolean = false
        get() = isReadyInternal
    override var isLoaded: Boolean = false
        get() = isReadyInternal
    
    private var isReadyInternal = false
    
    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isReadyInternal = true
                tts?.language = Locale.KOREAN
            } else {
                Log.e(TAG, "TTS Initialization Failed")
            }
        }
    }
    
    override suspend fun prepare(options: Interpreter.Options): Boolean {
        // System TTS is managed by Android, we just wait for its ready state
        return isReadyInternal
    }
    
    fun setProgressListener(listener: android.speech.tts.UtteranceProgressListener) {
        tts?.setOnUtteranceProgressListener(listener)
    }
    
    fun speak(text: String, utteranceId: String = "TTS_TASK") {
        if (!isReady) {
            Log.w(TAG, "TTS not ready yet")
            return
        }
        val params = android.os.Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    fun setPitch(pitch: Float) {
        tts?.setPitch(pitch)
    }

    fun setSpeechRate(rate: Float) {
        tts?.setSpeechRate(rate)
    }
    
    override fun release() {
        Log.i(TAG, "Shutting down TTS")
        tts?.shutdown()
        tts = null
    }
}

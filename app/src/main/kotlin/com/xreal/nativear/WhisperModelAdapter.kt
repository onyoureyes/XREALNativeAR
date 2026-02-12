package com.xreal.nativear

import com.xreal.ai.IAIModel
import org.tensorflow.lite.Interpreter

/**
 * WhisperModelAdapter: Adapter for Whisper ASR model.
 */
class WhisperModelAdapter(private val whisperEngine: Any) : IAIModel {
    
    override val priority: Int = 10 // High priority
    
    override fun initialize(interpreterOptions: Interpreter.Options) {
        // Whisper already initialized
    }
    
    override fun release() {
        // Release whisper resources
    }
}

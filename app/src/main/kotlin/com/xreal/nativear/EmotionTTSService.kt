package com.xreal.nativear

import android.speech.tts.TextToSpeech
import android.util.Log

/**
 * EmotionTTSService: Text-to-Speech with emotion-aware voice modulation.
 * Adjusts pitch, rate, and volume based on detected emotion.
 */
class EmotionTTSService(private val tts: SystemTTSAdapter) {
    private val TAG = "EmotionTTSService"
    
    /**
     * Speak text with emotion-based voice modulation.
     * @param text: Text to speak
     * @param emotion: Emotion label (angry, happy, sad, excited, neutral)
     * @param emotionScore: Confidence score (0.0 - 1.0)
     */
    fun speakWithEmotion(text: String, emotion: String?, emotionScore: Float?) {
        if (emotion == null) {
            tts.speak(text, "EMOTION_NEUTRAL")
            return
        }
        
        // Adjust TTS parameters based on emotion
        val (pitch, rate, volume) = getEmotionParameters(emotion, emotionScore ?: 0.5f)
        
        Log.d(TAG, "Speaking with emotion: $emotion (score: $emotionScore) - pitch: $pitch, rate: $rate")
        
        // Apply emotion parameters
        tts.setPitch(pitch)
        tts.setSpeechRate(rate)
        
        // Speak with emotion tag for logging
        tts.speak(text, "EMOTION_${emotion.uppercase()}")
    }
    
    /**
     * Get TTS parameters for specific emotion.
     * @return Triple of (pitch, rate, volume)
     */
    private fun getEmotionParameters(emotion: String, score: Float): Triple<Float, Float, Float> {
        // Base parameters
        val basePitch = 1.0f
        val baseRate = 1.0f
        val baseVolume = 1.0f
        
        // Emotion-specific modulation (scaled by confidence score)
        val intensity = score.coerceIn(0.0f, 1.0f)
        
        return when (emotion.lowercase()) {
            "angry" -> Triple(
                basePitch + (0.3f * intensity),  // Higher pitch
                baseRate + (0.2f * intensity),   // Faster rate
                baseVolume + (0.2f * intensity)  // Louder
            )
            "happy" -> Triple(
                basePitch + (0.2f * intensity),  // Slightly higher pitch
                baseRate + (0.15f * intensity),  // Slightly faster
                baseVolume
            )
            "sad" -> Triple(
                basePitch - (0.2f * intensity),  // Lower pitch
                baseRate - (0.15f * intensity),  // Slower rate
                baseVolume - (0.1f * intensity)  // Quieter
            )
            "excited" -> Triple(
                basePitch + (0.25f * intensity), // Higher pitch
                baseRate + (0.3f * intensity),   // Much faster
                baseVolume + (0.15f * intensity) // Louder
            )
            "neutral" -> Triple(basePitch, baseRate, baseVolume)
            else -> Triple(basePitch, baseRate, baseVolume)
        }
    }
    
    /**
     * Play back a voice log with original emotion.
     */
    fun playVoiceLog(voiceLog: SceneDatabase.VoiceLog) {
        speakWithEmotion(voiceLog.transcript, voiceLog.emotion, voiceLog.emotionScore)
    }
    
    /**
     * Play back multiple voice logs sequentially.
     */
    fun playVoiceLogs(voiceLogs: List<SceneDatabase.VoiceLog>, onComplete: () -> Unit = {}) {
        if (voiceLogs.isEmpty()) {
            onComplete()
            return
        }
        
        var currentIndex = 0
        
        fun playNext() {
            if (currentIndex >= voiceLogs.size) {
                onComplete()
                return
            }
            
            val log = voiceLogs[currentIndex]
            currentIndex++
            
            // Speak with emotion and play next on completion
            speakWithEmotion(log.transcript, log.emotion, log.emotionScore)
            
            // Simple delay for next (in production, use TTS completion callback)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                playNext()
            }, (log.transcript.length * 50L).coerceAtLeast(1000L)) // Rough estimate
        }
        
        playNext()
    }
}

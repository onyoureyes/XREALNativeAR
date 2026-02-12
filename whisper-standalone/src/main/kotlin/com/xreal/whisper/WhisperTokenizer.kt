package com.xreal.whisper

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

class WhisperTokenizer(private val context: Context) {
    private val TAG = "WhisperTokenizer"
    private var vocab: Map<Int, String>? = null

    init {
        loadVocab()
    }

    private fun loadVocab() {
        try {
            context.assets.open("vocab.json").use { stream ->
                val json = JSONObject(stream.bufferedReader().use { it.readText() })
                val map = mutableMapOf<Int, String>()
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    map[json.getInt(key)] = key
                }
                vocab = map
            }
        } catch (e: Exception) {
            Log.e(TAG, "Vocab Load Error: ${e.message}")
        }
    }

    fun decode(tokens: IntArray): String {
        val sb = StringBuilder()
        for (tokenId in tokens) {
            if (tokenId == 50257) break // EOT
            val word = vocab?.get(tokenId) ?: ""
            sb.append(word)
        }
        
        val result = sb.toString().replace("Ġ", " ").trim()
        
        // Hallucination filter
        val hallucinations = setOf("you", "oh", "one", "transcript", "by", "thank", "subtitles", "thanks")
        if (hallucinations.contains(result.lowercase()) || result.length < 2) {
            return ""
        }
        return result
    }
}

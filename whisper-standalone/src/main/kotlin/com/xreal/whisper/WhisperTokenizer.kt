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
        val lower = result.lowercase()

        // ★ 1단계: 너무 짧은 결과 필터
        if (result.length < 3) return ""

        // ★ 2단계: 단일 단어 환각 필터
        val hallucinationWords = setOf(
            "you", "oh", "one", "transcript", "by", "thank", "subtitles", "thanks",
            "the", "a", "and", "or", "is", "it", "ok", "okay", "yes", "no", "hmm",
            "uh", "um", "ah", "er", "huh", "wow", "hey", "hi"
        )
        if (hallucinationWords.contains(lower)) return ""

        // ★ 3단계: 알려진 Whisper 환각 구문 필터 (문자열 포함 여부로 검사)
        val hallucinationPhrases = listOf(
            "you can also use the same method",
            "you can use the same method",
            "thank you for watching",
            "thanks for watching",
            "please subscribe",
            "don't forget to subscribe",
            "like and subscribe",
            "subtitles by",
            "transcript by",
            "transcribed by",
            "translated by",
            "captions by",
            "www.",
            ".com",
            "copyright",
            "all rights reserved",
            "this is a test",
            "thank you very much",
            "have a great day",
            "have a nice day",
            "see you next time",
            "i'll see you",
            "we'll see you",
            "music playing",
            "[music]",
            "(music)",
            "[applause]",
            "(applause)",
            "♪",
            "♫",
            "this video",
            "in this video",
            "in this tutorial",
            "today we're going to",
            "welcome back",
            "welcome to",
            "ladies and gentlemen"
        )
        for (phrase in hallucinationPhrases) {
            if (lower.contains(phrase)) {
                Log.d("WhisperTokenizer", "환각 구문 필터링: \"$result\"")
                return ""
            }
        }

        // ★ 4단계: 특수 토큰만으로 구성된 결과 필터 (언어/타임스탬프 토큰만 남은 경우)
        if (result.startsWith("<|") || result.all { !it.isLetterOrDigit() }) return ""

        return result
    }
}

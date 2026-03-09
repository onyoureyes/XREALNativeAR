package com.xreal.nativear.companion

import android.util.Log

/**
 * AnalysisCacheManager: Caches recent AI analysis results to avoid duplicate processing.
 *
 * When the same scene (same objects in same location) is detected repeatedly,
 * returns cached results instead of making expensive AI calls.
 *
 * Scene hash = sorted label set + quantized location → consistent key
 * Cache TTL: 30 minutes for scenes, 24 hours for object identification
 */
class AnalysisCacheManager {

    companion object {
        private const val TAG = "AnalysisCacheManager"
        // ★ Policy Department: PolicyRegistry shadow read
        private val MAX_CACHE_SIZE: Int get() =
            com.xreal.nativear.policy.PolicyReader.getInt("capacity.analysis_cache_size", 100)
        private val SCENE_CACHE_TTL_MS: Long get() =
            com.xreal.nativear.policy.PolicyReader.getLong("capacity.scene_cache_ttl_ms", 30 * 60 * 1000L)
        private val OBJECT_CACHE_TTL_MS: Long get() =
            com.xreal.nativear.policy.PolicyReader.getLong("capacity.object_cache_ttl_ms", 24 * 60 * 60 * 1000L)
    }

    // LRU cache: sceneHash → CachedAnalysis (access-order)
    private val recentAnalyses = object : LinkedHashMap<String, CachedAnalysis>(
        MAX_CACHE_SIZE, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedAnalysis>?): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }

    // Track previous scene labels for change detection
    private var previousSceneLabels: Set<String> = emptySet()
    private var previousSceneHash: String = ""

    // ─── Scene Hash ───

    fun computeSceneHash(labels: Set<String>, lat: Double?, lon: Double?): String {
        val sortedLabels = labels.sorted().joinToString(",")
        val quantizedLat = lat?.let { String.format("%.4f", it) } ?: "null"
        val quantizedLon = lon?.let { String.format("%.4f", it) } ?: "null"
        return "$sortedLabels@$quantizedLat,$quantizedLon".hashCode().toString(16)
    }

    // ─── Cache Operations ───

    fun getCachedAnalysis(sceneHash: String): CachedAnalysis? {
        val cached = synchronized(recentAnalyses) { recentAnalyses[sceneHash] }
        if (cached != null && !cached.isExpired) {
            cacheHits++
            Log.d(TAG, "Cache HIT: $sceneHash")
            return cached
        }
        cacheMisses++
        if (cached != null) {
            synchronized(recentAnalyses) { recentAnalyses.remove(sceneHash) }
        }
        return null
    }

    fun cacheAnalysis(sceneHash: String, analysis: CachedAnalysis) {
        synchronized(recentAnalyses) {
            recentAnalyses[sceneHash] = analysis
        }
        Log.d(TAG, "Cached analysis: $sceneHash (${analysis.labels.size} labels)")
    }

    // ─── Scene Change Detection ───

    fun detectSceneChange(currentLabels: Set<String>): SceneChangeResult {
        if (previousSceneLabels.isEmpty()) {
            previousSceneLabels = currentLabels
            return SceneChangeResult.NEW_SCENE
        }

        val intersection = currentLabels.intersect(previousSceneLabels)
        val union = currentLabels.union(previousSceneLabels)

        if (union.isEmpty()) {
            previousSceneLabels = currentLabels
            return SceneChangeResult.NEW_SCENE
        }

        val similarity = intersection.size.toFloat() / union.size.toFloat()

        val result = when {
            similarity >= 0.95f -> SceneChangeResult.UNCHANGED
            similarity >= 0.7f -> SceneChangeResult.MINOR_CHANGE
            similarity >= 0.5f -> SceneChangeResult.MAJOR_CHANGE
            else -> SceneChangeResult.NEW_SCENE
        }

        previousSceneLabels = currentLabels
        return result
    }

    fun getChangedLabels(currentLabels: Set<String>): Set<String> {
        return currentLabels - previousSceneLabels
    }

    // ─── Statistics ───

    fun getCacheSize(): Int = synchronized(recentAnalyses) { recentAnalyses.size }

    private var cacheHits = 0
    private var cacheMisses = 0

    fun getCacheHitRate(): Pair<Int, Int> {
        return cacheHits to (cacheHits + cacheMisses)
    }

    fun clearExpired() {
        synchronized(recentAnalyses) {
            val expired = recentAnalyses.filter { it.value.isExpired }.keys.toList()
            expired.forEach { recentAnalyses.remove(it) }
            if (expired.isNotEmpty()) {
                Log.d(TAG, "Cleared ${expired.size} expired cache entries")
            }
        }
    }

    fun clear() {
        synchronized(recentAnalyses) { recentAnalyses.clear() }
        previousSceneLabels = emptySet()
        previousSceneHash = ""
    }
}

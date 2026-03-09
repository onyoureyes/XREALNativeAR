package com.xreal.nativear.session

import android.util.Log
import com.xreal.nativear.UnifiedMemoryDatabase
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent

/**
 * LifeSessionManager — StorytellerOrchestrator 위임 래퍼.
 *
 * ## 방안 B 리팩토링
 * 이전: 독립 세션 생명주기 관리 (30분 비활성 타이머, DB 연동)
 * 현재: StorytellerOrchestrator의 Chapter 개념에 위임
 *       → AIAgentManager, PersonaManager, MemorySaveHelper가 기존 API 유지
 *
 * ## 음성 명령 (AIAgentManager에서 감지, 기존과 동일)
 * - "세션 시작" / "새 세션 [상황]" → storyteller.startSession(situation)
 * - "세션 종료" → storyteller.endSession()
 * - "세션 요약" → storyteller.getSessionSummaryPrompt()
 */
class LifeSessionManager(
    private val database: UnifiedMemoryDatabase,
    private val eventBus: GlobalEventBus
) {
    companion object {
        private const val TAG = "LifeSessionManager"
    }

    // Storyteller lazy inject (순환 의존 방지)
    private val storyteller: com.xreal.nativear.storyteller.StorytellerOrchestrator? by lazy {
        try { org.koin.java.KoinJavaComponent.getKoin().getOrNull() } catch (_: Exception) { null }
    }

    /** 호환용 — PersonaManager에서 currentSession.durationMinutes 참조 */
    val currentSession: CompatSession?
        get() {
            val st = storyteller ?: return null
            val sessionId = st.currentSessionId ?: return null
            return CompatSession(
                id = sessionId,
                situation = st.currentChapterSituation,
                durationMinutes = st.currentSessionMinutes
            )
        }

    val currentSessionId: String?
        get() = storyteller?.currentSessionId

    fun startSession(situation: String? = null) {
        storyteller?.startSession(situation)
            ?: Log.w(TAG, "Storyteller 미초기화 — 세션 시작 무시")
    }

    fun endSession(generateSummary: Boolean = false) {
        storyteller?.endSession(generateSummary)
            ?: Log.w(TAG, "Storyteller 미초기화 — 세션 종료 무시")
    }

    fun updateLastActivity() {
        storyteller?.updateLastActivity()
    }

    fun getSessionSummaryPrompt(): String? {
        return storyteller?.getSessionSummaryPrompt()
    }

    fun getRecentSessions(days: Int = 7): List<LifeSession> {
        // DB 조회는 유지 (기존 데이터 호환)
        return try {
            database.getRecentLifeSessions(days.toLong() * 24 * 3600 * 1000L)
        } catch (e: Exception) {
            Log.w(TAG, "getRecentSessions failed: ${e.message}")
            emptyList()
        }
    }

    fun start() {
        Log.d(TAG, "LifeSessionManager started (→ StorytellerOrchestrator 위임)")
    }

    fun stop() {
        Log.d(TAG, "LifeSessionManager stopped")
    }

    /** PersonaManager 호환용 최소 세션 데이터 */
    data class CompatSession(
        val id: String,
        val situation: String?,
        val durationMinutes: Long
    )
}

package com.xreal.nativear.monitoring

import android.util.Log
import com.xreal.nativear.UnifiedMemoryDatabase
import com.xreal.nativear.core.GlobalEventBus
import kotlin.coroutines.cancellation.CancellationException
import com.xreal.nativear.core.XRealEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * SystemErrorLogger — SystemEvent.Error를 수신하여 DB에 자동 저장하는 에러 추적 서비스.
 *
 * ## 역할
 * - GlobalEventBus에서 SystemEvent.Error 구독
 * - UnifiedMemoryDatabase.insertErrorLog()로 영속화
 * - 반복 에러 패턴 감지 → CapabilityManager에 BUG_REPORT 자동 제출 (10분 내 동일 에러 3회 이상)
 *
 * ## 설계 원칙 (CLAUDE.md Rule 5)
 * - EventBus 구독 패턴: start()/stop()으로 구독 생명주기 관리
 * - Koin singleton으로 등록 (AppModule.kt)
 * - AppBootstrapper에서 start()/stop() 호출
 */
class SystemErrorLogger(
    private val eventBus: GlobalEventBus,
    private val database: UnifiedMemoryDatabase
) {
    private val TAG = "SystemErrorLogger"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 반복 에러 감지: (errorCode → 타임스탬프 목록)
    private val recentErrorTimestamps = mutableMapOf<String, MutableList<Long>>()
    private val REPEAT_WINDOW_MS = 10 * 60 * 1000L  // 10분
    private val REPEAT_THRESHOLD = 3                  // 3회 이상 = BUG_REPORT 대상

    fun start() {
        scope.launch {
            eventBus.events.collect { event ->
                try {
                    if (event is XRealEvent.SystemEvent.Error) {
                        handleError(event)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "에러 로깅 처리 오류 (루프 유지됨): ${e.message}", e)
                }
            }
        }
        Log.i(TAG, "SystemErrorLogger started — DB 에러 추적 활성")
    }

    private fun handleError(error: XRealEvent.SystemEvent.Error) {
        // 1. DB에 저장
        val stackTrace = error.throwable?.let {
            it.stackTraceToString().take(2000)
        }
        val rowId = database.insertErrorLog(
            errorCode = error.code,
            message = error.message,
            stackTrace = stackTrace,
            component = error.code.split("_").firstOrNull() ?: "UNKNOWN"
        )

        if (rowId < 0) {
            Log.w(TAG, "에러 로그 DB 저장 실패: ${error.code}")
            return
        }

        Log.d(TAG, "에러 저장됨 (rowId=$rowId): ${error.code} — ${error.message.take(60)}")

        // 2. 반복 에러 감지
        val now = System.currentTimeMillis()
        val timestamps = recentErrorTimestamps.getOrPut(error.code) { mutableListOf() }
        timestamps.add(now)
        // 10분 초과 항목 제거
        timestamps.removeAll { now - it > REPEAT_WINDOW_MS }

        if (timestamps.size >= REPEAT_THRESHOLD) {
            Log.w(TAG, "⚠️ 반복 에러 감지: ${error.code} (${timestamps.size}회/${REPEAT_WINDOW_MS / 60_000}분)")
            timestamps.clear()  // 중복 리포트 방지

            // 3. CapabilityManager에 BUG_REPORT 자동 제출
            try {
                val koin = org.koin.java.KoinJavaComponent.getKoin()
                val capabilityManager = koin.getOrNull<com.xreal.nativear.evolution.CapabilityManager>()
                capabilityManager?.submitRequest(
                    com.xreal.nativear.evolution.CapabilityRequest(
                        title = "[자동 감지] 반복 에러: ${error.code}",
                        description = "${REPEAT_THRESHOLD}회/${REPEAT_WINDOW_MS / 60_000}분 이내 반복 발생. " +
                            "최근 메시지: ${error.message.take(150)}",
                        requestingExpertId = "system_error_logger",
                        type = com.xreal.nativear.evolution.CapabilityType.BUG_REPORT,
                        priority = com.xreal.nativear.evolution.RequestPriority.HIGH
                    )
                )
                Log.i(TAG, "BUG_REPORT 자동 제출됨: ${error.code}")
            } catch (e: Exception) {
                Log.w(TAG, "BUG_REPORT 제출 실패 (CapabilityManager 없음): ${e.message}")
            }
        }
    }

    fun stop() {
        scope.cancel()
        Log.i(TAG, "SystemErrorLogger stopped")
    }
}

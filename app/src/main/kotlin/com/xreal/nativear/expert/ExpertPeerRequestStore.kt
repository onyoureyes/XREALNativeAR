package com.xreal.nativear.expert

import android.content.ContentValues
import android.util.Log
import com.xreal.nativear.UnifiedMemoryDatabase
import com.xreal.nativear.core.ErrorReporter
import java.util.concurrent.ConcurrentHashMap

/**
 * ExpertPeerRequestStore — 전문가 AI 역량 강화 요청의 저장소.
 *
 * - 미결(PENDING) 요청은 메모리 캐시 유지 (빠른 접근)
 * - 모든 상태 변경은 expert_peer_requests 테이블에 영속
 * - StrategistService가 피크타임 외에 getPendingRequests()로 일괄 처리
 */
class ExpertPeerRequestStore(
    private val database: UnifiedMemoryDatabase
) {
    private val TAG = "ExpertPeerRequestStore"

    // PENDING 요청 메모리 캐시 (빠른 접근)
    private val pendingCache = ConcurrentHashMap<String, ExpertPeerRequest>()

    init {
        // 앱 재시작 시 미결 요청 복원
        loadPendingFromDb()
    }

    // ─── 제출 ───

    /**
     * 새 요청 제출. 중복 방지: 동일 expert + type + content 조합이 PENDING이면 스킵.
     */
    fun submitRequest(request: ExpertPeerRequest): String {
        // 중복 체크
        if (hasDuplicatePending(request)) {
            Log.d(TAG, "중복 요청 스킵: ${request.requestingExpertId} / ${request.requestType}")
            return "DUPLICATE"
        }

        try {
            val db = database.writableDatabase
            val values = ContentValues().apply {
                put("id", request.id)
                put("requesting_expert_id", request.requestingExpertId)
                put("request_type", request.requestType.name)
                put("content", request.content)
                put("rationale", request.rationale)
                put("situation", request.situation)
                put("status", request.status.name)
                put("created_at", request.createdAt)
                put("expires_at", request.expiresAt)
            }
            db.insert("expert_peer_requests", null, values)
            pendingCache[request.id] = request
            Log.i(TAG, "요청 저장됨 (PENDING) — ${request.requestingExpertId}: ${request.requestType.displayName}")
            return request.id
        } catch (e: Exception) {
            ErrorReporter.report(TAG, "요청 저장 실패 (${request.requestType.name})", e)
            return "ERROR"
        }
    }

    // ─── 상태 변경 ───

    fun approveRequest(id: String, notes: String, approvedContent: String? = null) {
        updateStatus(id, ExpertRequestStatus.APPROVED, notes, approvedContent)
        pendingCache.remove(id)
    }

    fun approveModified(id: String, notes: String, modifiedContent: String) {
        updateStatus(id, ExpertRequestStatus.MODIFIED, notes, modifiedContent)
        pendingCache.remove(id)
    }

    fun rejectRequest(id: String, reason: String) {
        updateStatus(id, ExpertRequestStatus.REJECTED, reason, null)
        pendingCache.remove(id)
    }

    fun revokeRequest(id: String, reason: String) {
        updateStatus(id, ExpertRequestStatus.REVOKED, reason, null)
        pendingCache.remove(id)
    }

    fun updateOutcomeScore(id: String, score: Float) {
        try {
            val db = database.writableDatabase
            val values = ContentValues().apply { put("outcome_score", score) }
            db.update("expert_peer_requests", values, "id = ?", arrayOf(id))
        } catch (e: Exception) {
            Log.w(TAG, "outcome_score 업데이트 실패: ${e.message}")
        }
    }

    // ─── 조회 ───

    fun getPendingRequests(): List<ExpertPeerRequest> = pendingCache.values.toList()

    fun getApprovedRequests(expertId: String? = null): List<ExpertPeerRequest> {
        return queryByStatus(listOf(ExpertRequestStatus.APPROVED, ExpertRequestStatus.MODIFIED), expertId)
    }

    /**
     * StrategistService 반성 주기에서 읽는 최근 요청 결과 요약.
     * 승인 후 효과 데이터 포함 → Gemini가 연장/취소 결정에 활용.
     */
    fun getRecentRequestSummary(): String? {
        val cutoff = System.currentTimeMillis() - 7 * 24 * 3600_000L  // 7일
        return try {
            val db = database.readableDatabase
            val cursor = db.rawQuery("""
                SELECT requesting_expert_id, request_type, status, reviewer_notes, outcome_score, expires_at
                FROM expert_peer_requests
                WHERE created_at > ? AND status NOT IN ('PENDING', 'EXPIRED')
                ORDER BY created_at DESC
                LIMIT 30
            """.trimIndent(), arrayOf(cutoff.toString()))

            if (!cursor.moveToFirst()) { cursor.close(); return null }

            val sb = StringBuilder()
            sb.appendLine("[전문가 요청 결과 — 최근 7일 (PeerRequestReviewer)]")
            var count = 0
            do {
                val expertId = cursor.getString(0)
                val type = cursor.getString(1)
                val status = cursor.getString(2)
                val notes = cursor.getString(3)?.take(80) ?: ""
                val score = if (cursor.isNull(4)) null else cursor.getFloat(4)
                val expires = if (cursor.isNull(5)) "무기한" else {
                    val remainMs = cursor.getLong(5) - System.currentTimeMillis()
                    if (remainMs > 0) "${remainMs / 3600_000}h 남음" else "만료"
                }
                val scoreStr = score?.let { " 효과: ${String.format("%.2f", it)}" } ?: ""
                sb.appendLine("  $expertId [$type] → $status$scoreStr ($expires) - $notes")
                count++
            } while (cursor.moveToNext())
            cursor.close()

            if (count == 0) null else sb.toString()
        } catch (e: Exception) {
            Log.w(TAG, "getRecentRequestSummary 실패: ${e.message}")
            null
        }
    }

    // ─── 내부 헬퍼 ───

    private fun updateStatus(id: String, status: ExpertRequestStatus, notes: String?, approvedContent: String?) {
        try {
            val db = database.writableDatabase
            val values = ContentValues().apply {
                put("status", status.name)
                put("reviewer_notes", notes)
                if (approvedContent != null) put("approved_content", approvedContent)
                if (status in listOf(ExpertRequestStatus.APPROVED, ExpertRequestStatus.MODIFIED)) {
                    put("applied_at", System.currentTimeMillis())
                }
            }
            db.update("expert_peer_requests", values, "id = ?", arrayOf(id))
            Log.i(TAG, "요청 상태 업데이트: $id → ${status.displayName}")
        } catch (e: Exception) {
            Log.w(TAG, "상태 업데이트 실패 ($id): ${e.message}")
        }
    }

    private fun hasDuplicatePending(request: ExpertPeerRequest): Boolean {
        return pendingCache.values.any {
            it.requestingExpertId == request.requestingExpertId &&
            it.requestType == request.requestType &&
            it.content.take(50) == request.content.take(50)
        }
    }

    private fun queryByStatus(statuses: List<ExpertRequestStatus>, expertId: String?): List<ExpertPeerRequest> {
        val results = mutableListOf<ExpertPeerRequest>()
        try {
            val db = database.readableDatabase
            val statusPlaceholders = statuses.joinToString(",") { "?" }
            val baseQuery = if (expertId != null)
                "SELECT * FROM expert_peer_requests WHERE status IN ($statusPlaceholders) AND requesting_expert_id = ? ORDER BY created_at DESC LIMIT 50"
            else
                "SELECT * FROM expert_peer_requests WHERE status IN ($statusPlaceholders) ORDER BY created_at DESC LIMIT 50"
            val args = if (expertId != null)
                (statuses.map { it.name } + listOf(expertId)).toTypedArray()
            else
                statuses.map { it.name }.toTypedArray()

            val cursor = db.rawQuery(baseQuery, args)
            while (cursor.moveToNext()) {
                results.add(cursorToRequest(cursor))
            }
            cursor.close()
        } catch (e: Exception) {
            Log.w(TAG, "queryByStatus 실패: ${e.message}")
        }
        return results
    }

    private fun loadPendingFromDb() {
        try {
            val db = database.readableDatabase
            val cursor = db.rawQuery(
                "SELECT * FROM expert_peer_requests WHERE status = 'PENDING' ORDER BY created_at ASC LIMIT 50",
                null
            )
            while (cursor.moveToNext()) {
                val req = cursorToRequest(cursor)
                pendingCache[req.id] = req
            }
            cursor.close()
            if (pendingCache.isNotEmpty()) {
                Log.i(TAG, "미결 요청 ${pendingCache.size}개 복원됨")
            }
        } catch (e: Exception) {
            Log.w(TAG, "미결 요청 복원 실패: ${e.message}")
        }
    }

    private fun cursorToRequest(cursor: android.database.Cursor): ExpertPeerRequest {
        return ExpertPeerRequest(
            id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
            requestingExpertId = cursor.getString(cursor.getColumnIndexOrThrow("requesting_expert_id")),
            requestType = try {
                ExpertRequestType.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("request_type")))
            } catch (_: Exception) { ExpertRequestType.PROMPT_ADDITION },
            content = cursor.getString(cursor.getColumnIndexOrThrow("content")),
            rationale = cursor.getString(cursor.getColumnIndexOrThrow("rationale")),
            situation = cursor.getString(cursor.getColumnIndexOrThrow("situation")),
            status = try {
                ExpertRequestStatus.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("status")))
            } catch (_: Exception) { ExpertRequestStatus.PENDING },
            reviewerNotes = cursor.getString(cursor.getColumnIndexOrThrow("reviewer_notes")),
            approvedContent = cursor.getString(cursor.getColumnIndexOrThrow("approved_content")),
            appliedAt = cursor.getLong(cursor.getColumnIndexOrThrow("applied_at")).takeIf { it != 0L },
            expiresAt = cursor.getLong(cursor.getColumnIndexOrThrow("expires_at")).takeIf { it != 0L },
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
            outcomeScore = if (cursor.isNull(cursor.getColumnIndexOrThrow("outcome_score"))) null
                           else cursor.getFloat(cursor.getColumnIndexOrThrow("outcome_score"))
        )
    }
}

package com.xreal.nativear.evolution

import android.content.ContentValues
import android.util.Log
import com.xreal.nativear.UnifiedMemoryDatabase
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent
import kotlinx.coroutines.*

/**
 * CapabilityManager: Manages AI capability requests lifecycle.
 *
 * Handles the full flow: submit -> notify user -> approve/reject -> track implementation.
 * All requests are persisted to DB for history and analysis.
 */
class CapabilityManager(
    private val database: UnifiedMemoryDatabase,
    private val eventBus: GlobalEventBus,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "CapabilityManager"
        private const val TABLE = "capability_requests"
    }

    // ─── Submit Request (called by AI via request_capability tool) ───

    fun submitRequest(request: CapabilityRequest): String {
        // Check for duplicate
        if (hasSimilarRequest(request.title)) {
            Log.d(TAG, "Similar request already exists: ${request.title}")
            return "DUPLICATE"
        }

        try {
            val db = database.writableDatabase
            val values = requestToContentValues(request)
            db.insertWithOnConflict(TABLE, null, values,
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)

            Log.i(TAG, "📋 Capability request: ${request.id} — \"${request.title}\" by ${request.requestingExpertId}")

            // Notify user via TTS + HUD
            scope.launch {
                eventBus.publish(XRealEvent.ActionRequest.SpeakTTS(
                    "${request.requestingExpertId}가 새 기능을 요청했습니다: ${request.title}. 승인하시겠습니까?"
                ))
                eventBus.publish(XRealEvent.SystemEvent.DebugLog(
                    "📋 역량 요청: ${request.title} (${request.requestingExpertId})"
                ))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to submit request: ${e.message}")
        }

        return request.id
    }

    // ─── User Actions ───

    fun approveRequest(id: String, notes: String? = null) {
        updateStatus(id, RequestStatus.APPROVED, notes)
        Log.i(TAG, "✅ Request approved: $id")
        scope.launch {
            eventBus.publish(XRealEvent.SystemEvent.DebugLog("✅ 역량 요청 승인: $id"))
        }
    }

    fun rejectRequest(id: String, reason: String? = null) {
        updateStatus(id, RequestStatus.REJECTED, reason)
        Log.i(TAG, "❌ Request rejected: $id")
    }

    fun deferRequest(id: String) {
        updateStatus(id, RequestStatus.DEFERRED)
        Log.i(TAG, "⏸️ Request deferred: $id")
    }

    // ─── Status Management ───

    fun updateStatus(id: String, status: RequestStatus, notes: String? = null) {
        try {
            val db = database.writableDatabase
            val values = ContentValues().apply {
                put("status", status.ordinal)
                notes?.let { put("implementation_notes", it) }
                if (status == RequestStatus.VERIFIED || status == RequestStatus.REJECTED) {
                    put("resolved_at", System.currentTimeMillis())
                }
            }
            db.update(TABLE, values, "id = ?", arrayOf(id))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update status: ${e.message}")
        }
    }

    // ─── Query API ───

    fun getRequestsByStatus(status: RequestStatus): List<CapabilityRequest> {
        return queryRequests("status = ?", arrayOf(status.ordinal.toString()))
    }

    fun getPendingRequests(): List<CapabilityRequest> {
        return getRequestsByStatus(RequestStatus.PENDING)
    }

    fun getApprovedRequests(): List<CapabilityRequest> {
        return getRequestsByStatus(RequestStatus.APPROVED)
    }

    fun getAllRequests(limit: Int = 50): List<CapabilityRequest> {
        return queryRequests(null, null, limit)
    }

    fun getRequest(id: String): CapabilityRequest? {
        val results = queryRequests("id = ?", arrayOf(id), 1)
        return results.firstOrNull()
    }

    fun hasSimilarRequest(title: String): Boolean {
        return try {
            val db = database.readableDatabase
            val cursor = db.rawQuery(
                "SELECT COUNT(*) FROM $TABLE WHERE title = ? AND status IN (0, 1, 2)",
                arrayOf(title))
            cursor.use {
                it.moveToFirst() && it.getInt(0) > 0
            }
        } catch (_: Exception) { false }
    }

    // ─── Statistics ───

    fun getRequestStats(): RequestStats {
        return try {
            val db = database.readableDatabase
            val cursor = db.rawQuery("""
                SELECT
                    COUNT(*),
                    SUM(CASE WHEN status = 0 THEN 1 ELSE 0 END),
                    SUM(CASE WHEN status = 1 THEN 1 ELSE 0 END),
                    SUM(CASE WHEN status = 2 THEN 1 ELSE 0 END),
                    SUM(CASE WHEN status = 3 OR status = 4 THEN 1 ELSE 0 END),
                    SUM(CASE WHEN status = 5 THEN 1 ELSE 0 END),
                    SUM(CASE WHEN status = 6 THEN 1 ELSE 0 END)
                FROM $TABLE
            """.trimIndent(), null)
            cursor.use {
                if (it.moveToFirst()) {
                    RequestStats(
                        total = it.getInt(0),
                        pending = it.getInt(1),
                        approved = it.getInt(2),
                        inProgress = it.getInt(3),
                        implemented = it.getInt(4),
                        rejected = it.getInt(5),
                        deferred = it.getInt(6)
                    )
                } else RequestStats()
            }
        } catch (_: Exception) { RequestStats() }
    }

    // ─── Helpers ───

    private fun queryRequests(
        selection: String?,
        selectionArgs: Array<String>?,
        limit: Int = 50
    ): List<CapabilityRequest> {
        return try {
            val db = database.readableDatabase
            val cursor = db.query(TABLE, null, selection, selectionArgs,
                null, null, "timestamp DESC", "$limit")
            val list = mutableListOf<CapabilityRequest>()
            cursor.use {
                while (it.moveToNext()) {
                    list.add(cursorToRequest(it))
                }
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "Query failed: ${e.message}")
            emptyList()
        }
    }

    private fun requestToContentValues(request: CapabilityRequest): ContentValues {
        return ContentValues().apply {
            put("id", request.id)
            put("timestamp", request.timestamp)
            put("requesting_expert_id", request.requestingExpertId)
            put("requesting_domain_id", request.requestingDomainId)
            put("type", request.type.ordinal)
            put("title", request.title)
            put("description", request.description)
            put("current_limitation", request.currentLimitation)
            put("expected_benefit", request.expectedBenefit)
            put("priority", request.priority.ordinal)
            put("situation", request.situation)
            put("status", request.status.ordinal)
            put("user_response", request.userResponse)
            put("implementation_notes", request.implementationNotes)
            put("resolved_at", request.resolvedAt)
        }
    }

    private fun cursorToRequest(cursor: android.database.Cursor): CapabilityRequest {
        return CapabilityRequest(
            id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
            timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
            requestingExpertId = cursor.getString(cursor.getColumnIndexOrThrow("requesting_expert_id")),
            requestingDomainId = cursor.getString(cursor.getColumnIndexOrThrow("requesting_domain_id")),
            type = CapabilityType.entries.getOrElse(
                cursor.getInt(cursor.getColumnIndexOrThrow("type"))
            ) { CapabilityType.NEW_TOOL },
            title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
            description = cursor.getString(cursor.getColumnIndexOrThrow("description")),
            currentLimitation = cursor.getString(cursor.getColumnIndexOrThrow("current_limitation")),
            expectedBenefit = cursor.getString(cursor.getColumnIndexOrThrow("expected_benefit")),
            priority = RequestPriority.entries.getOrElse(
                cursor.getInt(cursor.getColumnIndexOrThrow("priority"))
            ) { RequestPriority.NORMAL },
            situation = cursor.getString(cursor.getColumnIndexOrThrow("situation")),
            status = RequestStatus.entries.getOrElse(
                cursor.getInt(cursor.getColumnIndexOrThrow("status"))
            ) { RequestStatus.PENDING },
            userResponse = cursor.getString(cursor.getColumnIndexOrThrow("user_response")),
            implementationNotes = cursor.getString(cursor.getColumnIndexOrThrow("implementation_notes")),
            resolvedAt = if (cursor.isNull(cursor.getColumnIndexOrThrow("resolved_at"))) null
                else cursor.getLong(cursor.getColumnIndexOrThrow("resolved_at"))
        )
    }
}

package com.xreal.nativear.plan

import java.util.UUID

/**
 * PlanTypes: Data classes for Todo, Schedule, and Recurrence management.
 *
 * Used by PlanManager for CRUD operations and by PlannerToolExecutor
 * for AI agent access.
 */

// ─── Todo ───

data class TodoItem(
    val id: String = UUID.randomUUID().toString().take(12),
    val title: String,
    val description: String? = null,
    val priority: TodoPriority = TodoPriority.NORMAL,
    val deadline: Long? = null,
    val status: TodoStatus = TodoStatus.PENDING,
    val category: String? = null,
    val assignedExpert: String? = null,
    val createdBy: String? = null,
    val parentGoalId: String? = null,
    val contextTags: List<String> = emptyList(),
    val recurrence: RecurrenceRule? = null,
    val completedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

enum class TodoPriority(val level: Int, val displayName: String) {
    URGENT(0, "긴급"),
    HIGH(1, "높음"),
    NORMAL(2, "보통"),
    LOW(3, "낮음")
}

enum class TodoStatus(val displayName: String) {
    PENDING("대기"),
    IN_PROGRESS("진행 중"),
    COMPLETED("완료"),
    CANCELLED("취소")
}

// ─── Schedule ───

data class ScheduleBlock(
    val id: String = UUID.randomUUID().toString().take(12),
    val title: String,
    val startTime: Long,
    val endTime: Long,
    val type: ScheduleType = ScheduleType.TASK,
    val linkedTodoIds: List<String> = emptyList(),
    val reminder: Long? = null,
    val notes: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

enum class ScheduleType(val displayName: String) {
    MEETING("미팅"),
    TASK("작업"),
    BREAK("휴식"),
    EXERCISE("운동"),
    SOCIAL("모임"),
    CUSTOM("기타")
}

// ─── Recurrence ───

data class RecurrenceRule(
    val pattern: RecurrencePattern,
    val interval: Int = 1,
    val endDate: Long? = null
)

enum class RecurrencePattern {
    DAILY, WEEKDAYS, WEEKLY, MONTHLY, CUSTOM
}

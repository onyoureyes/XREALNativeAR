package com.xreal.nativear.plan

/**
 * IPlanService: 할일/일정 CRUD 및 조회 인터페이스.
 * 구현체: PlanManager
 */
interface IPlanService {
    fun createTodo(todo: TodoItem): TodoItem
    fun completeTodo(todoId: String): Boolean
    fun updateTodoStatus(todoId: String, status: TodoStatus): Boolean
    fun deleteTodo(todoId: String): Boolean
    fun getTodo(todoId: String): TodoItem?
    fun listTodos(status: TodoStatus? = null, category: String? = null, limit: Int = 50): List<TodoItem>
    fun getPendingTodos(): List<TodoItem>
    fun getTodayTodos(): List<TodoItem>
    fun getUpcomingTodoTitles(limit: Int = 5): List<String>
    fun createScheduleBlock(block: ScheduleBlock): ScheduleBlock
    fun getScheduleForDay(timestamp: Long): List<ScheduleBlock>
    fun getTodaySchedule(): List<ScheduleBlock>
    fun getCurrentScheduleBlock(): ScheduleBlock?
    fun getNextScheduleBlock(): ScheduleBlock?
    fun deleteScheduleBlock(blockId: String): Boolean
    fun buildDailySummary(): String
}

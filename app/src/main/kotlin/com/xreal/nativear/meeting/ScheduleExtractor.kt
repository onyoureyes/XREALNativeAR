package com.xreal.nativear.meeting

import android.util.Log
import com.xreal.nativear.ai.AICallGateway
import com.xreal.nativear.ai.AIMessage
import com.xreal.nativear.plan.IPlanService
import com.xreal.nativear.plan.ScheduleBlock
import com.xreal.nativear.plan.ScheduleType
import com.xreal.nativear.plan.TodoItem
import com.xreal.nativear.plan.TodoPriority
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.policy.PolicyReader
import com.xreal.nativear.core.XRealEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.xreal.nativear.ai.IPersonaService
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.regex.Pattern

/**
 * ScheduleExtractor: OCR 텍스트에서 일정/날짜/할일을 추출하여 PlanManager에 저장.
 *
 * ## 동작 흐름
 * 1. OCR 텍스트 수신 (OcrDetected 이벤트 또는 직접 호출)
 * 2. 한국어 날짜 패턴 휴리스틱으로 일정 후보 탐지
 * 3. 후보가 있으면 Gemini로 구조화된 추출 요청
 * 4. JSON 응답 파싱 → PlanManager.createScheduleBlock / createTodo
 *
 * ## 예산 절약
 * - 날짜 패턴이 없는 텍스트는 AI 호출 없이 스킵
 * - 동일 텍스트 중복 추출 방지 (해시 캐시)
 * - Gemini 호출당 ~500-1000 토큰 예상
 */
class ScheduleExtractor(
    private val aiRegistry: com.xreal.nativear.ai.IAICallService,
    private val planManager: IPlanService,
    private val eventBus: GlobalEventBus,
    private val scope: CoroutineScope,
    private val tokenBudgetTracker: com.xreal.nativear.router.persona.TokenBudgetTracker? = null,
    // ★ Phase M: 사용자 직업·역할 컨텍스트 주입용
    private val personaManager: IPersonaService? = null,
    private val cadenceConfig: com.xreal.nativear.cadence.CadenceConfig? = null
) {
    companion object {
        private const val TAG = "ScheduleExtractor"
        private val MAX_CACHE_SIZE: Int get() = PolicyReader.getInt("meeting.schedule_cache_size", 50)
        private const val EXTRACT_COOLDOWN_MS = 30_000L  // 30초 쿨다운
    }

    // 중복 방지: 최근 처리한 텍스트 해시
    private val processedHashes = LinkedHashSet<Int>()
    private var lastExtractionTime = 0L

    // ── 한국어 날짜 패턴 (휴리스틱) ──
    private val datePatterns = listOf(
        // 2026년 3월 5일, 3월 5일, 3/5
        Pattern.compile("(\\d{4}년\\s*)?\\d{1,2}월\\s*\\d{1,2}일"),
        Pattern.compile("\\d{1,2}/\\d{1,2}"),
        Pattern.compile("\\d{1,2}\\.\\d{1,2}"),
        // 다음주, 이번주, 내일, 모레
        Pattern.compile("(다음|이번|저번|지난)\\s*(주|달|월)"),
        Pattern.compile("(내일|모레|오늘|글피)"),
        // 월~금 요일
        Pattern.compile("[월화수목금토일]요일"),
        // 시간: 10:00, 오후 2시, 14시
        Pattern.compile("(오전|오후)?\\s*\\d{1,2}(시|:\\d{2})"),
        // 연간/월간/주간 일정
        Pattern.compile("(연간|월간|주간|일간)\\s*(일정|계획|스케줄)"),
        // ~까지, ~마감
        Pattern.compile("(마감|기한|제출|완료)\\s*(일|까지)?")
    )

    // ── 할일 키워드 ──
    private val todoKeywords = listOf(
        "해야 할", "할 일", "준비물", "제출", "작성", "확인", "검토",
        "보고", "완료", "마감", "처리", "연락", "전달", "수정"
    )

    /**
     * OCR 텍스트를 분석하여 일정/할일 추출.
     * @param ocrTexts OCR에서 감지된 텍스트 리스트
     * @return 추출된 일정/할일 수
     */
    fun extractFromOcr(ocrTexts: List<String>) {
        val now = System.currentTimeMillis()

        // 쿨다운 체크
        val extractCooldown = cadenceConfig?.current?.scheduleExtractCooldownMs ?: EXTRACT_COOLDOWN_MS
        if (now - lastExtractionTime < extractCooldown) return

        val combinedText = ocrTexts.joinToString(" ").trim()
        if (combinedText.length < 10) return  // 너무 짧은 텍스트 무시

        // 중복 체크
        val textHash = combinedText.hashCode()
        if (textHash in processedHashes) return

        // 날짜 패턴 탐지 (휴리스틱)
        val hasDatePattern = datePatterns.any { pattern ->
            pattern.matcher(combinedText).find()
        }
        val hasTodoKeyword = todoKeywords.any { keyword ->
            combinedText.contains(keyword)
        }

        if (!hasDatePattern && !hasTodoKeyword) {
            Log.d(TAG, "No date patterns or todo keywords found, skipping extraction")
            return
        }

        Log.i(TAG, "Date/todo patterns detected, requesting Gemini extraction")
        lastExtractionTime = now

        // 캐시에 추가
        processedHashes.add(textHash)
        if (processedHashes.size > MAX_CACHE_SIZE) {
            processedHashes.iterator().let { iter ->
                if (iter.hasNext()) { iter.next(); iter.remove() }
            }
        }

        scope.launch(Dispatchers.IO) {
            extractWithGemini(combinedText)
        }
    }

    /**
     * Gemini를 사용한 구조화된 일정/할일 추출.
     */
    private suspend fun extractWithGemini(text: String) {
        // Budget gate
        tokenBudgetTracker?.let { tracker ->
            val check = tracker.checkBudget(com.xreal.nativear.ai.ProviderId.GEMINI, estimatedTokens = 1000)
            if (!check.allowed) {
                Log.w(TAG, "Schedule extraction blocked by budget: ${check.reason}")
                return
            }
        }

        // ★ Phase M: 사용자 직업·역할 컨텍스트 주입 — 일정/할일 분류 정확도 향상
        val contextAddendum = try {
            personaManager?.buildContextAddendum("schedule_extractor")?.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
        val contextSection = if (contextAddendum != null) "\n[사용자 컨텍스트 — 직업·역할 참고]\n$contextAddendum\n" else ""

        val today = SimpleDateFormat("yyyy-MM-dd (E)", Locale.KOREAN).format(System.currentTimeMillis())
        val prompt = """
다음 텍스트에서 일정(schedule)과 할일(todo)을 추출하세요.
오늘 날짜: $today
$contextSection
텍스트:
\"\"\"
$text
\"\"\"

JSON 형식으로만 응답하세요. 다른 설명 없이 JSON만:
{
  "schedules": [
    {
      "title": "일정 제목",
      "date": "YYYY-MM-DD",
      "start_time": "HH:mm",
      "end_time": "HH:mm",
      "type": "MEETING|TASK|SOCIAL|CUSTOM",
      "notes": "추가 메모"
    }
  ],
  "todos": [
    {
      "title": "할일 제목",
      "deadline": "YYYY-MM-DD",
      "priority": "URGENT|HIGH|NORMAL|LOW",
      "category": "카테고리"
    }
  ]
}

규칙:
- 날짜가 상대적이면 (다음주 월요일, 내일 등) 오늘 기준으로 절대 날짜 계산
- 시간이 없으면 start_time/end_time은 null
- 추출할 것이 없으면 빈 배열 반환
- deadline이 불명확하면 null
""".trimIndent()

        try {
            val response = aiRegistry.quickText(
                messages = listOf(AIMessage(role = "user", content = prompt)),
                visibility = AICallGateway.VisibilityIntent.INTERNAL_ONLY,
                intent = "schedule_extraction"
            ) ?: return
            val responseText = response.text ?: return
            tokenBudgetTracker?.recordUsage(com.xreal.nativear.ai.ProviderId.GEMINI, (responseText.length / 4).coerceAtLeast(150))

            Log.d(TAG, "Gemini extraction response: ${responseText.take(200)}")

            // JSON 파싱
            val jsonText = extractJsonFromResponse(responseText) ?: return
            val json = JSONObject(jsonText)

            var count = 0

            // 일정 처리
            val schedules = json.optJSONArray("schedules") ?: JSONArray()
            for (i in 0 until schedules.length()) {
                val item = schedules.getJSONObject(i)
                val block = parseScheduleBlock(item)
                if (block != null) {
                    planManager.createScheduleBlock(block)
                    count++
                    Log.i(TAG, "Created schedule: ${block.title} at ${block.startTime}")
                }
            }

            // 할일 처리
            val todos = json.optJSONArray("todos") ?: JSONArray()
            for (i in 0 until todos.length()) {
                val item = todos.getJSONObject(i)
                val todo = parseTodoItem(item)
                if (todo != null) {
                    planManager.createTodo(todo)
                    count++
                    Log.i(TAG, "Created todo: ${todo.title}")
                }
            }

            if (count > 0) {
                eventBus.publish(
                    XRealEvent.SystemEvent.DebugLog(
                        message = "[$TAG] 일정 ${schedules.length()}개, 할일 ${todos.length()}개 추출 완료"
                    )
                )
                Log.i(TAG, "Extraction complete: $count items created")
                // ★ Phase M: 추출 결과를 personaMemory에 저장
                try {
                    val memService = org.koin.java.KoinJavaComponent.getKoin()
                        .getOrNull<com.xreal.nativear.ai.PersonaMemoryService>()
                    memService?.savePersonaMemory(
                        personaId = "schedule_extractor",
                        content = "일정 추출: 일정 ${schedules.length()}개, 할일 ${todos.length()}개 ($today)",
                        role = "AI"
                    )
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini extraction failed: ${e.message}")
        }
    }

    /**
     * 응답에서 JSON 부분만 추출 (```json ... ``` 또는 { ... } 패턴)
     */
    private fun extractJsonFromResponse(response: String): String? {
        // Try extracting from code block
        val codeBlockPattern = Pattern.compile("```(?:json)?\\s*\\n?(\\{[\\s\\S]*?})\\s*\\n?```")
        val matcher = codeBlockPattern.matcher(response)
        if (matcher.find()) return matcher.group(1)

        // Try finding raw JSON object
        val jsonStart = response.indexOf('{')
        val jsonEnd = response.lastIndexOf('}')
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            return response.substring(jsonStart, jsonEnd + 1)
        }

        Log.w(TAG, "Could not extract JSON from response")
        return null
    }

    /**
     * JSON → ScheduleBlock 변환
     */
    private fun parseScheduleBlock(json: JSONObject): ScheduleBlock? {
        return try {
            val title = json.getString("title")
            val dateStr = json.optString("date").takeIf { it.isNotEmpty() && it != "null" } ?: return null
            val startTimeStr = json.optString("start_time").takeIf { it.isNotEmpty() && it != "null" }
            val endTimeStr = json.optString("end_time").takeIf { it.isNotEmpty() && it != "null" }
            val typeStr = json.optString("type", "TASK")
            val notes = json.optString("notes").takeIf { it.isNotEmpty() && it != "null" }

            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val date = dateFormat.parse(dateStr) ?: return null
            val cal = Calendar.getInstance().apply { time = date }

            // 시작 시간 파싱
            val startTime = if (startTimeStr != null) {
                val parts = startTimeStr.split(":")
                cal.set(Calendar.HOUR_OF_DAY, parts[0].toInt())
                cal.set(Calendar.MINUTE, parts.getOrElse(1) { "0" }.toInt())
                cal.timeInMillis
            } else {
                // 시간 없으면 해당 날짜 09:00으로 기본값
                cal.set(Calendar.HOUR_OF_DAY, 9)
                cal.set(Calendar.MINUTE, 0)
                cal.timeInMillis
            }

            // 종료 시간 파싱
            val endTime = if (endTimeStr != null) {
                val parts = endTimeStr.split(":")
                cal.set(Calendar.HOUR_OF_DAY, parts[0].toInt())
                cal.set(Calendar.MINUTE, parts.getOrElse(1) { "0" }.toInt())
                cal.timeInMillis
            } else {
                startTime + 3_600_000L  // 기본 1시간
            }

            val scheduleType = try {
                ScheduleType.valueOf(typeStr)
            } catch (_: Exception) {
                ScheduleType.TASK
            }

            ScheduleBlock(
                title = title,
                startTime = startTime,
                endTime = endTime,
                type = scheduleType,
                notes = notes,
                reminder = startTime - 900_000L  // 15분 전 리마인더
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse schedule: ${e.message}")
            null
        }
    }

    /**
     * JSON → TodoItem 변환
     */
    private fun parseTodoItem(json: JSONObject): TodoItem? {
        return try {
            val title = json.getString("title")
            val deadlineStr = json.optString("deadline").takeIf { it.isNotEmpty() && it != "null" }
            val priorityStr = json.optString("priority", "NORMAL")
            val category = json.optString("category").takeIf { it.isNotEmpty() && it != "null" }

            val deadline = if (deadlineStr != null) {
                try {
                    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                        .parse(deadlineStr)?.time
                } catch (_: Exception) { null }
            } else null

            val priority = try {
                TodoPriority.valueOf(priorityStr)
            } catch (_: Exception) {
                TodoPriority.NORMAL
            }

            TodoItem(
                title = title,
                deadline = deadline,
                priority = priority,
                category = category,
                createdBy = "schedule_extractor",
                contextTags = listOf("meeting", "auto-extracted")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse todo: ${e.message}")
            null
        }
    }

    /**
     * 디버그: 캐시 상태
     */
    fun getStats(): String {
        return "ScheduleExtractor: cached=${processedHashes.size}, lastExtraction=${System.currentTimeMillis() - lastExtractionTime}ms ago"
    }
}

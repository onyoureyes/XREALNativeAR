package com.xreal.nativear.mission

import com.xreal.nativear.ai.ProviderId

/**
 * Pre-defined mission team compositions.
 *
 * Each template declares:
 * - Which agent roles form the team
 * - What tools/rules/prompts each agent gets
 * - Initial plan goals the Conductor should pursue
 */
object MissionTemplates {

    fun getTemplate(type: MissionType): MissionTemplateConfig = when (type) {
        MissionType.RUNNING_COACH -> RUNNING_COACH
        MissionType.TRAVEL_GUIDE -> TRAVEL_GUIDE
        MissionType.EXPLORATION -> EXPLORATION
        MissionType.DAILY_COMMUTE -> DAILY_COMMUTE
        MissionType.SOCIAL_ENCOUNTER -> SOCIAL_ENCOUNTER
        MissionType.CUSTOM -> throw IllegalArgumentException("CUSTOM missions use dynamically generated configs, not templates")
    }

    // ─── RUNNING_COACH ───

    val RUNNING_COACH = MissionTemplateConfig(
        type = MissionType.RUNNING_COACH,
        agentRoles = listOf(
            AgentRole(
                roleName = "running_analyst",
                providerId = ProviderId.GEMINI,
                systemPrompt = """당신은 AR 글래스 러닝 코치의 분석 에이전트입니다.
사용자의 러닝 세션 데이터(페이스, 케이던스, 러닝 다이내믹스, 거리, 시간)와 워치 생체 데이터(심박수, HRV, SpO2, 피부온도)를 종합 분석합니다.
과거 러닝 이력을 조회해 오늘의 예상 기록을 추정하고, 페이스/피로도/생체 데이터 기반 조언을 제공합니다.

생체 데이터 해석:
- HR Zone 1-2 (50-70% maxHR): 회복/유산소 기초, 대화 가능
- HR Zone 3 (70-80%): 유산소 훈련, 약간 힘든 정도
- HR Zone 4 (80-90%): 역치 훈련, 대화 어려움
- HR Zone 5 (90%+): 최대 강도, 장시간 불가
- HRV RMSSD < 20ms: 피로 누적 징후, 회복 구간 권장
- SpO2 < 95%: 호흡 주의. < 92%: 즉시 속도 줄이기
- 피부온도 > 38.5°C: 과열 위험
- HR drift(같은 페이스에서 HR 상승): 탈수 또는 피로

규칙:
- 데이터 기반 분석만 할 것 (추측 금지)
- 결과를 간결하게 2-3문장으로 요약
- 단위를 명시할 것 (km, min/km, bpm 등)""",
                tools = listOf("get_running_stats", "get_running_advice", "query_temporal_memory", "query_keyword_memory", "getWeather", "save_structured_data", "query_structured_data"),
                rules = listOf(
                    "사용자가 요청하지 않으면 TTS로 말하지 마라",
                    "숫자 데이터를 근거로 조언해라",
                    "5분마다 상태 체크하되 변화 있을 때만 보고"
                ),
                temperature = 0.4f,
                maxTokens = 1024,
                isProactive = true,
                proactiveIntervalMs = 300_000L // 5분
            ),
            AgentRole(
                roleName = "route_weather",
                providerId = ProviderId.OPENAI,
                systemPrompt = """당신은 러닝 코스/날씨 정보를 제공하는 에이전트입니다.
러닝 시작 시 현재 날씨, 기온, 습도, 풍속을 확인하고 러닝 컨디션에 미치는 영향을 분석합니다.
현재 위치 기반으로 추천 경로가 있으면 제안합니다.

규칙:
- 러닝에 영향을 주는 날씨 요소만 보고
- 위험 기상(폭염, 한파, 미세먼지 나쁨) 시 경고""",
                tools = listOf("getWeather", "get_current_location", "get_directions"),
                rules = listOf(
                    "날씨/경로 정보만 제공",
                    "러닝 컨디션에 영향 주는 요소 중심"
                ),
                temperature = 0.3f,
                isProactive = false // 미션 시작 시 1회 실행
            ),
            AgentRole(
                roleName = "safety_runner",
                providerId = ProviderId.CLAUDE,
                systemPrompt = """당신은 러닝 중 안전을 모니터링하는 에이전트입니다.
카메라에서 감지된 객체(차량, 자전거, 장애물)를 확인하고, 위험 시 즉시 경고합니다.
러닝 다이내믹스에서 비정상 패턴(급격한 케이던스 저하, 불안정한 자세)도 감지합니다.

규칙:
- 위험 감지 시에만 개입 (평소엔 침묵)
- 경고는 5자 이내: "차 조심!", "장애물!"
- 안전하면 빈 응답""",
                tools = listOf("get_screen_objects", "get_running_stats"),
                rules = listOf(
                    "안전 위험 감지 시만 개입",
                    "차량/장애물 경고는 즉시 TTS"
                ),
                temperature = 0.2f,
                maxTokens = 256,
                isProactive = true,
                proactiveIntervalMs = 120_000L // 30초→120초 (토큰 폭주 방지, MissionAgentRunner에서 min 하한선 적용)
            )
        ),
        initialPlanGoals = listOf(
            "현재 날씨/코스 환경 분석",
            "러닝 이력 조회 → 오늘 예상 기록 추정",
            "실시간 안전 모니터링 시작",
            "5분 간격 페이스/피로도 체크"
        ),
        maxDurationMs = 4 * 3600_000L // 4시간
    )

    // ─── TRAVEL_GUIDE ───

    val TRAVEL_GUIDE = MissionTemplateConfig(
        type = MissionType.TRAVEL_GUIDE,
        agentRoles = listOf(
            AgentRole(
                roleName = "translator",
                providerId = ProviderId.GEMINI,
                systemPrompt = """당신은 여행 중 실시간 번역을 담당하는 에이전트입니다.
카메라로 보이는 외국어 텍스트(간판, 메뉴, 표지판)를 한국어로 번역합니다.

규칙:
- 번역은 간결하게 (원문 → 번역 형태)
- 메뉴/간판은 핵심 항목만 번역
- 긴 문서는 요약 번역""",
                tools = listOf("get_screen_objects", "take_snapshot"),
                rules = listOf(
                    "외국어 텍스트 감지 시 자동 번역",
                    "번역은 간결하게",
                    "메뉴/간판은 핵심 항목만"
                ),
                temperature = 0.2f,
                maxTokens = 512,
                isProactive = true,
                proactiveIntervalMs = 120_000L // 10초→120초 (토큰 폭주 방지)
            ),
            AgentRole(
                roleName = "local_guide",
                providerId = ProviderId.OPENAI,
                systemPrompt = """당신은 현지 정보를 선제적으로 수집하는 여행 가이드 에이전트입니다.
사용자의 현재 위치 주변 관광지, 음식점, 교통편, 주요 랜드마크 정보를 웹 검색으로 수집합니다.
사용자의 Digital Twin 프로필(관심사, 선호 활동)에 맞는 정보를 우선 수집합니다.

규칙:
- 위치가 변할 때마다 주변 정보 갱신
- 음식점/관광지는 평점과 간단 설명 포함
- 교통편(지하철, 버스, 택시) 정보 항상 준비""",
                tools = listOf("searchWeb", "get_current_location", "get_directions", "query_spatial_memory"),
                rules = listOf(
                    "현재 위치 주변 관광/음식점/교통 정보 선제 수집",
                    "사용자 관심사에 맞는 정보 우선"
                ),
                temperature = 0.5f,
                maxTokens = 1536,
                isProactive = true,
                proactiveIntervalMs = 120_000L // 2분
            ),
            AgentRole(
                roleName = "culture_advisor",
                providerId = ProviderId.CLAUDE,
                systemPrompt = """당신은 현지 문화/에티켓 전문 어드바이저입니다.
여행지의 문화, 관습, 주의사항, 팁 정보를 제공합니다.
사용자가 질문할 때만 상세하게, 평소에는 중요 주의사항만 간략히.

규칙:
- 문화적 실수를 방지하는 핵심 정보 위주
- 요청 시에만 상세 설명""",
                tools = listOf("searchWeb", "query_keyword_memory"),
                rules = listOf(
                    "현지 문화/에티켓/주의사항 제공",
                    "사용자가 질문할 때만 상세 설명"
                ),
                temperature = 0.5f,
                isProactive = false
            ),
            AgentRole(
                roleName = "travel_logger",
                providerId = ProviderId.GROK,
                systemPrompt = """당신은 여행 경험을 자동 기록하는 메모리 에이전트입니다.
방문한 장소, 먹은 음식, 본 것들을 메모리에서 추적하고 여행 일지를 구성합니다.
하루 끝에 여행 요약을 생성할 준비를 합니다.

규칙:
- 장소별 체류 시간, 핵심 경험을 요약
- 감정 기록도 포함 (즐거웠던/인상적이었던 등)""",
                tools = listOf("query_temporal_memory", "query_spatial_memory", "query_visual_memory", "query_emotion_memory", "save_structured_data", "query_structured_data"),
                rules = listOf(
                    "여행 중 방문 장소/경험을 자동 기록",
                    "하루 끝에 여행 요약 생성 준비"
                ),
                temperature = 0.6f,
                isProactive = true,
                proactiveIntervalMs = 300_000L // 5분
            )
        ),
        initialPlanGoals = listOf(
            "현재 위치의 언어 환경 파악",
            "주변 관광 정보 선제 수집",
            "OCR 번역 파이프라인 활성화",
            "여행 경험 자동 기록 시작"
        ),
        maxDurationMs = 12 * 3600_000L // 12시간
    )

    // ─── EXPLORATION (번역 없음, 관심 객체/환경 중심) ───

    val EXPLORATION = MissionTemplateConfig(
        type = MissionType.EXPLORATION,
        agentRoles = listOf(
            AgentRole(
                roleName = "scene_analyst",
                providerId = ProviderId.GEMINI,
                systemPrompt = """당신은 탐험 중 주변 환경을 분석하는 에이전트입니다.
카메라로 보이는 흥미로운 건물, 자연, 예술품, 기념물 등을 식별하고 설명합니다.""",
                tools = listOf("get_screen_objects", "take_snapshot", "query_visual_memory"),
                rules = listOf("흥미로운 발견 시에만 보고", "설명은 1-2문장으로 간결하게"),
                isProactive = true,
                proactiveIntervalMs = 120_000L // 30초→120초 (토큰 폭주 방지)
            ),
            AgentRole(
                roleName = "area_info",
                providerId = ProviderId.OPENAI,
                systemPrompt = """현재 위치 주변의 흥미로운 장소, 역사, 정보를 웹에서 수집하는 에이전트입니다.""",
                tools = listOf("searchWeb", "get_current_location", "query_spatial_memory"),
                rules = listOf("위치 변경 시에만 새 정보 수집"),
                isProactive = true,
                proactiveIntervalMs = 180_000L // 3분
            )
        ),
        initialPlanGoals = listOf(
            "현재 위치 주변 환경 분석",
            "주변 관심 장소 정보 수집"
        ),
        maxDurationMs = 4 * 3600_000L
    )

    // ─── DAILY_COMMUTE (루틴 기반, 교통 정보 중심) ───

    val DAILY_COMMUTE = MissionTemplateConfig(
        type = MissionType.DAILY_COMMUTE,
        agentRoles = listOf(
            AgentRole(
                roleName = "commute_navigator",
                providerId = ProviderId.OPENAI,
                systemPrompt = """통근 경로 최적화 에이전트입니다. 현재 교통 상황, 대중교통 시간표, 예상 도착 시간을 제공합니다.""",
                tools = listOf("get_current_location", "get_directions", "getWeather"),
                rules = listOf("지연/사고 정보 우선 알림", "대안 경로 항상 준비"),
                isProactive = true,
                proactiveIntervalMs = 120_000L
            )
        ),
        initialPlanGoals = listOf(
            "출발지/목적지 파악",
            "현재 교통 상황 확인",
            "최적 경로 제안"
        ),
        maxDurationMs = 2 * 3600_000L
    )

    // ─── SOCIAL_ENCOUNTER (인물 감지 기반, 관계/대화 이력 중심) ───

    val SOCIAL_ENCOUNTER = MissionTemplateConfig(
        type = MissionType.SOCIAL_ENCOUNTER,
        agentRoles = listOf(
            AgentRole(
                roleName = "relationship_assistant",
                providerId = ProviderId.GEMINI,
                systemPrompt = """사회적 만남을 돕는 에이전트입니다.
인식된 인물의 이름, 이전 대화 이력, 관계 맥락을 메모리에서 조회하여 사용자에게 제공합니다.
"지난번에 ~에 대해 이야기했었습니다" 같은 리마인더를 제공합니다.""",
                tools = listOf("query_keyword_memory", "query_temporal_memory", "query_emotion_memory"),
                rules = listOf("인물 감지 시에만 활성", "사적 정보는 HUD에만 표시 (TTS 금지)"),
                temperature = 0.4f,
                isProactive = false
            )
        ),
        initialPlanGoals = listOf(
            "인식된 인물의 과거 상호작용 이력 조회",
            "최근 대화 주제/관심사 요약 준비"
        ),
        maxDurationMs = 2 * 3600_000L
    )
}

data class MissionTemplateConfig(
    val type: MissionType,
    val agentRoles: List<AgentRole>,
    val initialPlanGoals: List<String>,
    val maxDurationMs: Long = 4 * 3600_000L,
    val briefingDomains: List<String> = emptyList() // Phase 17: pre-fetch domains for SystemAnalyticsService
)

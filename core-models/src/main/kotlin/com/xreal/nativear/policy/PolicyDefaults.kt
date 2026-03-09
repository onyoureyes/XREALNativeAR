package com.xreal.nativear.policy

/**
 * PolicyDefaults — 전체 정책 상수 기본값.
 *
 * 모든 정책은 PolicyRegistry 초기화 시 등록됨.
 * 소비자는 PolicyReader.getXxx() 호출, 실패 시 기존 하드코딩 fallback.
 *
 * Phase 1 (완료): CADENCE + BUDGET + SYSTEM + CAPACITY + VISION/CAMERA (~58개)
 * Phase 2 (완료): COMPANION (~30개)
 * Phase 3 (완료): RUNNING + MEETING (~25개)
 * Phase 4 (완료): MISSION + EXPERT + RESILIENCE + MONITORING (~20개)
 */
object PolicyDefaults {

    fun getAllDefaults(): List<PolicyEntry> = listOf(
        // =====================================================================
        // CADENCE — 비전/센서 처리 간격 (CadenceConfig, CadenceProfile)
        // =====================================================================
        PolicyEntry("cadence.ocr_interval_ms", PolicyCategory.CADENCE, "2000", PolicyValueType.LONG,
            "OCR 분석 간격 (ms)", "500", "30000"),
        PolicyEntry("cadence.detect_interval_ms", PolicyCategory.CADENCE, "2000", PolicyValueType.LONG,
            "객체 감지 간격 (ms)", "500", "30000"),
        PolicyEntry("cadence.pose_interval_ms", PolicyCategory.CADENCE, "500", PolicyValueType.LONG,
            "포즈 추정 간격 (ms)", "200", "10000"),
        PolicyEntry("cadence.visual_embedding_interval_ms", PolicyCategory.CADENCE, "5000", PolicyValueType.LONG,
            "시각 임베딩 간격 (ms)", "1000", "60000"),
        PolicyEntry("cadence.frame_skip", PolicyCategory.CADENCE, "2", PolicyValueType.INT,
            "프레임 스킵 비율", "1", "10"),
        PolicyEntry("cadence.hand_tracking_interval_ms", PolicyCategory.CADENCE, "33", PolicyValueType.LONG,
            "손 추적 간격 (ms)", "16", "200"),
        PolicyEntry("cadence.pdr_step_threshold", PolicyCategory.CADENCE, "15", PolicyValueType.INT,
            "PDR 걸음 임계값", "3", "100"),
        PolicyEntry("cadence.stability_duration_ms", PolicyCategory.CADENCE, "2000", PolicyValueType.LONG,
            "안정성 판단 기간 (ms)", "500", "10000"),
        PolicyEntry("cadence.stability_accel_threshold", PolicyCategory.CADENCE, "0.5", PolicyValueType.FLOAT,
            "안정성 가속도 임계값", "0.1", "2.0"),
        PolicyEntry("cadence.slam_frame_interval", PolicyCategory.CADENCE, "3", PolicyValueType.INT,
            "SLAM 프레임 간격", "1", "10"),
        PolicyEntry("cadence.rgb_frame_interval", PolicyCategory.CADENCE, "3", PolicyValueType.INT,
            "RGB 프레임 간격", "1", "10"),
        PolicyEntry("cadence.tilt_cooldown_ms", PolicyCategory.CADENCE, "5000", PolicyValueType.LONG,
            "틸트 제스처 쿨다운 (ms)", "1000", "30000"),
        PolicyEntry("cadence.schedule_extract_cooldown_ms", PolicyCategory.CADENCE, "30000", PolicyValueType.LONG,
            "일정 추출 쿨다운 (ms)", "5000", "120000"),
        PolicyEntry("cadence.directive_check_interval_ms", PolicyCategory.CADENCE, "60000", PolicyValueType.LONG,
            "Directive 확인 주기 (ms)", "10000", "300000"),

        // UserStateTracker 상태 전환 파라미터
        PolicyEntry("cadence.state_hysteresis_ms", PolicyCategory.CADENCE, "5000", PolicyValueType.LONG,
            "상태 변경 디바운스 (ms)", "1000", "30000"),
        PolicyEntry("cadence.step_window_ms", PolicyCategory.CADENCE, "10000", PolicyValueType.LONG,
            "걸음 카운터 윈도우 (ms)", "3000", "30000"),
        PolicyEntry("cadence.speech_window_ms", PolicyCategory.CADENCE, "15000", PolicyValueType.LONG,
            "음성 카운터 윈도우 (ms)", "5000", "60000"),

        // NeedsDetector
        PolicyEntry("cadence.translate_cooldown_ms", PolicyCategory.CADENCE, "5000", PolicyValueType.LONG,
            "번역 쿨다운 (ms)", "1000", "30000"),
        PolicyEntry("cadence.ocr_boost_window_ms", PolicyCategory.CADENCE, "30000", PolicyValueType.LONG,
            "OCR 부스트 윈도우 (ms)", "5000", "120000"),
        PolicyEntry("cadence.ocr_boost_threshold", PolicyCategory.CADENCE, "3", PolicyValueType.INT,
            "OCR 부스트 외국어 감지 횟수", "1", "10"),
        PolicyEntry("cadence.boosted_ocr_interval_ms", PolicyCategory.CADENCE, "1000", PolicyValueType.LONG,
            "부스트 OCR 간격 (ms)", "500", "5000"),
        PolicyEntry("cadence.interest_cooldown_ms", PolicyCategory.CADENCE, "15000", PolicyValueType.LONG,
            "관심사 쿨다운 (ms)", "5000", "60000"),

        // =====================================================================
        // BUDGET — 토큰 예산 (TokenBudgetTracker, TokenOptimizer)
        // =====================================================================
        PolicyEntry("budget.gemini_daily_tokens", PolicyCategory.BUDGET, "500000", PolicyValueType.INT,
            "Gemini 일일 토큰 예산", "100000", "2000000"),
        PolicyEntry("budget.openai_daily_tokens", PolicyCategory.BUDGET, "100000", PolicyValueType.INT,
            "OpenAI 일일 토큰 예산", "10000", "500000"),
        PolicyEntry("budget.claude_daily_tokens", PolicyCategory.BUDGET, "100000", PolicyValueType.INT,
            "Claude 일일 토큰 예산", "10000", "500000"),
        PolicyEntry("budget.grok_daily_tokens", PolicyCategory.BUDGET, "100000", PolicyValueType.INT,
            "Grok 일일 토큰 예산", "10000", "500000"),
        PolicyEntry("budget.careful_threshold", PolicyCategory.BUDGET, "0.90", PolicyValueType.FLOAT,
            "CAREFUL 예산 임계값 (ENRICHED 차단)", "0.50", "0.99"),
        PolicyEntry("budget.minimal_threshold", PolicyCategory.BUDGET, "0.95", PolicyValueType.FLOAT,
            "MINIMAL 예산 임계값 (STANDARD 차단)", "0.80", "1.00"),
        PolicyEntry("budget.blocked_threshold", PolicyCategory.BUDGET, "1.00", PolicyValueType.FLOAT,
            "BLOCKED 예산 임계값 (전부 차단)", "0.90", "1.50"),

        // TokenOptimizer 토큰 할당량
        PolicyEntry("budget.minimal_tokens", PolicyCategory.BUDGET, "200", PolicyValueType.INT,
            "MINIMAL 분석 토큰 한도", "50", "500"),
        PolicyEntry("budget.standard_tokens", PolicyCategory.BUDGET, "400", PolicyValueType.INT,
            "STANDARD 분석 토큰 한도", "100", "1000"),
        PolicyEntry("budget.enriched_tokens", PolicyCategory.BUDGET, "500", PolicyValueType.INT,
            "ENRICHED 분석 토큰 한도", "200", "1500"),

        // Gap B: TokenOptimizer 연속 SKIP 상한 (과도한 SKIP 방지)
        PolicyEntry("vision.max_consecutive_skips", PolicyCategory.VISION, "10", PolicyValueType.INT,
            "연속 SKIP 최대 횟수 (초과 시 MINIMAL 강제)", "3", "50"),

        // PersonaProviderRouter 예산 전환점
        PolicyEntry("budget.lite_switch_percent", PolicyCategory.BUDGET, "80", PolicyValueType.FLOAT,
            "경량 모델 전환 예산 사용률 (%)", "50", "95"),
        PolicyEntry("budget.emergency_percent", PolicyCategory.BUDGET, "95", PolicyValueType.FLOAT,
            "긴급 모드 예산 사용률 (%)", "80", "100"),

        // =====================================================================
        // SYSTEM — 시스템 전반 (StrategistService, BatchProcessor 등)
        // =====================================================================
        PolicyEntry("system.reflection_interval_ms", PolicyCategory.SYSTEM, "300000", PolicyValueType.LONG,
            "전략가 반성 주기 (ms)", "60000", "1800000"),
        PolicyEntry("system.reflection_initial_delay_ms", PolicyCategory.SYSTEM, "60000", PolicyValueType.LONG,
            "전략가 초기 대기 (ms)", "10000", "300000"),
        PolicyEntry("system.batch_interval_ms", PolicyCategory.SYSTEM, "30000", PolicyValueType.LONG,
            "배치 처리 간격 (ms)", "10000", "120000"),
        PolicyEntry("system.max_concurrent_ai_calls", PolicyCategory.SYSTEM, "2", PolicyValueType.INT,
            "최대 동시 AI 호출 수", "1", "5"),
        PolicyEntry("system.dedup_window_ms", PolicyCategory.SYSTEM, "60000", PolicyValueType.LONG,
            "중복 제거 윈도우 (ms)", "10000", "300000"),
        PolicyEntry("system.compression_throttle_ms", PolicyCategory.SYSTEM, "180000", PolicyValueType.LONG,
            "메모리 압축 최소 간격 (ms)", "60000", "600000"),
        PolicyEntry("system.max_conversation_turns", PolicyCategory.SYSTEM, "4", PolicyValueType.INT,
            "일반 예산 최대 대화 턴 수", "2", "10"),
        PolicyEntry("system.index_throttle_ms", PolicyCategory.SYSTEM, "60000", PolicyValueType.LONG,
            "공간 인덱싱 쓰로틀 (ms)", "10000", "300000"),
        PolicyEntry("system.max_indexed_objects", PolicyCategory.SYSTEM, "200", PolicyValueType.INT,
            "LRU 인덱스 최대 크기", "50", "1000"),

        // =====================================================================
        // CAPACITY — 캐시 크기, 버퍼 한도
        // =====================================================================
        PolicyEntry("capacity.analysis_cache_size", PolicyCategory.CAPACITY, "100", PolicyValueType.INT,
            "분석 캐시 최대 크기 (LRU)", "20", "500"),
        PolicyEntry("capacity.scene_cache_ttl_ms", PolicyCategory.CAPACITY, "1800000", PolicyValueType.LONG,
            "씬 캐시 TTL (ms, 30분)", "300000", "7200000"),
        PolicyEntry("capacity.object_cache_ttl_ms", PolicyCategory.CAPACITY, "86400000", PolicyValueType.LONG,
            "객체 캐시 TTL (ms, 24시간)", "3600000", "259200000"),
        PolicyEntry("capacity.translation_cache_max_size", PolicyCategory.CAPACITY, "200", PolicyValueType.INT,
            "번역 캐시 최대 크기", "50", "1000"),
        PolicyEntry("capacity.translation_cache_ttl_ms", PolicyCategory.CAPACITY, "300000", PolicyValueType.LONG,
            "번역 캐시 TTL (ms, 5분)", "60000", "1800000"),
        PolicyEntry("capacity.edge_judge_cache_size", PolicyCategory.CAPACITY, "20", PolicyValueType.INT,
            "Edge 판단 캐시 최대 크기", "5", "100"),
        PolicyEntry("capacity.edge_judge_cache_ttl_ms", PolicyCategory.CAPACITY, "90000", PolicyValueType.LONG,
            "Edge 판단 캐시 TTL (ms, 90초)", "10000", "600000"),

        // =====================================================================
        // VISION — 카메라 스트림 건강 모니터링 (CameraStreamManager)
        // =====================================================================
        PolicyEntry("camera.health_fps_threshold", PolicyCategory.VISION, "3.0", PolicyValueType.FLOAT,
            "건강 판단 최소 fps", "0.5", "15.0"),
        PolicyEntry("camera.dark_frame_limit", PolicyCategory.VISION, "30", PolicyValueType.INT,
            "연속 다크 프레임 한계", "5", "100"),
        PolicyEntry("camera.no_frame_timeout_ms", PolicyCategory.VISION, "5000", PolicyValueType.LONG,
            "프레임 타임아웃 (ms)", "2000", "30000"),
        PolicyEntry("camera.auto_fallback_enabled", PolicyCategory.VISION, "true", PolicyValueType.BOOLEAN,
            "자동 fallback 활성화", null, null),
        PolicyEntry("camera.auto_recover_enabled", PolicyCategory.VISION, "true", PolicyValueType.BOOLEAN,
            "고우선 소스 복구 시 자동 복귀", null, null),
        PolicyEntry("camera.rgb_retry_max_attempts", PolicyCategory.VISION, "3", PolicyValueType.INT,
            "RGB 카메라 시작 최대 재시도", "1", "10"),
        PolicyEntry("camera.rgb_retry_initial_delay_ms", PolicyCategory.VISION, "3000", PolicyValueType.LONG,
            "RGB 시작 첫 대기 (ms)", "1000", "10000"),
        PolicyEntry("camera.rgb_retry_backoff_factor", PolicyCategory.VISION, "1.5", PolicyValueType.FLOAT,
            "RGB 재시도 backoff 배수", "1.0", "3.0"),
        PolicyEntry("vision.scene_confidence_threshold", PolicyCategory.VISION, "0.50", PolicyValueType.FLOAT,
            "장면 해싱 최소 신뢰도", "0.1", "0.9"),
        PolicyEntry("vision.scene_unchanged_threshold", PolicyCategory.VISION, "0.95", PolicyValueType.FLOAT,
            "장면 변화 없음 임계값", "0.8", "1.0"),
        PolicyEntry("vision.scene_minor_change_threshold", PolicyCategory.VISION, "0.7", PolicyValueType.FLOAT,
            "장면 소변화 임계값", "0.4", "0.95"),
        PolicyEntry("vision.scene_major_change_threshold", PolicyCategory.VISION, "0.5", PolicyValueType.FLOAT,
            "장면 대변화 임계값", "0.2", "0.8"),

        // =====================================================================
        // COMPANION — AI 동반자 엔진 (NoveltyEngine, CoachEngine, FamiliarityEngine 등)
        // =====================================================================
        // NoveltyEngine
        PolicyEntry("companion.routine_novelty_interval_ms", PolicyCategory.COMPANION, "900000", PolicyValueType.LONG,
            "루틴 노벨티 주입 간격 (ms)", "300000", "3600000"),
        PolicyEntry("companion.active_novelty_interval_ms", PolicyCategory.COMPANION, "300000", PolicyValueType.LONG,
            "활동 중 노벨티 간격 (ms)", "60000", "1800000"),
        PolicyEntry("companion.novelty_base_analysis_tokens", PolicyCategory.COMPANION, "500", PolicyValueType.INT,
            "노벨티 분석 기본 토큰", "100", "1000"),
        PolicyEntry("companion.novelty_base_novelty_tokens", PolicyCategory.COMPANION, "300", PolicyValueType.INT,
            "노벨티 주입 기본 토큰", "50", "800"),

        // CoachEngine
        PolicyEntry("companion.challenge_cooldown_ms", PolicyCategory.COMPANION, "1800000", PolicyValueType.LONG,
            "도전 과제 쿨다운 (ms)", "600000", "7200000"),
        PolicyEntry("companion.memory_prime_cooldown_ms", PolicyCategory.COMPANION, "1200000", PolicyValueType.LONG,
            "기억 프라이밍 쿨다운 (ms)", "300000", "3600000"),
        PolicyEntry("companion.max_active_challenges", PolicyCategory.COMPANION, "2", PolicyValueType.INT,
            "최대 동시 활성 도전", "1", "5"),
        PolicyEntry("companion.hrv_stress_threshold", PolicyCategory.COMPANION, "20.0", PolicyValueType.FLOAT,
            "HRV 스트레스 임계값", "10.0", "50.0"),
        PolicyEntry("companion.spo2_stress_threshold", PolicyCategory.COMPANION, "94", PolicyValueType.INT,
            "SpO2 스트레스 임계값", "88", "98"),
        PolicyEntry("companion.elevated_hr_threshold", PolicyCategory.COMPANION, "100", PolicyValueType.INT,
            "활성 심박 임계값", "80", "140"),
        PolicyEntry("companion.head_stability_focus_threshold", PolicyCategory.COMPANION, "0.8", PolicyValueType.FLOAT,
            "집중 상태 머리 안정성 임계값", "0.5", "1.0"),

        // FamiliarityEngine
        PolicyEntry("companion.recent_encounter_window_ms", PolicyCategory.COMPANION, "604800000", PolicyValueType.LONG,
            "최근 만남 윈도우 (ms, 7일)", "86400000", "2592000000"),
        PolicyEntry("companion.emotion_ema_alpha", PolicyCategory.COMPANION, "0.1", PolicyValueType.FLOAT,
            "감정 EMA 알파 (새 값 가중치)", "0.01", "0.5"),
        PolicyEntry("companion.diversity_increment", PolicyCategory.COMPANION, "0.05", PolicyValueType.FLOAT,
            "컨텍스트 다양성 증가분", "0.01", "0.2"),
        PolicyEntry("companion.recent_insight_window_days", PolicyCategory.COMPANION, "7", PolicyValueType.INT,
            "최근 인사이트 윈도우 (일)", "1", "30"),

        // AgentPersonalityEvolution
        PolicyEntry("companion.trait_evolution_threshold", PolicyCategory.COMPANION, "5", PolicyValueType.INT,
            "특성 진화 연속 성공 횟수", "2", "20"),
        PolicyEntry("companion.max_evolved_traits", PolicyCategory.COMPANION, "5", PolicyValueType.INT,
            "에이전트 최대 진화 특성", "1", "10"),
        PolicyEntry("companion.max_agent_memories", PolicyCategory.COMPANION, "50", PolicyValueType.INT,
            "에이전트 최대 기억 수", "10", "200"),
        PolicyEntry("companion.trust_ema_alpha", PolicyCategory.COMPANION, "0.1", PolicyValueType.FLOAT,
            "신뢰도 EMA 알파", "0.01", "0.5"),
        PolicyEntry("companion.max_recent_outcomes", PolicyCategory.COMPANION, "20", PolicyValueType.INT,
            "최근 결과 추적 버퍼 크기", "5", "100"),

        // SituationLifecycleManager
        PolicyEntry("companion.persist_every_n", PolicyCategory.COMPANION, "5", PolicyValueType.INT,
            "DB 영속 주기 (N 관찰마다)", "1", "20"),
        PolicyEntry("companion.downgrade_failure_rate", PolicyCategory.COMPANION, "0.30", PolicyValueType.FLOAT,
            "숙련도 강등 실패율 임계값", "0.10", "0.60"),
        PolicyEntry("companion.downgrade_min_observations", PolicyCategory.COMPANION, "10", PolicyValueType.INT,
            "강등 최소 관찰 횟수", "3", "50"),
        PolicyEntry("companion.routine_min_successes", PolicyCategory.COMPANION, "3", PolicyValueType.INT,
            "ROUTINE 승급 최소 연속 성공", "1", "10"),
        PolicyEntry("companion.routine_min_observations", PolicyCategory.COMPANION, "5", PolicyValueType.INT,
            "ROUTINE 승급 최소 관찰", "2", "20"),
        PolicyEntry("companion.mastered_min_successes", PolicyCategory.COMPANION, "10", PolicyValueType.INT,
            "MASTERED 승급 최소 연속 성공", "3", "30"),
        PolicyEntry("companion.mastered_min_observations", PolicyCategory.COMPANION, "20", PolicyValueType.INT,
            "MASTERED 승급 최소 관찰", "5", "100"),

        // SituationPredictor
        PolicyEntry("companion.prediction_min_probability", PolicyCategory.COMPANION, "0.35", PolicyValueType.FLOAT,
            "예측 최소 확률 임계값", "0.10", "0.80"),
        PolicyEntry("companion.prediction_warmup_lead_ms", PolicyCategory.COMPANION, "900000", PolicyValueType.LONG,
            "예측 워밍업 선행 (ms, 15분)", "300000", "3600000"),
        PolicyEntry("companion.warmup_min_delay_ms", PolicyCategory.COMPANION, "300000", PolicyValueType.LONG,
            "워밍업 최소 지연 (ms, 5분)", "60000", "900000"),

        // AgentMetaManager
        PolicyEntry("companion.low_effectiveness_threshold", PolicyCategory.COMPANION, "0.3", PolicyValueType.FLOAT,
            "에이전트 낮은 효과 임계값", "0.1", "0.5"),
        PolicyEntry("companion.high_effectiveness_threshold", PolicyCategory.COMPANION, "0.7", PolicyValueType.FLOAT,
            "에이전트 높은 효과 임계값", "0.5", "0.9"),

        // CompanionDeviceManager
        PolicyEntry("companion.llm_offload_timeout_ms", PolicyCategory.COMPANION, "30000", PolicyValueType.LONG,
            "LLM 오프로드 타임아웃 (ms)", "10000", "120000"),

        // =====================================================================
        // RUNNING — 러닝 코치 (PacemakerService, PositionFusion 등)
        // =====================================================================
        PolicyEntry("running.pacemaker_max_ahead_m", PolicyCategory.RUNNING, "30.0", PolicyValueType.FLOAT,
            "페이스메이커 최대 전방 거리 (m)", "10.0", "100.0"),
        PolicyEntry("running.pacemaker_max_behind_m", PolicyCategory.RUNNING, "-15.0", PolicyValueType.FLOAT,
            "페이스메이커 최대 후방 거리 (m)", "-50.0", "0.0"),
        PolicyEntry("running.pacemaker_update_interval_ms", PolicyCategory.RUNNING, "1000", PolicyValueType.LONG,
            "페이스메이커 업데이트 간격 (ms)", "200", "5000"),
        PolicyEntry("running.default_target_pace", PolicyCategory.RUNNING, "6.0", PolicyValueType.FLOAT,
            "기본 목표 페이스 (min/km)", "3.0", "12.0"),
        PolicyEntry("running.coaching_interval_ms", PolicyCategory.RUNNING, "300000", PolicyValueType.LONG,
            "AI 코칭 간격 (ms)", "60000", "900000"),
        PolicyEntry("running.gps_degraded_timeout_ms", PolicyCategory.RUNNING, "15000", PolicyValueType.LONG,
            "GPS 열화 판단 타임아웃 (ms)", "5000", "60000"),
        PolicyEntry("running.gps_lost_timeout_ms", PolicyCategory.RUNNING, "25000", PolicyValueType.LONG,
            "GPS 유실 판단 타임아웃 (ms)", "10000", "120000"),
        PolicyEntry("running.max_running_speed_mps", PolicyCategory.RUNNING, "6.0", PolicyValueType.FLOAT,
            "최대 허용 러닝 속도 (m/s)", "3.0", "12.0"),
        PolicyEntry("running.max_gps_accuracy_accept", PolicyCategory.RUNNING, "50.0", PolicyValueType.FLOAT,
            "GPS 정확도 수용 한계 (m)", "10.0", "100.0"),
        PolicyEntry("running.good_accuracy_threshold", PolicyCategory.RUNNING, "15.0", PolicyValueType.FLOAT,
            "GPS 양호 정확도 (m)", "5.0", "30.0"),
        PolicyEntry("running.pdr_reset_uncertainty_m", PolicyCategory.RUNNING, "50.0", PolicyValueType.FLOAT,
            "PDR 리셋 불확실성 (m)", "10.0", "100.0"),
        PolicyEntry("running.stride_calib_min_steps", PolicyCategory.RUNNING, "50", PolicyValueType.INT,
            "보폭 보정 최소 걸음 수", "20", "200"),
        PolicyEntry("running.stride_calib_min_dist_m", PolicyCategory.RUNNING, "20.0", PolicyValueType.FLOAT,
            "보폭 보정 최소 GPS 거리 (m)", "10.0", "100.0"),
        PolicyEntry("running.lap_proximity_m", PolicyCategory.RUNNING, "20.0", PolicyValueType.FLOAT,
            "랩 근접 임계 거리 (m)", "5.0", "50.0"),
        PolicyEntry("running.lap_depart_m", PolicyCategory.RUNNING, "50.0", PolicyValueType.FLOAT,
            "랩 출발 인정 거리 (m)", "20.0", "100.0"),
        PolicyEntry("running.min_lap_duration_ms", PolicyCategory.RUNNING, "60000", PolicyValueType.LONG,
            "최소 랩 시간 (ms)", "30000", "300000"),
        PolicyEntry("running.min_lap_distance_m", PolicyCategory.RUNNING, "200.0", PolicyValueType.FLOAT,
            "최소 랩 거리 (m)", "50.0", "1000.0"),
        PolicyEntry("running.floor_hpa_per_floor", PolicyCategory.RUNNING, "1.2", PolicyValueType.FLOAT,
            "층간 기압 차이 (hPa)", "0.5", "2.0"),
        PolicyEntry("running.floor_debounce_ms", PolicyCategory.RUNNING, "5000", PolicyValueType.LONG,
            "층 변경 디바운스 (ms)", "1000", "15000"),
        PolicyEntry("running.intervention_cooldown_ms", PolicyCategory.RUNNING, "45000", PolicyValueType.FLOAT,
            "폼 인터벤션 쿨다운 (ms)", "10000", "120000"),
        PolicyEntry("running.intervention_escalation_count", PolicyCategory.RUNNING, "3", PolicyValueType.INT,
            "인터벤션 에스컬레이션 횟수", "2", "10"),

        // =====================================================================
        // MEETING — 회의 보조 (MeetingContextService, ScheduleExtractor)
        // =====================================================================
        PolicyEntry("meeting.max_ocr_buffer", PolicyCategory.MEETING, "20", PolicyValueType.INT,
            "최근 OCR 텍스트 버퍼 크기", "5", "100"),
        PolicyEntry("meeting.max_speech_buffer", PolicyCategory.MEETING, "30", PolicyValueType.INT,
            "최근 음성 텍스트 버퍼 크기", "10", "100"),
        PolicyEntry("meeting.ocr_extract_interval_ms", PolicyCategory.MEETING, "60000", PolicyValueType.LONG,
            "일정 추출 OCR 간격 (ms)", "10000", "300000"),
        PolicyEntry("meeting.schedule_cache_size", PolicyCategory.MEETING, "50", PolicyValueType.INT,
            "일정 추출 캐시 크기", "10", "200"),

        // =====================================================================
        // MISSION — 미션 (MissionConductor, MissionDetectorRouter)
        // =====================================================================
        PolicyEntry("mission.max_concurrent", PolicyCategory.MISSION, "4", PolicyValueType.INT,
            "최대 동시 미션 수", "1", "10"),
        PolicyEntry("mission.monitor_interval_ms", PolicyCategory.MISSION, "300000", PolicyValueType.LONG,
            "미션 모니터링 주기 (ms)", "60000", "900000"),
        PolicyEntry("mission.plan_temperature", PolicyCategory.MISSION, "0.4", PolicyValueType.FLOAT,
            "미션 계획 AI 온도", "0.0", "1.0"),
        PolicyEntry("mission.plan_max_tokens", PolicyCategory.MISSION, "2048", PolicyValueType.INT,
            "미션 계획 최대 토큰", "256", "4096"),
        PolicyEntry("mission.max_agents_per_mission", PolicyCategory.MISSION, "3", PolicyValueType.INT,
            "미션당 최대 에이전트 수", "1", "5"),
        PolicyEntry("mission.travel_cooldown_ms", PolicyCategory.MISSION, "3600000", PolicyValueType.LONG,
            "여행 미션 쿨다운 (ms)", "600000", "7200000"),
        PolicyEntry("mission.exploration_cooldown_ms", PolicyCategory.MISSION, "1800000", PolicyValueType.LONG,
            "탐험 미션 쿨다운 (ms)", "300000", "3600000"),
        PolicyEntry("mission.social_cooldown_ms", PolicyCategory.MISSION, "600000", PolicyValueType.LONG,
            "소셜 미션 쿨다운 (ms)", "60000", "1800000"),

        // =====================================================================
        // EXPERT — 전문가 팀 (ExpertTeamManager, BriefingService)
        // =====================================================================
        PolicyEntry("expert.max_active_domains", PolicyCategory.EXPERT, "4", PolicyValueType.INT,
            "최대 동시 전문 영역", "1", "8"),
        PolicyEntry("expert.min_briefing_interval_ms", PolicyCategory.EXPERT, "14400000", PolicyValueType.LONG,
            "브리핑 최소 간격 (ms, 4시간)", "3600000", "43200000"),

        // =====================================================================
        // RESOURCE_GUARD — 지능형 자원 관리 (ResourceGuardian, AI 상황 판단)
        // =====================================================================
        PolicyEntry("resource.cloud_enabled", PolicyCategory.RESILIENCE, "false", PolicyValueType.BOOLEAN,
            "서버(Cloud) API 활성화 여부 — false면 리모트+엣지만 사용", null, null),
        PolicyEntry("resource.remote_enabled", PolicyCategory.RESILIENCE, "true", PolicyValueType.BOOLEAN,
            "리모트(PC LLM) 활성화 여부", null, null),
        PolicyEntry("resource.edge_enabled", PolicyCategory.RESILIENCE, "true", PolicyValueType.BOOLEAN,
            "엣지(on-device) 활성화 여부", null, null),

        PolicyEntry("resource.hw_retry_enabled", PolicyCategory.RESILIENCE, "true", PolicyValueType.BOOLEAN,
            "하드웨어 재시도 허용 여부 (false → 즉시 포기)", null, null),
        PolicyEntry("resource.hw_max_retries", PolicyCategory.RESILIENCE, "3", PolicyValueType.INT,
            "하드웨어 재시도 최대 횟수 (AI가 동적 조절)", "0", "10"),
        PolicyEntry("resource.hw_retry_backoff_ms", PolicyCategory.RESILIENCE, "5000", PolicyValueType.LONG,
            "하드웨어 재시도 간격 (ms)", "1000", "60000"),
        PolicyEntry("resource.hw_suspend_until_ms", PolicyCategory.RESILIENCE, "0", PolicyValueType.LONG,
            "하드웨어 재시도 일시 중지 종료 시각 (epoch ms, 0=즉시 허용)", "0", "9999999999999"),

        PolicyEntry("resource.max_concurrent_coroutines", PolicyCategory.RESILIENCE, "8", PolicyValueType.INT,
            "전체 시스템 최대 동시 코루틴 수", "2", "32"),
        PolicyEntry("resource.max_concurrent_ai_calls", PolicyCategory.RESILIENCE, "2", PolicyValueType.INT,
            "최대 동시 AI 호출 수 (AI+시스템 공유)", "1", "5"),

        PolicyEntry("resource.guard_interval_ms", PolicyCategory.RESILIENCE, "30000", PolicyValueType.LONG,
            "ResourceGuardian AI 판단 주기 (ms)", "10000", "300000"),
        PolicyEntry("resource.guard_enabled", PolicyCategory.RESILIENCE, "true", PolicyValueType.BOOLEAN,
            "ResourceGuardian AI 판단 활성화", null, null),

        // =====================================================================
        // RESILIENCE — 장치 건강, 안전 (DeviceHealthMonitor, FailsafeController)
        // =====================================================================
        PolicyEntry("resilience.health_poll_interval_ms", PolicyCategory.RESILIENCE, "30000", PolicyValueType.LONG,
            "장치 건강 폴링 간격 (ms)", "10000", "120000"),
        PolicyEntry("resilience.glasses_heartbeat_timeout_ms", PolicyCategory.RESILIENCE, "3000", PolicyValueType.LONG,
            "글래스 MCU 하트비트 타임아웃 (ms)", "1000", "10000"),
        PolicyEntry("resilience.glasses_frame_timeout_ms", PolicyCategory.RESILIENCE, "5000", PolicyValueType.LONG,
            "글래스 OV580 프레임 타임아웃 (ms)", "2000", "15000"),
        PolicyEntry("resilience.watch_data_timeout_ms", PolicyCategory.RESILIENCE, "30000", PolicyValueType.LONG,
            "워치 데이터 타임아웃 (ms)", "10000", "120000"),
        PolicyEntry("resilience.battery_critical_percent", PolicyCategory.RESILIENCE, "10", PolicyValueType.INT,
            "배터리 위험 임계값 (%)", "5", "20"),
        PolicyEntry("resilience.battery_low_percent", PolicyCategory.RESILIENCE, "20", PolicyValueType.INT,
            "배터리 저전력 임계값 (%)", "10", "35"),
        PolicyEntry("resilience.thermal_severe_level", PolicyCategory.RESILIENCE, "4", PolicyValueType.INT,
            "열 심각 레벨 (0-5)", "2", "5"),
        PolicyEntry("resilience.conductor_loop_ms", PolicyCategory.RESILIENCE, "30000", PolicyValueType.LONG,
            "시스템 지휘자 루프 간격 (ms)", "10000", "120000"),
        PolicyEntry("resilience.emergency_lock_ms", PolicyCategory.RESILIENCE, "30000", PolicyValueType.LONG,
            "긴급 잠금 기간 (ms)", "10000", "120000"),

        // =====================================================================
        // AI_CONFIG — GoalOrientedAgentLoop (ReAct + Reflexion)
        // =====================================================================
        PolicyEntry("agent.max_reasoning_depth", PolicyCategory.AI_CONFIG, "12", PolicyValueType.INT,
            "목표 추구 최대 추론 깊이", "3", "30"),
        PolicyEntry("agent.max_tokens_per_goal", PolicyCategory.AI_CONFIG, "4000", PolicyValueType.INT,
            "목표당 최대 토큰 예산", "500", "15000"),
        PolicyEntry("agent.goal_confidence_threshold", PolicyCategory.AI_CONFIG, "0.75", PolicyValueType.FLOAT,
            "목표 달성 신뢰도 임계값", "0.5", "0.99"),
        PolicyEntry("agent.reflexion_interval", PolicyCategory.AI_CONFIG, "3", PolicyValueType.INT,
            "Reflexion 자기 평가 간격 (스텝)", "2", "10"),
        PolicyEntry("agent.complex_query_threshold", PolicyCategory.AI_CONFIG, "30", PolicyValueType.INT,
            "복잡 쿼리 판단 길이 (자 이상 → 에이전트 루프 사용)", "15", "100"),

        // =====================================================================
        // GATEWAY — AI 호출 중앙 게이트웨이 (AICallGateway, ProactiveScheduler)
        // =====================================================================
        PolicyEntry("gateway.max_calls_per_minute", PolicyCategory.GATEWAY, "10", PolicyValueType.INT,
            "분당 최대 클라우드 AI 호출 수 (전체 provider 합산)", "1", "60"),
        PolicyEntry("gateway.global_daily_tokens", PolicyCategory.GATEWAY, "300000", PolicyValueType.INT,
            "일일 글로벌 토큰 예산 (전체 클라우드 합산)", "50000", "2000000"),
        PolicyEntry("gateway.proactive_budget_cutoff", PolicyCategory.GATEWAY, "0.70", PolicyValueType.FLOAT,
            "PROACTIVE 호출 차단 예산 사용률 (이상이면 proactive 중단)", "0.30", "0.95"),
        PolicyEntry("gateway.thermal_throttle_temp", PolicyCategory.GATEWAY, "40", PolicyValueType.INT,
            "열 쓰로틀 온도 (C, 이상이면 proactive 차단)", "35", "50"),
        PolicyEntry("gateway.thermal_max_calls_per_minute", PolicyCategory.GATEWAY, "3", PolicyValueType.INT,
            "열 쓰로틀 시 분당 최대 호출 수", "1", "10"),
        PolicyEntry("gateway.max_concurrent_proactive", PolicyCategory.GATEWAY, "2", PolicyValueType.INT,
            "최대 동시 PROACTIVE AI 호출 수", "1", "5"),
        PolicyEntry("gateway.enabled", PolicyCategory.GATEWAY, "true", PolicyValueType.BOOLEAN,
            "AICallGateway 활성화 (false → 기존 직접 호출 허용)", null, null),

        PolicyEntry("gateway.min_cloud_interval_ms", PolicyCategory.GATEWAY, "60000", PolicyValueType.LONG,
            "클라우드 AI proactive 호출 최소 간격 (ms)", "10000", "600000"),
        PolicyEntry("gateway.budget_scale_50_multiplier", PolicyCategory.GATEWAY, "2.0", PolicyValueType.FLOAT,
            "예산 50%+ 시 인터벌 배율", "1.0", "10.0"),
        PolicyEntry("gateway.budget_scale_70_multiplier", PolicyCategory.GATEWAY, "4.0", PolicyValueType.FLOAT,
            "예산 70%+ 시 인터벌 배율", "1.0", "20.0"),
        PolicyEntry("gateway.budget_scale_90_suspend", PolicyCategory.GATEWAY, "true", PolicyValueType.BOOLEAN,
            "예산 90%+ 시 proactive 전면 중단", null, null),
        PolicyEntry("gateway.invisible_call_warn_ratio", PolicyCategory.GATEWAY, "0.50", PolicyValueType.FLOAT,
            "INTERNAL_ONLY 호출 비율 경고 임계값 (초과 시 proactive 감속)", "0.20", "0.90"),

        PolicyEntry("gateway.proactive_scheduler_enabled", PolicyCategory.GATEWAY, "true", PolicyValueType.BOOLEAN,
            "ProactiveScheduler 활성화 (false=모든 자율행동 정지)", null, null),
        PolicyEntry("gateway.value_gatekeeper_enabled", PolicyCategory.GATEWAY, "true", PolicyValueType.BOOLEAN,
            "ValueGatekeeper 활성화 (통계 기반 무의미 호출 차단)", null, null),
        PolicyEntry("gateway.duplicate_window_ms", PolicyCategory.GATEWAY, "120000", PolicyValueType.LONG,
            "동일 intent 중복 감지 윈도우 (ms)", "30000", "600000"),
        PolicyEntry("gateway.min_history_for_judge", PolicyCategory.GATEWAY, "5", PolicyValueType.INT,
            "가시율/유용율 판단에 필요한 최소 이력 수", "3", "20"),
        PolicyEntry("gateway.min_visibility_rate", PolicyCategory.GATEWAY, "0.10", PolicyValueType.FLOAT,
            "PROACTIVE+INTERNAL 차단 가시율 임계값 (이하면 무의미 루프)", "0.0", "0.50"),
        PolicyEntry("gateway.min_useful_rate", PolicyCategory.GATEWAY, "0.05", PolicyValueType.FLOAT,
            "PROACTIVE 차단 유용율 임계값 (사용자 긍정 반응 비율)", "0.0", "0.30"),
        PolicyEntry("gateway.ai_judge_enabled", PolicyCategory.GATEWAY, "true", PolicyValueType.BOOLEAN,
            "AI 보강 판단 활성화 (리모트→서버→엣지 cascade)", null, null),
        PolicyEntry("gateway.ai_judge_interval_ms", PolicyCategory.GATEWAY, "30000", PolicyValueType.LONG,
            "AI 보강 판단 최소 간격 (ms)", "10000", "300000"),

        // ── Storyteller Orchestrator ──
        PolicyEntry("storyteller.enabled", PolicyCategory.COMPANION, "true", PolicyValueType.BOOLEAN,
            "Storyteller 내러티브 엔진 활성화", null, null),
        PolicyEntry("storyteller.reflection_interval_ms", PolicyCategory.COMPANION, "300000", PolicyValueType.LONG,
            "주기적 리플렉션 beat 생성 간격 (ms)", "60000", "1800000"),
        PolicyEntry("storyteller.max_beats_per_chapter", PolicyCategory.COMPANION, "20", PolicyValueType.INT,
            "챕터당 최대 beat 수 (초과 시 리플렉션 스킵)", "5", "50"),
        PolicyEntry("storyteller.max_tokens_per_beat", PolicyCategory.COMPANION, "300", PolicyValueType.INT,
            "beat당 최대 AI 토큰 수", "100", "500"),
        PolicyEntry("storyteller.end_of_day_hour", PolicyCategory.COMPANION, "22", PolicyValueType.INT,
            "하루 마무리 요약 생성 시각 (24시간)", "18", "23"),
        PolicyEntry("storyteller.min_chapter_duration_ms", PolicyCategory.COMPANION, "120000", PolicyValueType.LONG,
            "최소 챕터 지속 시간 — 너무 짧은 전환 방지 (ms)", "30000", "600000"),
        PolicyEntry("storyteller.inactivity_dormant_ms", PolicyCategory.COMPANION, "180000", PolicyValueType.LONG,
            "비활성 → DORMANT 전이 시간 (ms, 기본 3분)", "60000", "600000"),
        PolicyEntry("storyteller.awakening_stabilize_ms", PolicyCategory.COMPANION, "5000", PolicyValueType.LONG,
            "AWAKENING → OBSERVING 센서 안정화 시간 (ms)", "1000", "30000"),
        PolicyEntry("storyteller.sleep_start_hour", PolicyCategory.COMPANION, "23", PolicyValueType.INT,
            "수면 시간대 시작 (시, 0-23)", "20", "23"),
        PolicyEntry("storyteller.sleep_end_hour", PolicyCategory.COMPANION, "6", PolicyValueType.INT,
            "수면 시간대 종료 (시, 0-23)", "4", "9"),

        // ── 원격 음성 서버 ──
        PolicyEntry("speech_server.host", PolicyCategory.SYSTEM, "100.121.84.80", PolicyValueType.STRING,
            "음성 처리 서버 IP (Tailscale)", null, null),
        PolicyEntry("speech_server.port", PolicyCategory.SYSTEM, "7860", PolicyValueType.INT,
            "음성 처리 서버 포트", "1024", "65535"),
        PolicyEntry("speech_server.enabled", PolicyCategory.SYSTEM, "true", PolicyValueType.BOOLEAN,
            "원격 음성 서버 활성화", null, null),
        PolicyEntry("speech_server.auto_fallback", PolicyCategory.SYSTEM, "true", PolicyValueType.BOOLEAN,
            "엣지 STT 실패 시 자동 원격 폴백", null, null),

        // ── 부트 레벨: 단계별 서비스 초기화 ──
        PolicyEntry("system.boot_level", PolicyCategory.SYSTEM, "5", PolicyValueType.INT,
            "부트 레벨 (1=DB, 2=HUD/TTS, 3=센서, 4=AI, 5=자율행동)", "1", "5")
    )
}

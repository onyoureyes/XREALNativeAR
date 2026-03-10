# :autonomy 모듈 — 자율 행동 (Strategy + Mission + Evolution)

## 모듈 개요
AI 에이전트의 자율 행동에 필요한 타입, 인터페이스, 템플릿을 정의하는 모듈.
미션 팀 구성, 전략 지시, 학습 결과 기록, 자기진화 타입 포함.
구현체(MissionConductor, OutcomeTracker, StrategistService 등)는 `:app`에 있다.

## 의존 관계
- 의존: `:core-models` (LifeSituation, ProviderId, OutcomeTypes), `:memory` (MemoryRecord)
- 이 모듈에 의존하는 모듈: `:app`

## 파일 목록
```
autonomy/src/main/kotlin/com/xreal/nativear/
├── evolution/
│   └── CapabilityTypes.kt     — CapabilityRequest, CapabilityType, RequestPriority, RequestStatus
├── learning/
│   └── IOutcomeRecorder.kt    — AI 개입 결과 기록/조회 인터페이스
├── mission/
│   ├── IMissionService.kt     — 미션 활성화/비활성화 인터페이스
│   ├── MissionTypes.kt        — Mission, MissionType, MissionState, AgentRole, AgentTask, MissionPlan
│   ├── MissionTemplates.kt    — 사전 정의 미션 팀 구성 (RUNNING_COACH, TRAVEL_GUIDE 등)
│   └── SharedMissionContext.kt — 에이전트 간 블랙보드 패턴 (ConcurrentHashMap)
└── strategist/
    └── Directive.kt           — AI 전략 지시 (instruction, rationale, confidence, TTL)
```

## 핵심 타입
- `Mission` — 미션 집합체 (type, state, plan, agentRoles, context)
- `AgentRole` — 에이전트 역할 정의 (roleName, providerId, systemPrompt, tools, rules)
- `MissionTemplateConfig` — 미션 템플릿 설정
- `MissionTemplates` — 5개 사전 정의 미션 (RUNNING_COACH, TRAVEL_GUIDE, EXPLORATION, DAILY_COMMUTE, SOCIAL_ENCOUNTER)
- `IOutcomeRecorder` — 개입 기록 + 효과성 조회 인터페이스
- `IMissionService` — 미션 CRUD 인터페이스
- `Directive` — 전략 지시 DTO (JSON 직렬화 포함)
- `CapabilityRequest` — 자기진화 요청 DTO

## 작업 범위
- **수정 가능**: autonomy/ 내부 파일만
- **생성 가능**: autonomy/src/main/, autonomy/src/test/ 하위만
- **수정 금지**: app/, core/, core-models/, memory/, 기타 모듈

## 확장 가능 작업
1. 단위 테스트 작성 (MissionTypes, Directive JSON 직렬화, SharedMissionContext 동시성)
2. 새 MissionTemplate 추가 (STUDY, SHOPPING 등)
3. IOutcomeRecorder 계약 테스트
4. CapabilityTypes 유효성 검증 테스트
5. 새 인터페이스 추출: IStrategyService, IEvolutionService (app에서 이동 후보)

## 빌드
```bash
JAVA_HOME="<your-jbr-path>" ./gradlew :autonomy:compileDebugKotlin
JAVA_HOME="<your-jbr-path>" ./gradlew :autonomy:test
```

## 소통 프로토콜
- 작업 완료 시 `SHARED/CHANGELOG.md`에 기록
- 다른 모듈 변경 필요 시 `SHARED/REQUESTS.md`에 요청 (직접 수정 금지)
- `SHARED/DECISIONS.md` 확인 후 작업 시작

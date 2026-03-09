# XREAL NativeAR — 아키텍처 원칙 및 구현 지침

## 경제적 제약
- **월 API 토큰 예산**: ~12만원 ($90 USD)
- **일일 예산**: ~$3 (Gemini Flash 기준 출력 750만 토큰)
- **디바이스**: Galaxy Fold 4 (RAM 12GB, 저장소 256GB)
- **절대 원칙**: 예산 초과 시 AI 호출을 자동 차단해야 함

---

## 원칙 1: AI 호출 게이트 (Call Gate)

모든 AI API 호출은 반드시 단일 게이트를 통과해야 한다.

```
[모든 AI 호출] → TokenBudgetTracker.canCall() → 허용/차단
                                                    ↓
                                              TokenOptimizer.shouldCallAI()
                                                    ↓
                                              SKIP / MINIMAL / STANDARD / ENRICHED
```

**규칙:**
- 일일 예산의 90%가 소진되면 ENRICHED 호출 차단
- 95% 소진되면 STANDARD도 차단, MINIMAL만 허용
- 100% 소진되면 모든 비필수 호출 차단 (TTS, 안전 경고만 허용)
- 캐시 히트 시 AI 호출 0 (TokenOptimizer.SKIP)

---

## 원칙 2: 반복 감지 우선 (Detect Before Call)

AI를 호출하기 전에 반드시 "이전에 본 것인가?"를 확인한다.

```
새 장면 → AnalysisCacheManager.detectSceneChange() → UNCHANGED?
                                                        ↓ YES
                                                    캐시 결과 반환 (토큰 0)
                                                        ↓ NO
                                                    FamiliarityEngine.getEntityContext()
                                                        ↓
                                                    "이미 47회 본 화분" → 깊이 조절
                                                        ↓
                                                    AI 호출 (축소된 프롬프트)
```

**규칙:**
- 동일 장면(Jaccard ≥ 0.95)은 AI 호출 안함
- 친숙도 FAMILIAR 이상 객체는 상세 설명 생략
- 이전 인사이트를 프롬프트에 포함하여 중복 방지
- 새로운 것이 없으면 새로움 엔진(NoveltyEngine)이 관점 전환

---

## 원칙 3: 배치 처리 (Batch, Don't Stream)

실시간성이 없는 작업은 반드시 배치로 처리한다.

| 작업 | 현재 | 목표 | 절약 |
|------|------|------|------|
| Strategist 반성 | 5분 | 15분 | 66% |
| Situation 딥 분류 | 5분 | 10분 | 50% |
| Mission 리플랜 | 5분 | 상황 변화 시만 | ~70% |
| 번역 | 매번 | 캐시 5분 TTL | ~60% |
| 메모리 압축 | 즉시 | 쓰로틀 3분 | 연쇄 방지 |

**규칙:**
- BatchProcessor를 통해 제출된 작업만 실행
- 동일 dedupKey 작업은 1분 내 중복 차단
- 동시 AI 호출 최대 2개
- 압축은 3분 쿨다운 (연쇄 폭발 방지)

---

## 원칙 4: 데이터는 유기적으로 조직한다

### 사람 중심 구조
사람에 대한 모든 데이터는 `person_id`로 추적 가능해야 한다.

```
persons (SceneDB) ─── 정체성의 원천
  ├── person_faces ─── 얼굴 임베딩
  ├── person_voices ─── 음성 임베딩
  ├── interactions ─── 만남 기록
  ├── relationship_profiles (UnifiedDB) ─── 관계 지능
  ├── conversation_journal (UnifiedDB) ─── 대화 기록
  └── entity_familiarity (UnifiedDB) ─── 친숙도
```

### DB 추가 규칙
- 새 테이블 생성 시 반드시 기존 테이블과의 FK 관계를 명시할 것
- 동일 데이터를 2곳에 저장하지 않을 것 (참조로 연결)
- JSON 컬럼은 최후 수단 (쿼리 불가능)
- 교차 DB 참조는 DatabaseIntegrityHelper로 정기 검증

---

## 원칙 5: 평가는 순환해야 한다

```
AI 개입 → 사용자 반응 (NOD/SHAKE/음성) → OutcomeTracker
    ↑                                           ↓
    │                                    StrategyRecord (효과도)
    │                                           ↓
    │                                    AgentPersonalityEvolution (학습)
    │                                           ↓
    └───────────── PersonaManager (다음 프롬프트에 반영) ◄──────┘
```

**규칙:**
- 모든 AI 개입은 반드시 intervention ID를 발급받을 것
- 60초 내 피드백 없으면 IGNORED 처리
- 효과도 < 30%인 전략은 프롬프트에 "이전 실패" 경고 포함
- 주 1회 에이전트 자기 반성 → 다음 주 프롬프트에 반영

---

## 원칙 6: 감정은 요주의 데이터

감정 데이터는 가장 신중하게 다뤄야 할 데이터이다.

**규칙:**
- 사용자 감정 상태가 STRESSED일 때: 도전적 질문 차단, 코칭 강도 최소화
- 감정 추세가 3일 연속 부정적: 브리핑에 경고, 부드러운 톤 강제
- 감정은 절대 단일 소스로 판단하지 않음 (HRV + 표정 + 음성 종합)
- 감정 데이터는 본인에게만 노출 (외부 동기화 제외)

---

## 원칙 7: 디바이스 한계를 존중한다

Galaxy Fold 4:
- RAM 12GB (OS + 백그라운드 앱 사용 후 가용 ~4GB)
- 온디바이스 모델: YOLO, Whisper, BlazeFace, FaceEmbedder, Pose, YAMNet, OCR, FER
- 인메모리 캐시 총량 < 200MB

**규칙:**
- LRU 캐시 크기: 분석 캐시 100개, 번역 캐시 200개
- 온디바이스 모델 동시 로드: 최대 4개 (나머지 lazy load)
- 벡터 검색: sqlite-vec로 로컬 처리 (API 불필요)
- 이미지 처리: 1280x960 MJPEG 그대로, 리사이즈 최소화

---

## 원칙 8: 새로움은 투자다

토큰을 절약하는 목적은 "안 쓰기 위해서"가 아니라
"더 가치 있는 곳에 쓰기 위해서"이다.

```
루틴 감지 → 분석 토큰 70% 절약
                    ↓
            절약한 토큰으로 새로움 주입
                    ↓
            철학적/과학적/예술적/역사적 관점
                    ↓
            사용자: "오, 이런 생각은 안 해봤는데"
```

**규칙:**
- 캐시 히트로 절약한 토큰의 30%를 NoveltyEngine에 재투자
- 루틴 중 15분마다 새로운 관점 1회
- 동일 카테고리 연속 3회 금지 (다양성 강제)
- 이전 인사이트 참조하여 절대 반복하지 않음

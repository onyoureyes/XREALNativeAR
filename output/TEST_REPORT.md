# Output Module Test Report

## 실행 결과
- **총 테스트**: 96개 (debug 48 + release 48)
- **통과**: 96개
- **실패**: 0개
- **빌드 명령**: `JAVA_HOME="F:/AndroidAndroid Studio/jbr" ./gradlew :output:test`

## 테스트 파일 (5개)

### 1. HUDModeTest (10 tests)
| 테스트 | 설명 |
|--------|------|
| HUDMode 전체 12개 값 존재 | enum 완전성 |
| HUDMode displayName 매핑 정확 | 12개 전체 한글명 검증 |
| HUDMode valueOf 역매핑 | String→enum |
| HUDWidget 전체 20개 값 존재 | enum 완전성 |
| HUDWidget region 매핑 정확 | 20개 전체 region 검증 |
| HUDRegion 전체 8개 값 존재 | enum 완전성 |
| HUDRegion 필수 값 포함 | 8개 값 이름 확인 |
| HUDTemplate 생성 기본값 확인 | isBuiltIn, createdBy 기본값 |
| HUDTemplate 커스텀 생성 | isBuiltIn=false, 위젯 수 |

### 2. HUDTemplateEngineTest (20 tests)
| 테스트 | 설명 |
|--------|------|
| 초기화 시 11개 built-in 템플릿 등록 | 템플릿 수 확인 |
| built-in 템플릿 ID 전체 확인 | 11개 ID 존재 |
| getTemplate으로 개별 템플릿 조회 | running 템플릿 상세 |
| 존재하지 않는 템플릿 조회 시 null | 에러 안전성 |
| RUNNING 상황에서 running 템플릿 반환 | 상황→템플릿 매핑 |
| MORNING_ROUTINE → briefing | 상황→템플릿 매핑 |
| AT_DESK_WORKING → work | 상황→템플릿 매핑 |
| SOCIAL_GATHERING → social | 상황→템플릿 매핑 |
| SLEEPING_PREP → minimal | 상황→템플릿 매핑 |
| 매핑되지 않은 상황에서 default 반환 | fallback |
| activateTemplate 후 상태 변경 | activeTemplate, currentMode |
| 같은 템플릿 재적용 시 스킵 | 중복 전환 방지 |
| switchMode로 모드 전환 | DEBUG 전환 |
| onSituationChanged 자동 전환 | RUNNING 상황 |
| 렌더러 등록 후 위젯 활성화 콜백 | IHUDWidgetRenderer |
| 템플릿 전환 시 이전 위젯 비활성화 콜백 | deactivate→activate 순서 |
| composeTemplate 커스텀 템플릿 생성 | isBuiltIn=false, CUSTOM 모드 |
| 커스텀 템플릿이 built-in보다 우선 | 우선순위 로직 |
| getCustomTemplates 커스텀만 반환 | 필터링 |
| saveTemplate/getAllTemplates | 저장/조회 |
| deactivateAll 에러 없음 | 안전성 |

### 3. HUDModeManagerTest (6 tests)
| 테스트 | 설명 |
|--------|------|
| start 시 DEFAULT 모드 활성화 | 초기 상태 |
| switchMode 수동 모드 전환 | RUNNING 전환 |
| toggleDebug DEFAULT→DEBUG | 토글 동작 |
| toggleDebug DEBUG→DEFAULT | 토글 복귀 |
| stop 후 에러 없음 | 종료 안전성 |
| SituationChanged 이벤트 간접 확인 | switchMode 연쇄 |

### 4. HUDPhysicsEngineTest (30 tests)
| 테스트 | 설명 |
|--------|------|
| addBody/getBody/removeBody/getAllBodies/clear | CRUD 기본 |
| addBody 커스텀 파라미터 | mass, friction, bounciness 등 |
| 중력 활성화 시 Y 속도 증가 | 물리 시뮬레이션 |
| 중력 비활성화 시 정지 유지 | 물리 무시 |
| static 바디 물리 무시 | isStatic=true |
| 4방향 경계 반발 (좌/우/상/하) | 벽 튕김 |
| 마찰로 속도 감소 | friction 계수 |
| 최소 속도 이하 시 정지 | MIN_VELOCITY |
| applyForce 속도 변경 | 힘 적용 |
| applyForce mass 반영 | F/m=a |
| static 바디 applyForce 무효 | 방어 로직 |
| 수명 만료 시 바디 제거 | lifetime |
| update 반환값에 만료 ID | expired 리스트 |
| setVelocity/setPosition | 직접 설정 |
| 스프링 앵커 복원력 | springK |
| setAnchor 앵커 변경 | 동적 앵커 |
| 속도 MAX_VELOCITY 클램핑 | 속도 제한 |
| 애니메이션 추가/조회 | addAnimation |
| 같은 타입 애니메이션 교체 | 중복 방지 |
| 다른 타입 애니메이션 공존 | 멀티 애니메이션 |
| 애니메이션 완료 시 자동 제거 | isComplete |
| SHAKE 오프셋 계산 | X축 진동 |
| FADE_OUT 알파 계산 | 투명도 감소 |
| SCALE_PULSE 스케일 계산 | 크기 맥동 |
| 대상 없는 오프셋/스케일/알파 | 기본값 반환 |
| removeBody 시 애니메이션 제거 | 연쇄 삭제 |

### 5. InteractionTypesTest (22 tests)
| 테스트 | 설명 |
|--------|------|
| PhysicsBody 생성 기본값 | 14개 필드 기본값 |
| PhysicsBody speed 계산 | 3-4-5 삼각형 |
| PhysicsBody anchorDistance | 앵커 거리 |
| AnimationType 10개 값 | enum 완전성 |
| ActiveAnimation progress/isComplete | 진행률 계산 |
| TriggerType 11개 값 | enum 완전성 |
| ActionType 17개 값 | enum 완전성 |
| InteractionRule 기본/커스텀 | 데이터 클래스 |
| RuleAction getFloat/getString/getInt | 파라미터 접근 |
| InteractionTemplate 기본값 | DB 캐시 데이터 |

### 6. FilamentMockTest (3 tests)
| 테스트 | 설명 |
|--------|------|
| MaterialFactory 초기 상태 | singleton 존재 |
| FilamentSceneManager PACER_ANCHOR_ID | 상수 "pacemaker_dot" |
| GhostRunnerEntity 상수 값 | RADIUS, BODY_HEIGHT, TOTAL_HEIGHT, SEGMENTS |

## Filament 네이티브 의존으로 테스트 불가 항목
- `FilamentRenderer.setup()` / `destroy()` / `setPose()` / `uploadCameraFrame()`
- `FilamentRenderer.computeProjectionMatrix()` — Filament.init() 필수
- `GhostRunnerEntity.createCapsuleMesh()` / `setPose()` / `setColor()`
- `MaterialFactory.getCameraBgMaterial()` / `getGhostMaterial()` / `getSolidColorMaterial()`
- `FilamentSceneManager.start()` — FilamentRenderer 인스턴스 필요

## 발견된 버그
- 없음

## 빌드 설정 변경
- `output/build.gradle`에 `testOptions { unitTests.returnDefaultValues = true }` 추가
  (android.util.Log 호출이 JVM 테스트에서 기본값 반환하도록)

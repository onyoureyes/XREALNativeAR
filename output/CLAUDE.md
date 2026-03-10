# Track D 임무: :output 모듈 테스트

## 너의 역할
:output 모듈의 단위 테스트를 작성하는 테스트 엔지니어.
이 모듈은 **HUD 프레임워크 + Filament 렌더러 + 물리 엔진**을 담당한다.
:core에 의존하며, Google Filament 3D 엔진을 사용한다.

## 중요 제약
- Filament 네이티브 라이브러리는 JVM 테스트에서 로드 불가.
- Filament 의존 클래스(FilamentRenderer, FilamentSceneManager 등)는 **Mock 또는 인터페이스 테스트**로 우회.
- 순수 로직(HUD 템플릿 매핑, 물리 계산, enum 매핑)을 우선 테스트한다.

## 작업 범위
- **수정 가능**: output/ 내부 파일만
- **생성 가능**: output/src/test/kotlin/ 하위만
- **수정 금지**: app/, core/, core-models/, input/, spatial/, AppModule.kt

## 테스트 대상 (우선순위순)

### 1. HUDMode.kt — HUD 모드/위젯/리전 매핑 (순수 enum + data class)
Filament 불필요. 가장 먼저 테스트 가능.
- HUDMode enum 전체 값 확인
- HUDWidget enum 전체 값 확인
- HUDRegion enum 전체 값 확인
- HUDTemplate — 모드별 위젯 구성 검증
- IHUDWidgetRenderer 인터페이스 Mock 구현 테스트

### 2. HUDTemplateEngine — 상황별 템플릿 전환 (핵심 로직)
- 11개 built-in 템플릿 존재 확인
- 상황(LifeSituation) → 템플릿 매핑
- 위젯 활성화/비활성화 호출 순서
- 중복 전환 방지 (같은 템플릿 재적용 스킵)
- 렌더러 등록/해제

### 3. HUDModeManager — 자동 모드 전환
- SituationChanged 이벤트 → 템플릿 전환 연동
- EventBus 구독 동작 (start/stop)
- 전환 쿨다운 검증

### 4. HUDPhysicsEngine — 2D 물리 시뮬레이션 (순수 수학)
Filament 불필요. 수학 로직만 테스트.
- PhysicsBody 생성 + 속성
- 충돌 판정 (AABB)
- 중력/마찰 시뮬레이션
- 경계 반사

### 5. InteractionTypes.kt — 인터랙션 데이터 타입
- PhysicsBody, AnimationType, InteractionRule 생성
- 타입 유효성 검증

### 6. FilamentRenderer / FilamentSceneManager / GhostRunnerEntity
- Filament 의존이므로 **인터페이스 레벨 Mock 테스트**
- 씬 명령 큐잉 로직 (Filament 없이 명령 축적 확인)
- MaterialFactory 매핑 로직

## 테스트 패턴
```kotlin
import org.junit.Test
import org.junit.Assert.*
import com.xreal.nativear.hud.*
import com.xreal.nativear.context.LifeSituation

class HUDTemplateEngineTest {
    @Test
    fun `WALKING 상황에서 올바른 위젯 활성화`() {
        val engine = HUDTemplateEngine()
        val mockRenderer = MockWidgetRenderer()
        engine.registerRenderer(mockRenderer)

        engine.applyTemplate(LifeSituation.WALKING)

        assertTrue(mockRenderer.activatedWidgets.isNotEmpty())
    }

    @Test
    fun `같은 템플릿 재적용 시 위젯 재활성화하지 않음`() {
        val engine = HUDTemplateEngine()
        val mockRenderer = MockWidgetRenderer()
        engine.registerRenderer(mockRenderer)

        engine.applyTemplate(LifeSituation.WALKING)
        val firstCount = mockRenderer.activationCount
        engine.applyTemplate(LifeSituation.WALKING) // 같은 상황
        assertEquals(firstCount, mockRenderer.activationCount)
    }
}

class HUDPhysicsEngineTest {
    @Test
    fun `두 AABB 겹침 → 충돌 판정`() {
        val engine = HUDPhysicsEngine()
        val bodyA = PhysicsBody(x = 10f, y = 10f, width = 20f, height = 20f)
        val bodyB = PhysicsBody(x = 20f, y = 20f, width = 20f, height = 20f)
        assertTrue(engine.checkCollision(bodyA, bodyB))
    }

    @Test
    fun `두 AABB 떨어짐 → 충돌 없음`() {
        val engine = HUDPhysicsEngine()
        val bodyA = PhysicsBody(x = 0f, y = 0f, width = 10f, height = 10f)
        val bodyB = PhysicsBody(x = 50f, y = 50f, width = 10f, height = 10f)
        assertFalse(engine.checkCollision(bodyA, bodyB))
    }
}

// Mock helper
class MockWidgetRenderer : IHUDWidgetRenderer {
    val activatedWidgets = mutableSetOf<HUDWidget>()
    var activationCount = 0
    override val supportedWidgets = HUDWidget.values().toSet()
    override fun onWidgetActivated(widget: HUDWidget) {
        activatedWidgets.add(widget)
        activationCount++
    }
    override fun onWidgetDeactivated(widget: HUDWidget) {
        activatedWidgets.remove(widget)
    }
}
```

## 빌드 & 실행
```bash
JAVA_HOME="F:/AndroidAndroid Studio/jbr" ./gradlew :output:test
```

## 완료 기준
- HUDTemplateEngine: 최소 6개 테스트 (핵심 로직)
- HUDPhysicsEngine: 최소 4개 테스트 (수학 로직)
- HUDMode/Widget/Region enum: 각 2개 이상
- `./gradlew :output:test` 전체 통과
- 커버리지 목표: 분기 기준 60%+ (Filament 의존 코드 제외)

## 소통 프로토콜 (필수)

### 내가 바꿨을 때
매 작업 완료 시 `SHARED/CHANGELOG.md`에 기록.
자기 모듈 외 파일 영향이 있으면 반드시 명시.

### 다른 모듈 변경이 필요할 때
1. `SHARED/REQUESTS.md`에 요청 작성 (상태: OPEN)
2. 지휘본부가 `SHARED/DECISIONS.md`에서 APPROVED할 때까지 대기
3. 차단되면 Mock/Fake로 우회해서 진행
4. **절대로 다른 모듈 파일을 직접 수정하지 않는다**

### 코드 작성 전 3단계 체크 (필수 — 위반 시 컴파일 에러 반복의 원인)

1. **읽기**: 참조할 클래스의 시그니처를 Read 도구로 확인. 추측 금지.
2. **생명주기**: "Koin 전? 후? 테스트에서는?" — `by lazy { getKoin() }` 금지 (영구 캐싱 위험)
3. **반문**: "이 수정이 같은 문제를 다른 곳에 만드는가?" — 문제 이동 ≠ 문제 해결

### 절대 금지 (Git 안전)
- `git clean`, `git checkout -- .`, `git reset --hard`, `git stash drop` 등 **작업물 삭제 명령 사용 금지**
- `git push --force` **절대 금지**
- 의심스러우면 삭제하지 말고 그대로 두어라

### 작업 시작 전
`SHARED/DECISIONS.md`를 읽어서 새로운 전체 공지나 승인된 요청 확인.

### 작업 완료 시
`output/TEST_REPORT.md`에 결과 요약 작성:
- 총 테스트 수, 통과/실패
- 발견된 버그 (있으면)
- Filament 의존으로 테스트 불가한 항목 목록
- 다른 모듈에 전달할 사항

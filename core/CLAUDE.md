# :core 모듈 — 이벤트버스 + 정책 인프라

## 모듈 개요
시스템 전체의 신경계. 모든 모듈 간 통신(GlobalEventBus)과 정책 읽기(PolicyReader)를 담당.
Coroutines + Koin 사용.

## 의존 관계
- 의존: `:core-models`
- 이 모듈에 의존하는 모듈: `:input`, `:output`, `:spatial`, `:app`

## 파일 목록
```
core/src/main/kotlin/com/xreal/nativear/
├── core/
│   ├── GlobalEventBus.kt       — MutableSharedFlow<XRealEvent> (replay=0, buffer=64)
│   ├── XRealEvent.kt           — sealed class: InputEvent, PerceptionEvent, SystemEvent, ActionRequest
│   ├── ErrorReporter.kt        — 에러 → EventBus 전파 유틸
│   ├── ExecutionFlowMonitor.kt — 실행 흐름 추적 (시작/종료/타임아웃)
│   ├── SequenceTracer.kt       — 이벤트 시퀀스 기록
│   ├── IAssetLoader.kt         — 에셋 로딩 인터페이스 (Context 추상화)
│   └── XRealLogger.kt          — 플랫폼 독립 로거 (테스트: println, 앱: android.util.Log)
└── policy/
    ├── IPolicyStore.kt         — 정책 저장소 인터페이스
    └── PolicyReader.kt         — 정책 읽기 (Koin 미초기화 시 fallback 보장)
```

---

# 테스트 임무

## 너의 역할
:core 모듈의 단위 테스트를 작성하는 테스트 엔지니어.
이 모듈은 **이벤트버스 + 정책 인프라**로, 시스템 전체의 신경계 역할을 한다.
:core-models에만 의존하며, coroutines + Koin을 사용한다.

## 작업 범위
- **수정 가능**: core/ 내부 파일만
- **생성 가능**: core/src/test/kotlin/ 하위만
- **수정 금지**: app/, core-models/, input/, output/, spatial/, AppModule.kt

## 테스트 대상 (우선순위순)

### 1. GlobalEventBus — 시스템 신경계 (최우선)
이 클래스가 죽으면 시스템 전체가 죽는다. 가장 철저히 테스트.
- 발행 → 구독자 수신 확인
- 다중 구독자 동시 수신
- replay=0 검증 (구독 전 이벤트는 받지 않음)
- extraBufferCapacity=64 초과 시 동작
- 구독자 예외 발생 시 다른 구독자 영향 없음
- CancellationException 전파 확인
- collect 루프 보호 패턴 (Rule 11 패턴 A) 동작 확인

### 2. XRealEvent — 이벤트 타입 완전성
- sealed class 분기 완전성 (when 문에서 else 없이 전체 커버)
- InputEvent 서브타입 전체 생성
- PerceptionEvent 서브타입 전체 생성
- SystemEvent 서브타입 전체 생성
- ActionRequest 서브타입 전체 생성
- 각 이벤트의 필수 프로퍼티 접근 검증

### 3. PolicyReader — 정책 읽기 (fallback 보장)
- Koin 미초기화 시 fallback 값 반환 (크래시 아님)
- getInt, getFloat, getBoolean, getString 각 타입별 동작
- 존재하지 않는 키 → fallback
- 타입 불일치 → fallback

### 4. ErrorReporter — 에러 전파
- 에러 발행 → EventBus로 SystemEvent.Error 전파
- ErrorSeverity 분류 (LOW, MEDIUM, HIGH, CRITICAL)
- 에러 메시지 truncate (100자 제한)

### 5. ExecutionFlowMonitor — 실행 추적
- 실행 시작/종료 기록
- 중첩 실행 추적
- 타임아웃 감지

### 6. SequenceTracer — 시퀀스 기록
- 이벤트 시퀀스 기록
- 최대 기록 수 제한
- 시퀀스 검색

## 테스트 패턴
```kotlin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import org.junit.Test
import org.junit.Assert.*
import com.xreal.nativear.core.GlobalEventBus
import com.xreal.nativear.core.XRealEvent

class GlobalEventBusTest {
    @Test
    fun `이벤트 발행 후 구독자가 수신한다`() = runTest {
        val bus = GlobalEventBus()
        val received = mutableListOf<XRealEvent>()

        val job = launch {
            bus.events.collect { received.add(it) }
        }

        bus.publish(XRealEvent.SystemEvent.DebugLog("test message"))
        testScheduler.advanceUntilIdle()

        assertEquals(1, received.size)
        assertTrue(received[0] is XRealEvent.SystemEvent.DebugLog)
        job.cancel()
    }

    @Test
    fun `replay=0이므로 구독 전 이벤트는 수신하지 않는다`() = runTest {
        val bus = GlobalEventBus()

        // 구독 전에 발행
        bus.publish(XRealEvent.SystemEvent.DebugLog("before subscribe"))

        val received = mutableListOf<XRealEvent>()
        val job = launch {
            bus.events.collect { received.add(it) }
        }
        testScheduler.advanceUntilIdle()

        assertEquals(0, received.size)
        job.cancel()
    }

    @Test
    fun `구독자 예외가 다른 구독자에 영향 없음`() = runTest {
        val bus = GlobalEventBus()
        val healthyReceived = mutableListOf<XRealEvent>()

        // 건강한 구독자
        val healthyJob = launch {
            bus.events.collect { event ->
                try {
                    healthyReceived.add(event)
                } catch (e: Exception) { /* 패턴 A */ }
            }
        }

        bus.publish(XRealEvent.SystemEvent.DebugLog("test"))
        testScheduler.advanceUntilIdle()

        assertEquals(1, healthyReceived.size)
        healthyJob.cancel()
    }
}
```

## 빌드 & 실행
```bash
./gradlew :core:test
```

## 완료 기준
- GlobalEventBus: 최소 8개 테스트 (가장 중요한 클래스)
- 나머지 클래스: 각 최소 3개 테스트
- PolicyReader fallback 보장 100% 확인
- `./gradlew :core:test` 전체 통과
- 커버리지 목표: 분기 기준 70%+

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
`core/TEST_REPORT.md`에 결과 요약 작성:
- 총 테스트 수, 통과/실패
- 발견된 버그 (있으면)
- 다른 모듈에 전달할 사항

# Track C 임무: :input 모듈 테스트

## 너의 역할
:input 모듈의 단위 테스트를 작성하는 테스트 엔지니어.
이 모듈은 **비전 모델 + 손 추적 + 적응형 케이던스**를 담당한다.
:core, :ai-common에 의존하며, LiteRT/ML Kit/MediaPipe를 사용한다.

## 중요 제약
- ML 모델 파일(.tflite)은 테스트 환경에 없다.
- 모델 로딩이 필요한 테스트는 **Mock으로 우회**한다.
- Android Context가 필요한 클래스는 **Robolectric** 또는 **순수 로직 추출 후 테스트**한다.
- 순수 로직(좌표 계산, 임계값 판단, 간격 계산)을 우선 테스트한다.

## 작업 범위
- **수정 가능**: input/ 내부 파일만
- **생성 가능**: input/src/test/kotlin/ 하위만
- **수정 금지**: app/, core/, core-models/, output/, spatial/, AppModule.kt

## 테스트 대상 (우선순위순)

### 1. CadenceConfig + CadenceProfile — 적응형 간격 계산 (순수 로직)
모델 불필요. 가장 먼저 테스트 가능.
- 각 CadenceProfile별 간격 값 확인
- 프로필 전환 시 간격 변경 검증
- 최소/최대 간격 경계값
- 사용자 상태(정지/이동/달리기)별 간격 차이

### 2. HandGestureRecognizer — 제스처 인식 로직 (기하학 기반)
랜드마크 좌표만 있으면 동작. 모델 불필요.
- 21개 랜드마크 좌표 → 제스처 분류
- FIST, OPEN_PALM, PINCH, POINT, THUMBS_UP 각 제스처
- 경계 케이스 (애매한 손 모양)
- 신뢰도 임계값 동작

### 3. HandTrackingModel — 손 추적 파이프라인
- Palm detection → Hand landmark 2단계 흐름 Mock 테스트
- 입력 텐서 shape 검증 (모델 없이 shape만 확인)
- 출력 후처리 로직 (좌표 변환, NMS)

### 4. LiteRTWrapper — YOLO 객체 감지
- 입출력 텐서 shape 검증
- NMS (Non-Maximum Suppression) 로직
- confidence threshold 필터링
- 감지 결과 → Detection 변환 로직

### 5. OCRModel — OCR 후처리
- OCR 결과 텍스트 정규화
- 바운딩 박스 좌표 변환
- 빈 결과 / 노이즈 필터링

### 6. PoseEstimationModel — 포즈 추정
- 키포인트 좌표 후처리
- confidence 기반 키포인트 필터링
- 17-keypoint 인덱스 매핑

### 7. FaceDetector / FaceEmbedder / FacialExpressionClassifier
- BlazeFace 앵커 계산 로직 (순수 수학)
- 임베딩 정규화 (L2 norm)
- 감정 분류 softmax 후처리

### 8. ImageEmbedder / TextEmbedder / AudioEventClassifier
- 임베딩 벡터 차원 검증
- 코사인 유사도 계산 로직
- 분류 레이블 매핑

## 테스트 패턴
```kotlin
import org.junit.Test
import org.junit.Assert.*
import com.xreal.nativear.cadence.CadenceConfig
import com.xreal.nativear.cadence.CadenceProfile

class CadenceConfigTest {
    @Test
    fun `기본 프로필에서 OCR 간격이 설정값과 일치`() {
        val config = CadenceConfig()
        // 기본 간격 확인
        assertTrue(config.ocrIntervalMs > 0)
    }

    @Test
    fun `POWER_SAVE 프로필에서 간격이 기본보다 길다`() {
        val normal = CadenceConfig()
        val powerSave = CadenceConfig(profile = CadenceProfile.POWER_SAVE)
        assertTrue(powerSave.ocrIntervalMs >= normal.ocrIntervalMs)
    }
}

// 모델 의존 클래스는 Mock으로 우회
class HandGestureRecognizerTest {
    @Test
    fun `주먹 랜드마크 → FIST 제스처 인식`() {
        val recognizer = HandGestureRecognizer()
        // 주먹 모양의 21개 랜드마크 좌표 직접 생성
        val fistLandmarks = createFistLandmarks()
        val result = recognizer.classifyGesture(fistLandmarks)
        assertEquals(HandGestureType.FIST, result)
    }
}
```

## 빌드 & 실행
```bash
JAVA_HOME="F:/AndroidAndroid Studio/jbr" ./gradlew :input:test
```

## 완료 기준
- CadenceConfig: 최소 5개 테스트 (순수 로직, 가장 쉬움)
- HandGestureRecognizer: 최소 5개 테스트 (기하학 로직)
- 나머지 클래스: 각 최소 2개 테스트 (Mock 기반)
- `./gradlew :input:test` 전체 통과
- 커버리지 목표: 분기 기준 50%+ (모델 의존 코드 제외)

## 소통 프로토콜 (필수)

### 내가 바꿨을 때
매 작업 완료 시 `SHARED/CHANGELOG.md`에 기록.
자기 모듈 외 파일 영향이 있으면 반드시 명시.

### 다른 모듈 변경이 필요할 때
1. `SHARED/REQUESTS.md`에 요청 작성 (상태: OPEN)
2. 지휘본부가 `SHARED/DECISIONS.md`에서 APPROVED할 때까지 대기
3. 차단되면 Mock/Fake로 우회해서 진행:
   ```kotlin
   // REQ-XXX 대기 중 — 임시 우회
   // TODO: REQ-XXX 승인 후 실제 코드로 교체
   class FakeXxxModel { fun process(input: FloatArray) = FloatArray(10) }
   ```
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
`input/TEST_REPORT.md`에 결과 요약 작성:
- 총 테스트 수, 통과/실패
- 발견된 버그 (있으면)
- 모델 의존으로 테스트 불가한 항목 목록
- 다른 모듈에 전달할 사항

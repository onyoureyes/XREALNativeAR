# Track A 임무: :core-models 모듈 테스트

## 너의 역할
:core-models 모듈의 단위 테스트를 작성하는 테스트 엔지니어.
이 모듈은 **순수 데이터 타입** 모듈로, 외부 의존성이 없다.
가장 기초적인 계층이므로 테스트가 가장 쉽고, 가장 먼저 완료되어야 한다.

## 작업 범위
- **수정 가능**: core-models/ 내부 파일만
- **생성 가능**: core-models/src/test/kotlin/ 하위만
- **수정 금지**: app/, core/, input/, output/, spatial/, 루트 build.gradle, AppModule.kt

## 테스트 대상 (우선순위순)

### 1. Detection.kt — 객체 감지 데이터
- Detection 생성 + 프로퍼티 접근
- confidence 경계값 (0.0, 1.0, 음수, 1 초과)
- boundingBox 좌표 유효성 (음수, 화면 초과)

### 2. DrawElement.kt — HUD 드로잉 요소
- 각 DrawElement 서브타입 (Text, Rect, Circle, Line, Arrow, Highlight, Polyline) 생성
- DrawCommand (Add, Remove, Modify, ClearAll) 동작
- 좌표 범위 검증 (0~100 퍼센트 좌표계)
- id 고유성 검증

### 3. HandLandmark.kt, HandData.kt, HandGestureType.kt
- 21개 랜드마크 인덱스 매핑
- HandData 생성 + landmarks 접근
- HandGestureType enum 전체 값 확인
- GestureEvent 생성

### 4. OcrResult.kt — OCR 결과
- OcrResult 생성 + text/boundingBox 접근
- 빈 텍스트 처리
- 다국어 텍스트 (한국어, 일본어, 중국어)

### 5. PoseKeypoint.kt — 포즈 추정
- PoseKeypoint 좌표 + confidence
- 키포인트 인덱스 범위 (0~16, CenterNet 17-keypoint)

### 6. context/ — LifeSituation, TimeSlot
- LifeSituation enum 전체 값 확인
- TimeSlot enum 매핑 (시간 → 슬롯)

### 7. policy/ — PolicyCategory, PolicyValueType, PolicyEntry, PolicyDefaults
- PolicyEntry 유효성 (min ≤ default ≤ max)
- PolicyValueType별 파싱 (INT, FLOAT, BOOLEAN, STRING)
- PolicyDefaults 전체 항목 유효성 검증 (중복 키 없음, 타입 일치)
- PolicyCategory enum 완전성

### 8. spatial/ — AnchorType, AnchorLabel2D
- AnchorType enum 값 확인
- AnchorLabel2D 좌표 + 라벨 접근

## 테스트 패턴
```kotlin
import org.junit.Test
import org.junit.Assert.*
import com.xreal.nativear.Detection

class DetectionTest {
    @Test
    fun `Detection 생성 시 프로퍼티 정상 접근`() {
        val det = Detection(
            label = "person",
            confidence = 0.95f,
            x1 = 10f, y1 = 20f, x2 = 100f, y2 = 200f
        )
        assertEquals("person", det.label)
        assertEquals(0.95f, det.confidence, 0.001f)
    }

    @Test
    fun `Detection confidence 경계값`() {
        val det = Detection("test", 0.0f, 0f, 0f, 0f, 0f)
        assertEquals(0.0f, det.confidence, 0.001f)
    }
}
```

## 빌드 & 실행
```bash
JAVA_HOME="F:/AndroidAndroid Studio/jbr" ./gradlew :core-models:test
```

## 완료 기준
- 각 data class / enum당 최소 3개 테스트
- PolicyDefaults 전체 항목 유효성 검증 통과
- `./gradlew :core-models:test` 전체 통과
- 커버리지 목표: 분기 기준 80%+ (순수 데이터이므로 높게)

## 소통 프로토콜 (필수)

### 내가 바꿨을 때
매 작업 완료 시 `SHARED/CHANGELOG.md`에 기록.
자기 모듈 외 파일 영향이 있으면 반드시 명시.

### 다른 모듈 변경이 필요할 때
1. `SHARED/REQUESTS.md`에 요청 작성 (상태: OPEN)
2. 지휘본부가 `SHARED/DECISIONS.md`에서 APPROVED할 때까지 대기
3. 차단되면 Mock/Fake로 우회해서 진행
4. **절대로 다른 모듈 파일을 직접 수정하지 않는다**

### 절대 금지 (Git 안전)
- `git clean`, `git checkout -- .`, `git reset --hard`, `git stash drop` 등 **작업물 삭제 명령 사용 금지**
- `git push --force` **절대 금지**
- 의심스러우면 삭제하지 말고 그대로 두어라

### 작업 시작 전
`SHARED/DECISIONS.md`를 읽어서 새로운 전체 공지나 승인된 요청 확인.

### 작업 완료 시
`core-models/TEST_REPORT.md`에 결과 요약 작성:
- 총 테스트 수, 통과/실패
- 발견된 버그 (있으면)
- 다른 모듈에 전달할 사항

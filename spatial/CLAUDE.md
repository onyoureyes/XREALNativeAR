# Track E 임무: :spatial 모듈 테스트

## 너의 역할
:spatial 모듈의 단위 테스트를 작성하는 테스트 엔지니어.
이 모듈은 **3D 공간 앵커 + 경로 추적 + 드리프트 보정 + 장소 인식**을 담당한다.
:core, :input, :ai-common, :xreal-hardware-standalone에 의존한다.

## 중요 제약
- 하드웨어(IMU, 카메라)는 테스트 환경에 없다.
- 센서 데이터 의존 부분은 **Mock/Fake 센서 데이터**로 테스트한다.
- 이 모듈의 핵심은 **수학 로직**(행렬 연산, 좌표 변환, 거리 계산)이므로 JVM 테스트로 충분하다.
- ISpatialDatabase는 app에서 구현(SceneDatabase)하므로, 테스트에서는 Fake 구현체 사용.

## 작업 범위
- **수정 가능**: spatial/ 내부 파일만
- **생성 가능**: spatial/src/test/kotlin/ 하위만
- **수정 금지**: app/, core/, core-models/, input/, output/, AppModule.kt

## 테스트 대상 (우선순위순)

### 1. CameraModel — 핀홀 카메라 모델 (순수 수학, 최우선)
외부 의존 없음. 바로 테스트 가능.
- 3D→2D 투영 (projectPoint)
- 2D→3D 역투영 (unprojectPoint)
- 투영 → 역투영 왕복 일관성
- 화면 경계 밖 좌표 처리
- 렌즈 왜곡 보정 (있다면)

### 2. PoseTransform — 포즈 행렬 + 좌표계 변환 (순수 수학)
- 4x4 행렬 곱셈
- 역행렬 계산 + 검증 (A * A^-1 = I)
- 쿼터니언 → 회전행렬 변환
- 좌표계 변환 (OpenGL ↔ Android ↔ AR)

### 3. DriftCorrection.kt — 드리프트 보정 수학
- CorrectedPose 생성 + 보정 적용
- DriftStats 누적 계산
- QuaternionUtils — slerp 보간, 정규화, 곱셈
- 보정 전후 포즈 차이 측정

### 4. DepthPriors — 카테고리별 깊이 추정 (룩업 테이블)
- 객체 카테고리 → 예상 깊이 매핑 (person=1.7m, car=4.5m 등)
- 알 수 없는 카테고리 → 기본값
- 깊이 범위 유효성 (양수, 합리적 범위)

### 5. SpatialAnchor + SpatialAnchorRecord — 앵커 데이터
- 앵커 생성 (ID, 위치, 타입)
- AnchorPersistenceLevel 3단계 검증
- PoseState 상태 전이

### 6. PathTracker + PathState — 경로 추적
- 웨이포인트 추가 → 경로 길이 계산
- 경로 상태 전이 (IDLE → TRACKING → PAUSED → COMPLETED)
- 누적 거리 계산 정확도
- PathWaypoint 시간순 정렬

### 7. SpatialAnchorManager — 앵커 관리 (Mock DB)
- 앵커 생성 → 저장 → 조회 (FakeSpatialDatabase 사용)
- 앵커 재투영 로직
- 앵커 병합 (근접 앵커 통합)

### 8. PlaceRecognitionManager — 장소 인식 (Mock DB)
- PlaceSignature 생성 + 비교
- PlaceMatchResult 유사도 점수 계산
- 크로스세션 매칭 시뮬레이션

### 9. DriftCorrectionManager — 통합 보정
- 자기 보정 (MagneticHeadingProvider Mock)
- 비주얼 루프 클로저 (VisualLoopCloser Mock)
- 보정 적용 → 포즈 업데이트

### 10. VisualLoopCloser — 루프 감지
- 이미지 임베딩 유사도 → 루프 판정
- 임계값 기반 필터링
- 최소 거리 제한 (가까운 프레임 무시)

## Fake 구현체 (테스트용)
```kotlin
// ISpatialDatabase의 Fake 구현
class FakeSpatialDatabase : ISpatialDatabase {
    private val anchors = mutableMapOf<String, SpatialAnchorRecord>()
    private val places = mutableListOf<PlaceSignatureRecord>()

    override fun insertSpatialAnchor(record: SpatialAnchorRecord) {
        anchors[record.anchorId] = record
    }
    override fun getSpatialAnchorsNear(x: Float, y: Float, z: Float, radius: Float): List<SpatialAnchorRecord> {
        return anchors.values.filter { /* 거리 계산 */ true }
    }
    // ... 나머지 8개 override
}
```

## 테스트 패턴
```kotlin
import org.junit.Test
import org.junit.Assert.*
import com.xreal.nativear.spatial.CameraModel
import com.xreal.nativear.spatial.PoseTransform

class CameraModelTest {
    @Test
    fun `3D 투영 후 역투영하면 원래 좌표 복원`() {
        val camera = CameraModel(
            fx = 500f, fy = 500f, cx = 320f, cy = 240f,
            width = 640, height = 480
        )
        val point3D = floatArrayOf(1.0f, 2.0f, 5.0f) // x, y, z
        val projected = camera.projectPoint(point3D)
        val unprojected = camera.unprojectPoint(projected[0], projected[1], 5.0f)

        assertEquals(point3D[0], unprojected[0], 0.01f)
        assertEquals(point3D[1], unprojected[1], 0.01f)
        assertEquals(point3D[2], unprojected[2], 0.01f)
    }
}

class PoseTransformTest {
    @Test
    fun `단위 행렬 곱셈은 원본 유지`() {
        val identity = PoseTransform.identity()
        val pose = PoseTransform.fromTranslation(1f, 2f, 3f)
        val result = identity.multiply(pose)
        assertEquals(1f, result.translationX, 0.001f)
        assertEquals(2f, result.translationY, 0.001f)
        assertEquals(3f, result.translationZ, 0.001f)
    }

    @Test
    fun `역행렬 곱하면 단위행렬`() {
        val pose = PoseTransform.fromTranslation(5f, 3f, 1f)
        val inverse = pose.inverse()
        val result = pose.multiply(inverse)
        assertTrue(result.isIdentity(tolerance = 0.001f))
    }
}
```

## 빌드 & 실행
```bash
JAVA_HOME="F:/AndroidAndroid Studio/jbr" ./gradlew :spatial:test
```

## 완료 기준
- CameraModel: 최소 5개 테스트 (투영/역투영/경계)
- PoseTransform: 최소 5개 테스트 (행렬/쿼터니언)
- DriftCorrection 수학: 최소 4개 테스트
- PathTracker: 최소 3개 테스트
- 나머지 클래스: 각 최소 2개 테스트 (Mock 기반)
- `./gradlew :spatial:test` 전체 통과
- 커버리지 목표: 분기 기준 55%+ (하드웨어 의존 코드 제외)

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
`spatial/TEST_REPORT.md`에 결과 요약 작성:
- 총 테스트 수, 통과/실패
- 발견된 버그 (있으면)
- 하드웨어 의존으로 테스트 불가한 항목 목록
- ISpatialDatabase Fake 구현 시 발견된 인터페이스 개선점
- 다른 모듈에 전달할 사항

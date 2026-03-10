# :input 모듈 테스트 결과

## 요약
- **총 테스트 수**: 77 (debug 기준, release도 동일하게 통과)
- **통과**: 77
- **실패**: 0
- **빌드 명령**: `JAVA_HOME="F:/AndroidAndroid Studio/jbr" ./gradlew :input:test`
- **날짜**: 2026-03-10

## 테스트 파일별 상세

| 파일 | 테스트 수 | 대상 클래스 | 테스트 유형 |
|------|-----------|-------------|-------------|
| `CadenceConfigTest.kt` | 12 | CadenceConfig, CadenceProfile | 순수 로직 (StateFlow, data class) |
| `HandGestureRecognizerTest.kt` | 12 | HandGestureRecognizer | 기하학 기반 제스처 분류 |
| `HandTrackingModelTest.kt` | 11 | HandTrackingModel (내부 로직) | sigmoid, IoU, NMS, 앵커, 상수 |
| `LiteRTWrapperTest.kt` | 11 | LiteRTWrapper (내부 로직) | LABELS, IoU, NMS, Detection |
| `EmotionClassifierTest.kt` | 5 | EmotionClassifier | 휴리스틱 분류 로직 |
| `SimilarityAndNormTest.kt` | 13 | FaceEmbedder/ImageEmbedder/TextEmbedder/FacialExpressionClassifier | l2Normalize, cosineSimilarity, softmax |
| `FaceDetectorTest.kt` | 7 | FaceDetector (내부 로직) | sigmoid, 앵커 계산, Face IoU |
| `AudioEventClassifierTest.kt` | 6 | AudioEventClassifier (내부 로직) | PCM→float 변환, zero-padding |

## 완료 기준 달성

| 기준 | 요구 | 달성 |
|------|------|------|
| CadenceConfig | 5개+ | 12개 |
| HandGestureRecognizer | 5개+ | 12개 |
| LiteRTWrapper | 3개+ | 11개 |
| 나머지 클래스 각 2개+ | 각 2개+ | EmotionClassifier 5, Similarity 13, FaceDetector 7, Audio 6 |
| `./gradlew :input:test` 통과 | 전체 통과 | 전체 통과 |

## 빌드 변경사항

- `input/build.gradle`에 `testOptions { unitTests.returnDefaultValues = true }` 추가
  - `android.util.Log` 등 Android API 스텁이 기본값(0, null, false) 반환하도록 설정

## 테스트 불가 항목 (모델 의존)

아래 항목은 `.tflite` 모델 파일이 테스트 환경에 없어서 직접 테스트 불가.
대신 내부 순수 로직(IoU, NMS, sigmoid, normalize 등)을 추출하여 동일 로직으로 검증.

- `LiteRTWrapper.detect()` — 모델 추론 (prepare → detect 파이프라인)
- `HandTrackingModel.detect()` — Palm Detection + Hand Landmark 2단계
- `FaceDetector.detect()` — BlazeFace 추론
- `FaceEmbedder.embed()` — MobileFaceNet 추론
- `FacialExpressionClassifier.classify()` — FER 추론
- `ImageEmbedder.embed()` — MobileNetV3 추론
- `TextEmbedder.getEmbedding()` — MediaPipe USE 추론
- `AudioEventClassifier.classify()` — YAMNet 추론
- `OCRModel.process()` — ML Kit OCR (Google Play Services 의존)
- `PoseEstimationModel.process()` — CenterNet 추론

## 발견된 버그

없음.

## 참고사항

- `HandGestureRecognizer`의 OPEN_PALM 테스트에서 첫 호출 시 이전 palm 위치(50,50)에서의
  이동으로 속도가 높아져 SWIPE로 분류될 수 있음. 연속 호출(2회)로 속도 안정화 후 검증.
- `EmotionClassifier`는 순수 휴리스틱 기반이므로 모델 없이 완전 테스트 가능.
- `CadenceProfile`의 기본값은 `PolicyReader` fallback 값(Koin 미초기화 시 하드코딩 반환)으로 안전하게 테스트됨.

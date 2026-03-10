# :memory 모듈 — 메모리 인터페이스 + DTO

## 모듈 개요
앱 전체의 메모리 접근 인터페이스와 데이터 타입을 정의하는 모듈.
구현체(SqliteMemoryStore)는 `:app`에 있고, 이 모듈은 **순수 인터페이스 + DTO만** 포함.

## 의존 관계
- 의존: 없음 (최하위 계층)
- 이 모듈에 의존하는 모듈: `:autonomy`, `:app`

## 파일 목록
```
memory/src/main/kotlin/com/xreal/nativear/memory/
├── api/
│   ├── IMemoryStore.kt      — 메모리 CRUD 인터페이스 (save, search, getAll, delete 등)
│   └── MemoryRecord.kt      — DTO: MemoryRecord, SearchResult, ExtractedFact 등
└── MemoryImportanceScorer.kt — 메모리 중요도 점수 계산 (MemoryRecord 기반)
```

## 핵심 타입
- `IMemoryStore` — 메모리 저장/검색/삭제 통합 인터페이스
- `IMemoryCompaction` — 압축/요약 인터페이스
- `IMemoryExtractor` — 사실 추출 인터페이스
- `MemoryRecord` — Android 의존 없는 메모리 DTO (timestamp, role, content, metadata, lat/lon, personaId, sessionId)
- `SearchResult` — 검색 결과 (record + score + source)
- `MemoryImportanceScorer` — 규칙 기반 중요도 점수 (0.0~1.0)

## 작업 범위
- **수정 가능**: memory/ 내부 파일만
- **생성 가능**: memory/src/main/, memory/src/test/ 하위만
- **수정 금지**: app/, core/, core-models/, 기타 모듈

## 확장 가능 작업
1. MemoryImportanceScorer 테스트 작성
2. IMemoryStore 계약 테스트 (mock 기반)
3. MemoryRecord 직렬화/역직렬화 테스트
4. 새 인터페이스 추가 시 `:app`의 SqliteMemoryStore 구현 필요 → SHARED/REQUESTS.md에 요청

## 빌드
```bash
JAVA_HOME="<your-jbr-path>" ./gradlew :memory:compileDebugKotlin
JAVA_HOME="<your-jbr-path>" ./gradlew :memory:test
```

## 소통 프로토콜
- 작업 완료 시 `SHARED/CHANGELOG.md`에 기록
- 다른 모듈 변경 필요 시 `SHARED/REQUESTS.md`에 요청 (직접 수정 금지)
- `SHARED/DECISIONS.md` 확인 후 작업 시작

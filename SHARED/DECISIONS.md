# 지휘본부 결정 사항 (DECISIONS)

> 사용자(지휘본부)만 이 파일을 수정한다.
> 각 Track은 작업 시작 전 이 파일을 읽어서 새로운 결정이 있는지 확인한다.

## 형식

```
### DEC-번호 | YYYY-MM-DD
- 유형: 요청 응답 | 전체 공지 | 정책 변경
- 관련: REQ-번호 (해당 시)
- 결정: APPROVED / DENIED / DEFERRED
- 내용: 구체적 지시 사항
- 조건: 실행 시 지켜야 할 조건 (해당 시)
```

## 전체 공지

### DEC-000 | 2026-03-10
- 유형: 전체 공지
- 내용: 병렬 테스트 작업 개시. 각 Track은 자기 모듈 CLAUDE.md를 숙지하고 작업 시작.
- 규칙:
  1. 자기 모듈 외 파일 수정 금지
  2. 변경 시 SHARED/CHANGELOG.md 기록 필수
  3. 다른 모듈 변경 필요 시 SHARED/REQUESTS.md에 요청
  4. 인터페이스 시그니처 변경은 반드시 지휘본부 승인 필요
  5. PolicyDefaults.kt, XRealEvent.kt, AppModule.kt 직접 수정 금지
  6. **절대 금지: `git clean`, `git checkout -- .`, `git reset --hard`, `git stash drop` 등 작업물 삭제 명령 사용 금지. 다른 Track의 uncommitted 작업이 소실될 수 있음.**
  7. **`git push --force` 절대 금지. 다른 Track의 push된 커밋이 사라질 수 있음.**

## 결정 목록

(아직 없음 — 요청 접수 시 여기에 판결)

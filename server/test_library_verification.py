#!/usr/bin/env python3
import sys, io
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")
"""
XREAL Library Verification -오픈소스 라이브러리 공식 API 준수 검증

모든 서버 코드가 공식 문서/예제대로 라이브러리를 사용하는지 확인.
LLM 서버 없이 실행 가능한 항목은 실제 동작까지 검증.

Usage:
    cd server/
    python test_library_verification.py              # 전체 실행
    python test_library_verification.py --quick       # import + 시그니처만 검증 (30초)
    python test_library_verification.py --fix 12      # 특정 Fix # 만 검증

검증 대상 (20개 서버 파일, 14개 라이브러리):
  Fix #1:  LangGraph -MemorySaver 제거 확인
  Fix #2:  CrewAI -native LLM 사용 확인 (ChatOpenAI 미사용)
  Fix #3:  ChromaDB -$contains 미사용, Python 후처리 태그 필터
  Fix #5:  LangChain -@tool(parse_docstring=True) 확인
  Fix #6:  LangChain -Optional 타입 힌트 확인
  Fix #7:  llama.cpp -GET /health 헬스체크 (토큰 미소비)
  Fix #8:  llama.cpp -temperature 명시
  Fix #9:  CrewAI -asyncio.to_thread 사용 확인
  Fix #10: CrewAI -result.raw 사용 확인
  Fix #11: Graphiti -search() → list 직접 순회
  Fix #12: orchestrator.py -global 선언 완전성
  Fix #13: NeuralProphet -neuralprophet.save() 공식 API
  Fix #14: PyTorch Forecasting -to_dataloader(train=True/False)
  Fix #15: DuckDB -파라미터화 쿼리 (SQL injection 방지)
  Fix #16: PyTorch Lightning -gradient_clip_val 권장값
  Fix #18: mcp_server.py -하드코딩 API 키 제거

  Lib: Mem0 -API 호출 패턴 정합성
  Lib: BERTopic -fit_transform + topics_over_time 동작
  Lib: STUMPY -stump() + mp 구조 검증
  Lib: MCP FastMCP -import + 데코레이터 패턴
  Lib: EpisodeStore -SQLite CRUD
  Lib: SemanticCardStore -ChromaDB 하이브리드 검색
"""

import ast
import inspect
import importlib
import sys
import re
import textwrap
import time
import argparse
import traceback
from pathlib import Path
from typing import Optional

# ─── 결과 집계 ───

class TestResult:
    def __init__(self):
        self.passed = []
        self.failed = []
        self.skipped = []

    def ok(self, name, detail=""):
        self.passed.append((name, detail))
        print(f"  [PASS] {name}" + (f" -{detail}" if detail else ""))

    def fail(self, name, detail=""):
        self.failed.append((name, detail))
        print(f"  [FAIL] {name}" + (f" -{detail}" if detail else ""))

    def skip(self, name, detail=""):
        self.skipped.append((name, detail))
        print(f"  [SKIP]  {name}" + (f" -{detail}" if detail else ""))

    def summary(self):
        total = len(self.passed) + len(self.failed) + len(self.skipped)
        print(f"\n{'='*60}")
        print(f"  VERIFICATION SUMMARY")
        print(f"{'='*60}")
        print(f"  [PASS] Passed:  {len(self.passed)}")
        print(f"  [FAIL] Failed:  {len(self.failed)}")
        print(f"  [SKIP]  Skipped: {len(self.skipped)}")
        print(f"  Total:   {total}")

        if self.failed:
            print(f"\n  Failed items:")
            for name, detail in self.failed:
                print(f"    - {name}: {detail}")

        if len(self.failed) == 0:
            print(f"\n  All verifications passed!")
        else:
            print(f"\n  {len(self.failed)} issues remain.")

        return len(self.failed) == 0


R = TestResult()


def header(title):
    print(f"\n{'─'*60}")
    print(f"  {title}")
    print(f"{'─'*60}")


# ─── AST 유틸: 소스 코드를 파싱하여 패턴 검증 ───

def read_source(filename: str) -> str:
    """서버 디렉토리의 Python 파일 소스 읽기"""
    path = Path(__file__).parent / filename
    if not path.exists():
        raise FileNotFoundError(f"{path}")
    return path.read_text(encoding="utf-8")


def source_contains(src: str, pattern: str) -> bool:
    """소스 코드에 특정 문자열 패턴이 있는지"""
    return pattern in src


def source_not_contains(src: str, pattern: str) -> bool:
    """소스 코드에 특정 문자열 패턴이 없는지"""
    return pattern not in src


def count_pattern(src: str, pattern: str) -> int:
    """패턴 출현 횟수"""
    return src.count(pattern)


# ═══════════════════════════════════════════════════════════
# Fix #1: LangGraph -MemorySaver 제거 확인
# ═══════════════════════════════════════════════════════════

def verify_fix_01():
    header("Fix #1: LangGraph -MemorySaver 제거")
    src = read_source("storyteller_graph.py")

    # import 문에서 MemorySaver 확인 (주석은 제외)
    has_import = bool(re.search(r'^from.*import.*MemorySaver', src, re.MULTILINE))
    has_usage = bool(re.search(r'^\s*(?!#).*MemorySaver\s*\(', src, re.MULTILINE))
    if not has_import and not has_usage:
        R.ok("MemorySaver 미사용", "import/사용 없음")
    else:
        R.fail("MemorySaver 잔존", f"import={has_import}, usage={has_usage}")

    # checkpointer= 파라미터 사용 확인 (주석 제외)
    has_checkpointer_param = bool(re.search(r'^\s*(?!#).*checkpointer\s*=', src, re.MULTILINE))
    if not has_checkpointer_param:
        R.ok("checkpointer 파라미터 미사용", "주석 참조만 존재 (OK)")
    else:
        R.fail("checkpointer 파라미터 잔존", "graph.compile(checkpointer=...) 아직 있음")

    # graph.compile() 호출 확인
    if source_contains(src, "graph.compile()"):
        R.ok("graph.compile() 파라미터 없이 호출")
    else:
        R.fail("graph.compile() 호출 방식 확인 필요")


# ═══════════════════════════════════════════════════════════
# Fix #2: CrewAI -native LLM 사용 (ChatOpenAI 대신)
# ═══════════════════════════════════════════════════════════

def verify_fix_02():
    header("Fix #2: CrewAI -native crewai.LLM 사용")
    src = read_source("expert_crews.py")

    if source_not_contains(src, "ChatOpenAI"):
        R.ok("ChatOpenAI 미사용", "deprecated 의존 제거됨")
    else:
        R.fail("ChatOpenAI 잔존", "expert_crews.py에 ChatOpenAI가 남아있음")

    if source_not_contains(src, "create_langchain_llm"):
        R.ok("create_langchain_llm 미사용")
    else:
        R.fail("create_langchain_llm 잔존", "deprecated 함수 호출")

    if source_contains(src, "from crewai import") and source_contains(src, "LLM"):
        R.ok("crewai.LLM import 확인")
    else:
        R.fail("crewai.LLM import 누락")

    # LLM(model=f"openai/...") 패턴 확인
    if re.search(r'LLM\(\s*model\s*=\s*f"openai/', src):
        R.ok("LLM(model='openai/...') 패턴 사용")
    else:
        R.fail("LLM 생성 패턴 확인 필요")


# ═══════════════════════════════════════════════════════════
# Fix #3: ChromaDB -$contains 미사용, Python 후처리
# ═══════════════════════════════════════════════════════════

def verify_fix_03():
    header("Fix #3: ChromaDB -태그 필터 Python 후처리")
    src = read_source("semantic_card_store.py")

    # $contains가 실제 코드에 사용되는지 확인 (주석 행 제외)
    has_contains_code = False
    for line in src.splitlines():
        stripped = line.lstrip()
        if stripped.startswith("#"):
            continue  # 주석 행 스킵
        if "$contains" in line:
            has_contains_code = True
            break
    if not has_contains_code:
        R.ok("$contains 코드에서 미사용", "주석 참조만 존재 (OK)")
    else:
        R.fail("$contains 코드에서 사용", "ChromaDB $contains는 배열 메타데이터 전용")

    # Python 후처리 필터 존재 확인
    if source_contains(src, "tag_list") and source_contains(src, "split(\",\")"):
        R.ok("Python 태그 후처리 구현됨", "split(',') 방식")
    else:
        R.fail("태그 후처리 로직 누락")

    # fetch_n 증가 로직 (태그 필터 시 더 많이 가져오기)
    if source_contains(src, "fetch_n"):
        R.ok("태그 필터 시 fetch_n 증가 로직")
    else:
        R.fail("fetch_n 로직 누락", "태그 필터 시 결과를 더 많이 가져와야 함")


# ═══════════════════════════════════════════════════════════
# Fix #5: LangChain @tool -parse_docstring=True
# ═══════════════════════════════════════════════════════════

def verify_fix_05():
    header("Fix #5: LangChain @tool(parse_docstring=True)")
    src = read_source("langchain_tools.py")

    # @tool 데코레이터 수 세기
    tool_count = count_pattern(src, "@tool")
    parse_count = count_pattern(src, "parse_docstring=True")

    if tool_count == parse_count and tool_count > 0:
        R.ok(f"모든 @tool에 parse_docstring=True", f"{parse_count}/{tool_count}개")
    else:
        R.fail(f"parse_docstring 불일치", f"@tool={tool_count}, parse_docstring={parse_count}")

    # 실제 import 확인
    try:
        from langchain_core.tools import tool as lc_tool
        R.ok("langchain_core.tools.tool import 성공")

        # parse_docstring 파라미터 존재 확인
        sig = inspect.signature(lc_tool)
        if "parse_docstring" in sig.parameters:
            R.ok("parse_docstring 파라미터 존재 확인")
        else:
            R.fail("parse_docstring 파라미터 없음", f"설치된 버전 확인 필요: {sig}")
    except ImportError:
        R.skip("langchain_core 미설치", "pip install langchain-core")


# ═══════════════════════════════════════════════════════════
# Fix #6: LangChain -Optional 타입 힌트
# ═══════════════════════════════════════════════════════════

def verify_fix_06():
    header("Fix #6: LangChain -Optional[str] 타입 힌트")
    src = read_source("langchain_tools.py")

    # str = None 패턴이 없어야 함 (int = None도 체크)
    # AST 파싱으로 함수 시그니처 확인
    tree = ast.parse(src)

    bad_defaults = []
    for node in ast.walk(tree):
        if isinstance(node, ast.FunctionDef):
            # 데코레이터가 @tool인 함수만
            for deco in node.decorator_list:
                is_tool = False
                if isinstance(deco, ast.Call) and isinstance(deco.func, ast.Name) and deco.func.id == "tool":
                    is_tool = True
                elif isinstance(deco, ast.Name) and deco.id == "tool":
                    is_tool = True

                if is_tool:
                    # 각 파라미터의 annotation 확인
                    for arg in node.args.args:
                        if arg.annotation:
                            ann_str = ast.unparse(arg.annotation) if hasattr(ast, 'unparse') else str(arg.annotation)
                            # str 타입에 default=None이면 잘못된 것
                            if ann_str == "str":
                                # default 값 확인
                                idx = node.args.args.index(arg)
                                defaults_offset = len(node.args.args) - len(node.args.defaults)
                                if idx >= defaults_offset:
                                    default = node.args.defaults[idx - defaults_offset]
                                    if isinstance(default, ast.Constant) and default.value is None:
                                        bad_defaults.append(f"{node.name}.{arg.arg}")

    if not bad_defaults:
        R.ok("모든 Optional 파라미터에 Optional[str] 사용")
    else:
        R.fail(f"str = None 잔존", f"{bad_defaults}")


# ═══════════════════════════════════════════════════════════
# Fix #7: llama.cpp -GET /health 헬스체크
# ═══════════════════════════════════════════════════════════

def verify_fix_07():
    header("Fix #7: llm_pool -GET /health 헬스체크")
    src = read_source("llm_pool.py")

    if source_contains(src, 'self.http.get') and source_contains(src, '/health'):
        R.ok("GET /health 사용", "토큰 미소비 헬스체크")
    else:
        R.fail("GET /health 미사용")

    # POST /v1/chat/completions가 _check_health에 없어야 함
    # _check_health 메서드 내용만 추출
    check_health_match = re.search(
        r'async def _check_health\(self.*?\n((?:        .*\n)*)',
        src
    )
    if check_health_match:
        method_body = check_health_match.group(1)
        if "chat/completions" not in method_body:
            R.ok("_check_health에 chat/completions 미사용", "토큰 절약")
        else:
            R.fail("_check_health에 chat/completions 잔존", "헬스체크가 토큰 소비")
    else:
        R.skip("_check_health 메서드 파싱 실패")


# ═══════════════════════════════════════════════════════════
# Fix #8: llama.cpp -temperature 명시
# ═══════════════════════════════════════════════════════════

def verify_fix_08():
    header("Fix #8: llm_pool -temperature 명시")
    src = read_source("llm_pool.py")

    # _call_chat 메서드에 temperature 파라미터 존재
    if re.search(r'def _call_chat\(.*temperature.*\)', src, re.DOTALL):
        R.ok("_call_chat에 temperature 파라미터 존재")
    else:
        R.fail("_call_chat에 temperature 파라미터 누락")

    # payload에 temperature 포함
    if source_contains(src, '"temperature": temperature'):
        R.ok("payload에 temperature 포함")
    else:
        R.fail("payload에 temperature 누락")


# ═══════════════════════════════════════════════════════════
# Fix #9: CrewAI -asyncio.to_thread
# ═══════════════════════════════════════════════════════════

def verify_fix_09():
    header("Fix #9: CrewAI -asyncio.to_thread 사용")
    src = read_source("expert_crews.py")

    if source_contains(src, "asyncio.to_thread"):
        R.ok("asyncio.to_thread 사용")
    else:
        R.fail("asyncio.to_thread 미사용")

    if source_not_contains(src, "get_event_loop"):
        R.ok("deprecated get_event_loop 미사용")
    else:
        R.fail("deprecated get_event_loop 잔존")

    if source_not_contains(src, "run_in_executor"):
        R.ok("deprecated run_in_executor 미사용")
    else:
        R.fail("deprecated run_in_executor 잔존")


# ═══════════════════════════════════════════════════════════
# Fix #10: CrewAI -result.raw
# ═══════════════════════════════════════════════════════════

def verify_fix_10():
    header("Fix #10: CrewAI -result.raw 사용")
    src = read_source("expert_crews.py")

    if source_contains(src, "result.raw"):
        R.ok("result.raw 사용")
    else:
        R.fail("result.raw 미사용")

    # str(result) 패턴이 없어야 함
    if source_not_contains(src, "str(result)"):
        R.ok("str(result) 미사용", "CrewAI v1.x .raw 사용")
    else:
        R.fail("str(result) 잔존")


# ═══════════════════════════════════════════════════════════
# Fix #11: Graphiti -search() 반환값 list 직접 순회
# ═══════════════════════════════════════════════════════════

def verify_fix_11():
    header("Fix #11: Graphiti -search() 반환값 직접 순회")
    src = read_source("knowledge_graph.py")

    # getattr(search_result, "edges", []) 패턴이 없어야 함
    if source_not_contains(src, 'getattr(search_result, "edges"'):
        R.ok("search_result.edges 접근 제거됨")
    else:
        R.fail("search_result.edges 접근 잔존", "search()는 list[EntityEdge] 반환")

    if source_not_contains(src, 'getattr(search_result, "nodes"'):
        R.ok("search_result.nodes 접근 제거됨")
    else:
        R.fail("search_result.nodes 접근 잔존")

    # search_result를 직접 순회 (for edge in search_result 또는 (search_result or []))
    if source_contains(src, "search_result or []") or source_contains(src, "for edge in search_result"):
        R.ok("search_result 직접 순회", "list[EntityEdge] 처리")
    else:
        R.fail("search_result 직접 순회 패턴 없음")


# ═══════════════════════════════════════════════════════════
# Fix #12: orchestrator.py -global 선언 완전성
# ═══════════════════════════════════════════════════════════

def verify_fix_12():
    header("Fix #12: orchestrator.py -global 선언 완전성")
    src = read_source("orchestrator.py")

    # global 선언에 5개 변수 모두 포함 확인
    required_globals = ["http_client", "mem0_svc", "predictor", "episode_db", "card_store"]

    # lifespan 함수 내의 global 선언 찾기
    global_match = re.search(r'global\s+(.+)', src)
    if global_match:
        declared = [g.strip() for g in global_match.group(1).split(",")]
        missing = [g for g in required_globals if g not in declared]

        if not missing:
            R.ok(f"global 선언 완전", f"{len(declared)}개 변수 선언")
        else:
            R.fail(f"global 선언 누락", f"누락: {missing}")
    else:
        R.fail("global 선언 없음")

    # 모듈 레벨에서 이 변수들이 정의되어 있는지
    for var in required_globals:
        pattern = f"^{var}\\s*[:=]"
        if re.search(pattern, src, re.MULTILINE):
            pass  # ok
        else:
            R.fail(f"모듈 레벨 {var} 정의 없음")


# ═══════════════════════════════════════════════════════════
# Fix #13: NeuralProphet -공식 save() API
# ═══════════════════════════════════════════════════════════

def verify_fix_13():
    header("Fix #13: NeuralProphet -공식 save/load API")
    src = read_source("forecast_engine.py")

    if source_not_contains(src, "model.model.state_dict"):
        R.ok("model.model.state_dict() 제거됨", "비공식 내부 접근 제거")
    else:
        R.fail("model.model.state_dict() 잔존", "비공식 PyTorch 내부 접근")

    if source_contains(src, "from neuralprophet import save"):
        R.ok("neuralprophet.save import 확인")
    else:
        R.fail("neuralprophet.save import 누락")

    if source_contains(src, "save(model,"):
        R.ok("save(model, path) 호출 확인")
    else:
        R.fail("save() 호출 누락")

    # .np 확장자 사용 (공식 권장)
    if source_contains(src, ".np"):
        R.ok(".np 확장자 사용 (공식 형식)")
    elif source_contains(src, ".pt"):
        R.fail(".pt 확장자 사용", "NeuralProphet은 .np 형식 권장")


# ═══════════════════════════════════════════════════════════
# Fix #14: PyTorch Forecasting -to_dataloader(train=True/False)
# ═══════════════════════════════════════════════════════════

def verify_fix_14():
    header("Fix #14: PyTorch Forecasting -to_dataloader(train=)")
    src = read_source("forecast_engine.py")

    if source_contains(src, "to_dataloader(train=True"):
        R.ok("to_dataloader(train=True) 사용 (학습)")
    else:
        R.fail("to_dataloader(train=True) 미사용")

    if source_contains(src, "to_dataloader(train=False"):
        R.ok("to_dataloader(train=False) 사용 (검증)")
    else:
        R.fail("to_dataloader(train=False) 미사용")

    # shuffle= 직접 사용 안 되어야 함
    if source_not_contains(src, "to_dataloader(shuffle=") and \
       source_not_contains(src, "to_dataloader(batch_size=32, shuffle="):
        R.ok("shuffle= 직접 파라미터 미사용", "train= 사용으로 drop_last 포함")
    else:
        R.fail("shuffle= 직접 사용 잔존", "train= 사용 권장 (drop_last 포함)")


# ═══════════════════════════════════════════════════════════
# Fix #15: DuckDB -파라미터화 쿼리
# ═══════════════════════════════════════════════════════════

def verify_fix_15():
    header("Fix #15: DuckDB -파라미터화 쿼리 (SQL injection 방지)")
    src = read_source("timeseries_miner.py")

    # f-string INTERVAL 패턴이 없어야 함
    if source_not_contains(src, "INTERVAL '{days}"):
        R.ok("f-string INTERVAL 패턴 제거됨")
    else:
        R.fail("f-string INTERVAL 패턴 잔존", "SQL injection 위험")

    # 파라미터화 쿼리 사용 확인 (? 바인딩)
    # cutoff 계산 후 ? 바인딩 또는 $1 파라미터
    if source_contains(src, "cutoff") and (source_contains(src, ", [cutoff]") or source_contains(src, "?,")):
        R.ok("파라미터화 쿼리 사용", "cutoff 변수 + 바인딩")
    else:
        R.fail("파라미터화 쿼리 확인 필요")


# ═══════════════════════════════════════════════════════════
# Fix #16: PyTorch Lightning -gradient_clip_val
# ═══════════════════════════════════════════════════════════

def verify_fix_16():
    header("Fix #16: PyTorch Lightning -gradient_clip_val")
    src = read_source("forecast_engine.py")

    if source_contains(src, "gradient_clip_val"):
        R.ok("gradient_clip_val 설정됨", "TFT RNN 발산 방지")
    else:
        R.fail("gradient_clip_val 미설정", "공식 권장: 0.1")


# ═══════════════════════════════════════════════════════════
# Fix #18: mcp_server.py -하드코딩 API 키 제거
# ═══════════════════════════════════════════════════════════

def verify_fix_18():
    header("Fix #18: mcp_server.py -하드코딩 API 키 제거")
    src = read_source("mcp_server.py")

    # 하드코딩 API 키 패턴
    if source_not_contains(src, "API_KEY"):
        R.ok("하드코딩 API_KEY 제거됨")
    else:
        R.fail("API_KEY 잔존", "소스 코드에 시크릿 하드코딩")

    # 일반적인 하드코딩 시크릿 패턴 검사
    secret_patterns = [
        r'api_key\s*=\s*"[A-Za-z0-9_-]{20,}"',
        r'secret\s*=\s*"[A-Za-z0-9_-]{20,}"',
        r'token\s*=\s*"[A-Za-z0-9_-]{20,}"',
    ]
    for pat in secret_patterns:
        # "not-needed" 같은 의도적 더미값은 제외
        matches = re.findall(pat, src, re.IGNORECASE)
        real_secrets = [m for m in matches if "not-needed" not in m and "not_needed" not in m]
        if real_secrets:
            R.fail(f"하드코딩 시크릿 발견", f"패턴: {pat}")
            return

    R.ok("하드코딩 시크릿 없음")


# ═══════════════════════════════════════════════════════════
# Lib: Mem0 -API 패턴 검증
# ═══════════════════════════════════════════════════════════

def verify_lib_mem0():
    header("Lib: Mem0 -API 패턴 검증")
    src = read_source("mem0_service.py")

    # Memory.from_config 사용
    if source_contains(src, "Memory.from_config"):
        R.ok("Memory.from_config() 사용")
    else:
        R.fail("Memory.from_config() 미사용")

    # add()에 messages= 파라미터
    if source_contains(src, "messages="):
        R.ok("add(messages=...) 올바른 파라미터명")
    else:
        R.fail("add() 파라미터 확인 필요")

    # search() 반환값 처리 (results 키)
    if source_contains(src, '.get("results"'):
        R.ok("search/get_all 반환값 .get('results') 처리")
    else:
        R.fail("반환값 처리 확인 필요")

    # import 확인
    try:
        from mem0 import Memory
        R.ok("mem0.Memory import 성공")
    except ImportError:
        R.skip("mem0ai 미설치", "pip install mem0ai")


# ═══════════════════════════════════════════════════════════
# Lib: BERTopic -동작 검증
# ═══════════════════════════════════════════════════════════

def verify_lib_bertopic(quick=False):
    header("Lib: BERTopic -API 검증")
    src = read_source("topic_miner.py")

    if source_contains(src, 'language="multilingual"'):
        R.ok('BERTopic(language="multilingual") 사용')
    else:
        R.fail("BERTopic language 파라미터 확인 필요")

    if source_contains(src, "fit_transform(texts, embeddings="):
        R.ok("fit_transform(texts, embeddings=) 올바른 호출")
    else:
        R.fail("fit_transform 호출 패턴 확인 필요")

    if source_contains(src, "topics_over_time"):
        R.ok("topics_over_time() 사용")
    else:
        R.skip("topics_over_time 미사용")

    if quick:
        return

    # 실제 동작 검증
    try:
        from bertopic import BERTopic
        from sentence_transformers import SentenceTransformer

        texts = [
            "오늘 수학 시간에 민수가 잘 했다",
            "영희가 국어 발표를 했다",
            "점심 시간에 급식을 먹었다",
            "운동장에서 체육 수업을 했다",
            "방과 후에 교실 청소를 했다",
            "민수가 수학 보충수업에서 만점",
            "영희가 미술 시간에 그림을 그렸다",
            "학부모 상담에서 민수 어머니와 이야기",
            "내일 현장학습 준비사항 확인",
            "퇴근 후 수업 준비 구상",
        ]

        embed_model = SentenceTransformer("sentence-transformers/all-MiniLM-L6-v2")
        embeddings = embed_model.encode(texts, show_progress_bar=False)

        model = BERTopic(
            embedding_model=embed_model,
            min_topic_size=2,
            language="multilingual",
            verbose=False,
        )
        topics, probs = model.fit_transform(texts, embeddings=embeddings)

        R.ok("BERTopic fit_transform 동작",
             f"{len(set(topics))}개 토픽, {len(texts)}문서")

        info = model.get_topic_info()
        R.ok("get_topic_info() DataFrame 반환",
             f"columns={list(info.columns)}")

        # get_topic() 반환 타입 확인
        for tid in set(topics):
            if tid != -1:
                words = model.get_topic(tid)
                if words and isinstance(words[0], tuple) and len(words[0]) == 2:
                    R.ok("get_topic() → [(word, score), ...] 형식")
                else:
                    R.fail("get_topic() 반환 형식 이상", f"type={type(words)}")
                break

    except ImportError:
        R.skip("BERTopic 미설치", "pip install bertopic sentence-transformers")
    except Exception as e:
        R.fail(f"BERTopic 동작 오류", str(e))


# ═══════════════════════════════════════════════════════════
# Lib: STUMPY -동작 검증
# ═══════════════════════════════════════════════════════════

def verify_lib_stumpy(quick=False):
    header("Lib: STUMPY -API 검증")
    src = read_source("timeseries_miner.py")

    if source_contains(src, "stumpy.stump("):
        R.ok("stumpy.stump() 사용")
    else:
        R.fail("stumpy.stump() 미사용")

    if "mp[:, 0]" in src and ("mp[:, 1]" in src or "mp[motif_idx, 1]" in src):
        R.ok("mp[:, 0] (거리), mp[:, 1] (인덱스) 올바른 접근")
    elif re.search(r'mp\[.*,\s*0\]', src) and re.search(r'mp\[.*,\s*1\]', src):
        R.ok("mp 거리/인덱스 접근 패턴 확인")
    else:
        R.fail("Matrix Profile 접근 패턴 확인 필요")

    if quick:
        return

    try:
        import stumpy
        import numpy as np

        # 더미 시계열
        np.random.seed(42)
        ts = np.random.randn(200) + 60
        # 반복 패턴 삽입
        pattern = np.array([70, 75, 80, 85, 80, 75, 70])
        ts[50:57] = pattern
        ts[120:127] = pattern + np.random.randn(7) * 0.5

        mp = stumpy.stump(ts, m=7)

        # mp 구조 확인: (n-m+1, 4) 배열
        R.ok(f"stump() 반환 shape", f"{mp.shape}")

        # 거리 (float), 인덱스 (int)
        distances = mp[:, 0].astype(float)
        indices = mp[:, 1].astype(int)

        motif_idx = int(np.argmin(distances))
        nn_idx = int(indices[motif_idx])

        # 모티프가 삽입한 패턴 위치 근처인지
        motif_near_pattern = (45 <= motif_idx <= 55) or (115 <= motif_idx <= 125)
        nn_near_pattern = (45 <= nn_idx <= 55) or (115 <= nn_idx <= 125)

        R.ok(f"모티프 탐지 동작",
             f"idx={motif_idx}↔{nn_idx}, dist={distances[motif_idx]:.2f}")

        if motif_near_pattern and nn_near_pattern:
            R.ok("삽입 패턴 정확히 탐지")
        else:
            R.fail("삽입 패턴 탐지 부정확",
                   f"예상: ~50↔~120, 실제: {motif_idx}↔{nn_idx}")

    except ImportError:
        R.skip("STUMPY 미설치", "pip install stumpy")
    except Exception as e:
        R.fail(f"STUMPY 동작 오류", str(e))


# ═══════════════════════════════════════════════════════════
# Lib: MCP FastMCP -패턴 검증
# ═══════════════════════════════════════════════════════════

def verify_lib_mcp():
    header("Lib: MCP FastMCP -API 패턴 검증")
    src = read_source("mcp_server.py")

    if source_contains(src, "from mcp.server.fastmcp import FastMCP"):
        R.ok("FastMCP import 경로 정확")
    else:
        R.fail("FastMCP import 경로 확인 필요")

    if re.search(r'FastMCP\("[\w-]+"\)', src):
        R.ok("FastMCP(name) 생성자 호출")
    else:
        R.fail("FastMCP 생성자 확인 필요")

    if source_contains(src, "@mcp.tool()"):
        tool_count = count_pattern(src, "@mcp.tool()")
        R.ok(f"@mcp.tool() 데코레이터 사용", f"{tool_count}개 도구")
    else:
        R.fail("@mcp.tool() 데코레이터 없음")

    if source_contains(src, 'mcp.run(transport="stdio")'):
        R.ok('mcp.run(transport="stdio") 호출')
    else:
        R.fail("mcp.run() 호출 확인 필요")

    try:
        from mcp.server.fastmcp import FastMCP
        R.ok("FastMCP import 성공")
    except ImportError:
        R.skip("mcp 미설치", "pip install mcp")


# ═══════════════════════════════════════════════════════════
# Lib: EpisodeStore -동작 검증
# ═══════════════════════════════════════════════════════════

def verify_lib_episode_store(quick=False):
    header("Lib: EpisodeStore -SQLite 동작 검증")

    if quick:
        try:
            from episode_store import EpisodeStore
            R.ok("EpisodeStore import 성공")
        except ImportError:
            R.fail("episode_store import 실패")
        return

    try:
        from episode_store import EpisodeStore
        import tempfile, os

        db_path = os.path.join(tempfile.mkdtemp(), "test_ep.db")
        store = EpisodeStore(db_path=db_path)

        # CRUD
        store.save(event_type="speech", content="테스트 에피소드", timestamp=time.time())
        R.ok("save() 동작")

        count = store.count()
        R.ok("count() 동작", f"{count}건")

        recent = store.get_recent(limit=5)
        R.ok("get_recent() 동작", f"{len(recent)}건")

        from datetime import datetime
        today = datetime.now().strftime("%Y-%m-%d")
        by_date = store.get_by_date(today)
        R.ok("get_by_date() 동작", f"오늘 {len(by_date)}건")

        stats = store.get_daily_stats(7)
        R.ok("get_daily_stats() 동작", f"{len(stats)}일")

        # 정리
        os.unlink(db_path)
    except Exception as e:
        R.fail("EpisodeStore 동작 오류", str(e))


# ═══════════════════════════════════════════════════════════
# Lib: SemanticCardStore -ChromaDB 하이브리드 검색 동작
# ═══════════════════════════════════════════════════════════

def verify_lib_chromadb(quick=False):
    header("Lib: SemanticCardStore -ChromaDB 하이브리드 검색")

    if quick:
        try:
            import chromadb
            R.ok(f"ChromaDB import 성공 (v{chromadb.__version__})")
        except ImportError:
            R.fail("chromadb 미설치")
        return

    try:
        from semantic_card_store import SemanticCardStore
        import tempfile, shutil

        tmp_dir = tempfile.mkdtemp()
        store = SemanticCardStore(
            chroma_path=tmp_dir,
            collection_name="verify_test",
        )

        # 카드 추가 (태그 포함)
        store.add_card("v1", "민수가 수학 시간에 잘 했다", person="민수",
                       emotion="positive", tags=["수학", "집중"])
        store.add_card("v2", "영희가 넘어졌다", person="영희",
                       emotion="negative", tags=["부상"])
        store.add_card("v3", "점심에 비빔밥을 먹었다",
                       emotion="neutral", tags=["급식", "점심"])

        R.ok("카드 추가 (태그 포함)", f"{store.count()}장")

        # 시맨틱 검색
        results = store.search("수학 성적")
        R.ok("시맨틱 검색 동작", f"{len(results)}건")
        if results and results[0]["metadata"].get("person") == "민수":
            R.ok("시맨틱 검색 정확도", "민수 카드 최상위")

        # 하이브리드: person 필터
        results = store.search("학생", person="영희")
        has_younghi = any(r["metadata"].get("person") == "영희" for r in results)
        R.ok("하이브리드 person 필터", f"{len(results)}건") if has_younghi else \
            R.fail("person 필터 동작 이상")

        # 하이브리드: emotion 필터
        results = store.search("학교", emotion="negative")
        all_neg = all(r["metadata"].get("emotion") == "negative" for r in results)
        R.ok("하이브리드 emotion 필터", f"{len(results)}건, 모두 negative") if all_neg else \
            R.fail("emotion 필터 동작 이상")

        # 태그 후처리 필터 (Fix #3 핵심)
        results = store.search("학교 활동", has_tag="수학")
        has_math = all("수학" in r["metadata"].get("tags", "") for r in results)
        R.ok("태그 후처리 필터 (Fix #3)", f"{len(results)}건") if has_math else \
            R.fail("태그 필터 동작 이상", "Python 후처리 필터 확인 필요")

        # 정리
        shutil.rmtree(tmp_dir, ignore_errors=True)

    except ImportError as e:
        R.skip("ChromaDB/SentenceTransformers 미설치", str(e))
    except Exception as e:
        R.fail("SemanticCardStore 동작 오류", str(e))
        traceback.print_exc()


# ═══════════════════════════════════════════════════════════
# Lib: NeuralProphet -동작 검증
# ═══════════════════════════════════════════════════════════

def verify_lib_neuralprophet(quick=False):
    header("Lib: NeuralProphet -동작 검증")

    if quick:
        try:
            from neuralprophet import NeuralProphet, save, load
            R.ok("NeuralProphet + save/load import 성공")
        except ImportError:
            R.skip("neuralprophet 미설치")
        return

    try:
        from neuralprophet import NeuralProphet, save, load
        import pandas as pd
        import numpy as np
        import tempfile

        # 더미 시계열
        dates = pd.date_range("2025-01-01", periods=168, freq="h")
        np.random.seed(42)
        activity = np.random.rand(168) * 3
        df = pd.DataFrame({"ds": dates, "y": activity})

        model = NeuralProphet(
            growth="off",
            n_changepoints=0,
            daily_seasonality=True,
            weekly_seasonality=False,
            n_forecasts=24,
            n_lags=48,
            epochs=5,  # 검증용 최소
            batch_size=32,
        )

        metrics = model.fit(df, freq="h")
        R.ok("NeuralProphet fit() 동작", f"metrics columns: {list(metrics.columns)}")

        future = model.make_future_dataframe(df, periods=24)
        R.ok("make_future_dataframe() 동작", f"{len(future)}행")

        forecast = model.predict(future)
        R.ok("predict() 동작", f"columns: {[c for c in forecast.columns if 'yhat' in c]}")

        # yhat1 컬럼 확인
        if "yhat1" in forecast.columns:
            R.ok("yhat1 컬럼 존재")
        else:
            R.fail("yhat1 컬럼 없음", f"columns: {list(forecast.columns)}")

        # 공식 save/load
        tmp = tempfile.mktemp(suffix=".np")
        save(model, tmp)
        loaded = load(tmp)
        R.ok("save/load 라운드트립 동작", f"파일: {tmp}")

        import os
        os.unlink(tmp)

    except ImportError:
        R.skip("neuralprophet 미설치", "pip install neuralprophet")
    except Exception as e:
        R.fail("NeuralProphet 동작 오류", str(e))
        traceback.print_exc()


# ═══════════════════════════════════════════════════════════
# Lib: Graphiti -import 검증 (동작은 LLM 서버 필요)
# ═══════════════════════════════════════════════════════════

def verify_lib_graphiti():
    header("Lib: Graphiti -import 검증")
    src = read_source("knowledge_graph.py")

    # import 경로 확인
    imports = [
        ("graphiti_core", "Graphiti"),
        ("graphiti_core.driver.kuzu_driver", "KuzuDriver"),
        ("graphiti_core.llm_client.openai_generic_client", "OpenAIGenericClient"),
        ("graphiti_core.llm_client.config", "LLMConfig"),
    ]

    for module, cls in imports:
        if source_contains(src, f"from {module} import {cls}"):
            try:
                mod = importlib.import_module(module)
                getattr(mod, cls)
                R.ok(f"{module}.{cls} import 성공")
            except ImportError:
                R.skip(f"{module} 미설치", "pip install graphiti-core[kuzu]")
            except AttributeError:
                R.fail(f"{cls} 클래스 없음", f"모듈 {module}에 {cls} 미존재")
        else:
            R.fail(f"import {module}.{cls} 코드 없음")


# ═══════════════════════════════════════════════════════════
# Main
# ═══════════════════════════════════════════════════════════

def main():
    parser = argparse.ArgumentParser(description="XREAL Library Verification")
    parser.add_argument("--quick", action="store_true",
                        help="import + 소스 패턴만 검증 (동작 테스트 스킵)")
    parser.add_argument("--fix", type=int, nargs="+",
                        help="특정 Fix # 만 검증 (예: --fix 11 12)")
    args = parser.parse_args()

    print("\n" + "#" * 60)
    print("  XREAL Library Verification Suite")
    print(f"  Mode: {'quick' if args.quick else 'full'}")
    if args.fix:
        print(f"  Filter: Fix #{args.fix}")
    print("#" * 60)

    t0 = time.time()

    # Fix 번호 → 검증 함수 매핑
    fix_map = {
        1: verify_fix_01,
        2: verify_fix_02,
        3: verify_fix_03,
        5: verify_fix_05,
        6: verify_fix_06,
        7: verify_fix_07,
        8: verify_fix_08,
        9: verify_fix_09,
        10: verify_fix_10,
        11: verify_fix_11,
        12: verify_fix_12,
        13: verify_fix_13,
        14: verify_fix_14,
        15: verify_fix_15,
        16: verify_fix_16,
        18: verify_fix_18,
    }

    # 라이브러리 검증 함수
    lib_tests = [
        ("mem0", verify_lib_mem0),
        ("bertopic", lambda: verify_lib_bertopic(args.quick)),
        ("stumpy", lambda: verify_lib_stumpy(args.quick)),
        ("mcp", verify_lib_mcp),
        ("episode_store", lambda: verify_lib_episode_store(args.quick)),
        ("chromadb", lambda: verify_lib_chromadb(args.quick)),
        ("neuralprophet", lambda: verify_lib_neuralprophet(args.quick)),
        ("graphiti", verify_lib_graphiti),
    ]

    if args.fix:
        # 특정 Fix만 실행
        for fix_num in args.fix:
            if fix_num in fix_map:
                try:
                    fix_map[fix_num]()
                except Exception as e:
                    R.fail(f"Fix #{fix_num} 검증 오류", str(e))
            else:
                print(f"\n  Unknown Fix #{fix_num}")
    else:
        # 전체 실행
        # 1) 소스 코드 패턴 검증 (Fix 사항)
        for fix_num in sorted(fix_map.keys()):
            try:
                fix_map[fix_num]()
            except FileNotFoundError as e:
                R.skip(f"Fix #{fix_num}", f"파일 없음: {e}")
            except Exception as e:
                R.fail(f"Fix #{fix_num} 검증 오류", str(e))

        # 2) 라이브러리 동작 검증
        for name, test_fn in lib_tests:
            try:
                test_fn()
            except FileNotFoundError as e:
                R.skip(f"Lib: {name}", f"파일 없음: {e}")
            except Exception as e:
                R.fail(f"Lib: {name} 검증 오류", str(e))
                traceback.print_exc()

    elapsed = time.time() - t0
    print(f"\n  Elapsed: {elapsed:.1f}s")

    success = R.summary()
    sys.exit(0 if success else 1)


if __name__ == "__main__":
    main()

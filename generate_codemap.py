#!/usr/bin/env python3
"""
generate_codemap.py — Kotlin 코드맵 자동 생성기

전체 .kt 파일에서 클래스/인터페이스/메서드 시그니처만 추출하여
CODEMAP.md를 생성합니다. ~16,500줄 소스 → ~1,500줄 요약.

Usage:
    python generate_codemap.py

Output:
    ../CODEMAP.md (프로젝트 루트)
"""

import os
import re
from pathlib import Path
from datetime import datetime
from collections import defaultdict

# ─── Configuration ───
KOTLIN_ROOT = Path("app/src/main/kotlin/com/xreal/nativear")
OUTPUT_FILE = Path("../CODEMAP.md")
PACKAGE_PREFIX = "com.xreal.nativear"

# ─── Regex Patterns ───
CLASS_PATTERN = re.compile(
    r'^(sealed |abstract |data |enum |open )?'
    r'(class|interface|object)\s+'
    r'(\w+)'
    r'(?:\s*<[^>]+>)?'                     # generics
    r'(?:\s*\([^)]*\))?'                   # primary constructor
    r'(?:\s*:\s*(.+?))?'                   # supertype
    r'\s*\{?\s*$'
)

FUN_PATTERN = re.compile(
    r'^\s+'
    r'(?:override\s+)?'
    r'(?:suspend\s+)?'
    r'(?:internal\s+)?'
    r'(?:open\s+)?'
    r'(?:private\s+|protected\s+)?'
    r'(fun)\s+'
    r'(\w+)'
    r'\s*\(([^)]*)\)'
    r'(?:\s*:\s*(\S+))?'
)

PROP_PATTERN = re.compile(
    r'^\s+'
    r'(?:override\s+)?'
    r'(?:val|var)\s+'
    r'(\w+)'
    r'\s*:\s*'
    r'(\S+)'
)

COMPANION_CONST = re.compile(
    r'^\s+(?:private\s+)?const\s+val\s+(\w+)\s*=\s*(.+)'
)


def extract_signatures(filepath: Path) -> dict:
    """Extract class/interface/function signatures from a .kt file."""
    result = {
        "classes": [],
        "functions": [],
        "package": "",
    }

    try:
        lines = filepath.read_text(encoding="utf-8").splitlines()
    except Exception:
        return result

    current_class = None
    brace_depth = 0
    in_companion = False

    for line in lines:
        stripped = line.strip()

        # Package
        if stripped.startswith("package "):
            result["package"] = stripped[8:].strip()
            continue

        # Skip imports, comments, blank lines
        if stripped.startswith("import ") or stripped.startswith("//") or not stripped:
            continue
        if stripped.startswith("/*") or stripped.startswith("*"):
            continue

        # Track brace depth (rough)
        brace_depth += stripped.count("{") - stripped.count("}")

        # Companion object
        if "companion object" in stripped:
            in_companion = True
            continue

        # Class/interface/object declaration
        m = CLASS_PATTERN.match(stripped)
        if m and "companion" not in stripped:
            modifier = (m.group(1) or "").strip()
            kind = m.group(2)
            name = m.group(3)
            supertype = m.group(4)

            # Clean supertype
            if supertype:
                supertype = supertype.rstrip("{").strip()
                # Shorten FQN
                supertype = re.sub(r'\bcom\.xreal\.nativear\.\w+\.', '', supertype)
                supertype = re.sub(r'\bcom\.xreal\.nativear\.', '', supertype)

            entry = {
                "kind": f"{modifier} {kind}".strip() if modifier else kind,
                "name": name,
                "supertype": supertype,
                "methods": [],
                "props": [],
            }
            result["classes"].append(entry)
            current_class = entry
            in_companion = False
            continue

        # Skip private functions
        if "private " in stripped and "fun " in stripped:
            continue

        # Function signature
        m = FUN_PATTERN.match(line)
        if m and current_class and not in_companion:
            name = m.group(2)
            params = m.group(3).strip()
            ret = m.group(4)

            # Simplify param types
            params = re.sub(r'\bcom\.xreal\.nativear\.\w+\.', '', params)
            params = re.sub(r'\bcom\.xreal\.nativear\.', '', params)
            params = re.sub(r'\bkotlinx\.coroutines\.', '', params)
            params = re.sub(r'\bandroid\.\w+\.', '', params)

            sig = f"{name}({params})"
            if ret:
                ret = re.sub(r'\bcom\.xreal\.nativear\.\w+\.', '', ret)
                sig += f": {ret}"

            current_class["methods"].append(sig)

    return result


def format_codemap(all_files: dict) -> str:
    """Format extracted data into CODEMAP.md content."""
    out = []
    out.append(f"# CODEMAP.md — Auto-generated Code Map")
    out.append(f"")
    out.append(f"> Generated: {datetime.now().strftime('%Y-%m-%d %H:%M')}")
    out.append(f"> Source: {KOTLIN_ROOT}")
    out.append(f"> Run `python generate_codemap.py` from XREALNativeAR/ to refresh.")
    out.append(f"")
    out.append(f"---")
    out.append(f"")

    # Group by package
    packages = defaultdict(list)
    total_classes = 0
    total_methods = 0

    for filepath, data in sorted(all_files.items()):
        pkg = data["package"]
        rel = pkg.replace(PACKAGE_PREFIX, "").lstrip(".")
        if not rel:
            rel = "(root)"
        packages[rel].append((filepath, data))
        total_classes += len(data["classes"])
        for c in data["classes"]:
            total_methods += len(c["methods"])

    out.append(f"**Summary**: {total_classes} classes/interfaces, {total_methods} public methods, {len(all_files)} files")
    out.append(f"")

    for pkg_name in sorted(packages.keys()):
        files = packages[pkg_name]
        out.append(f"## {pkg_name}")
        out.append(f"")

        for filepath, data in sorted(files, key=lambda x: x[0]):
            for cls in data["classes"]:
                # Header
                kind_str = cls["kind"]
                super_str = f" : {cls['supertype']}" if cls.get("supertype") else ""
                out.append(f"### `{cls['name']}` ({kind_str}){super_str}")
                out.append(f"_File: {filepath}_")

                if cls["methods"]:
                    out.append(f"```")
                    for method in cls["methods"]:
                        out.append(f"  {method}")
                    out.append(f"```")

                out.append(f"")

    return "\n".join(out)


def main():
    if not KOTLIN_ROOT.exists():
        print(f"Error: {KOTLIN_ROOT} not found. Run from XREALNativeAR/ directory.")
        return

    print(f"Scanning {KOTLIN_ROOT}...")

    all_files = {}
    kt_count = 0

    for kt_file in sorted(KOTLIN_ROOT.rglob("*.kt")):
        kt_count += 1
        rel_path = str(kt_file.relative_to(KOTLIN_ROOT)).replace("\\", "/")
        data = extract_signatures(kt_file)
        if data["classes"]:  # Only include files with class declarations
            all_files[rel_path] = data

    print(f"Found {kt_count} .kt files, {len(all_files)} with class declarations")

    content = format_codemap(all_files)

    OUTPUT_FILE.write_text(content, encoding="utf-8")

    line_count = content.count("\n")
    print(f"Generated {OUTPUT_FILE} ({line_count} lines)")
    savings = 100 - (line_count * 100 // 16500)
    print(f"vs ~16,500 lines of full source - {savings}% token savings")


if __name__ == "__main__":
    main()

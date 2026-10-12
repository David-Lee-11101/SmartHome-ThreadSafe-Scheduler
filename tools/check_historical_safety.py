#!/usr/bin/env python3
"""Fail if isolated historical research regains import-time external activity or private paths."""

import ast
from pathlib import Path
import re
import sys
import warnings


ROOT = Path(__file__).resolve().parents[1] / "experiments" / "historical-synchronization"
DRIVE_PATH = re.compile("[A-Z]:" + re.escape(chr(92)), re.IGNORECASE)
PRIVATE_MARKERS = (
    "/" + "Users" + "/",
    "".join(chr(value) for value in (0x7814, 0x7a76, 0x751f)),
    "".join(chr(value) for value in (0x5b66, 0x6821)),
    "".join(chr(value) for value in (0x5b66, 0x751f)),
    "".join(chr(value) for value in (0x6bd5, 0x4e1a)),
    "".join(chr(value) for value in (0x6bd5, 0x8bbe)),
)
EXTERNAL_CALLS = {"login", "get_devices", "create_order", "connect", "StrictRedis", "Redis"}
warnings.filterwarnings("ignore", category=SyntaxWarning)


def call_name(call):
    function = call.func
    if isinstance(function, ast.Name):
        return function.id
    if isinstance(function, ast.Attribute):
        return function.attr
    return ""


def contains_environment_lookup(node):
    for child in ast.walk(node):
        if isinstance(child, ast.Subscript) and isinstance(child.value, ast.Attribute):
            if isinstance(child.value.value, ast.Name) and child.value.value.id == "os" \
                    and child.value.attr == "environ":
                return True
    return False


def main():
    failures = []
    for path in sorted(ROOT.rglob("*.py")):
        text = path.read_text(encoding="utf-8")
        if DRIVE_PATH.search(text) or any(marker in text for marker in PRIVATE_MARKERS):
            failures.append(f"{path}: private path or education context")
        try:
            tree = ast.parse(text, filename=str(path))
        except SyntaxError as error:
            failures.append(f"{path}:{error.lineno}: syntax error")
            continue
        for statement in tree.body:
            if isinstance(statement, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef, ast.Import,
                                      ast.ImportFrom, ast.If)):
                continue
            if contains_environment_lookup(statement):
                failures.append(f"{path}:{statement.lineno}: import-time environment lookup")
            for child in ast.walk(statement):
                if isinstance(child, ast.Call) and call_name(child) in EXTERNAL_CALLS:
                    failures.append(f"{path}:{child.lineno}: import-time external adapter call")
    if failures:
        print("\n".join(failures), file=sys.stderr)
        return 1
    print("historical safety scan passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
# Copyright 2026 Calin-Andrei Burloiu
#
#    Licensed under the Apache License, Version 2.0 (the "License");
#    you may not use this file except in compliance with the License.
#    You may obtain a copy of the License at
#
#        http://www.apache.org/licenses/LICENSE-2.0
#
#    Unless required by applicable law or agreed to in writing, software
#    distributed under the License is distributed on an "AS IS" BASIS,
#    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#    See the License for the specific language governing permissions and
#    limitations under the License.

"""Checks the size of the architecture docs against their word budget. Run it with --help for its usage."""

import argparse
import sys
from enum import Enum
from pathlib import Path
from typing import List, NamedTuple, Optional, TextIO

BUDGET_WORDS = 1000
OK_MAX_WORDS = 1200
WARNING_MAX_WORDS = 1500
EXEMPTION_MARKER = "<!-- arch-doc-size: exempt (paper) -->"
MARKER_MAX_LINE = 10
ARCHITECTURE_DIR = Path("docs") / "architecture"
# This file is <root>/.claude/skills/architecture-docs/scripts/check_arch_doc_size.py.
REPO_ROOT = Path(__file__).resolve().parents[4]

COMPACTING = "rephrase, remove details or split; see the architecture-docs skill"


class Status(Enum):
    OK = "OK"
    WARNING = "WARNING"
    ERROR = "ERROR"
    SKIPPED = "SKIPPED"


INSTRUCTIONS = {
    Status.WARNING: f"compacting recommended above {OK_MAX_WORDS} words: {COMPACTING}",
    Status.ERROR: f"compacting required above {WARNING_MAX_WORDS} words: {COMPACTING}",
}
ANNOTATION_LEVELS = {Status.WARNING: "warning", Status.ERROR: "error"}


class Result(NamedTuple):
    path: Path
    status: Status
    words: int


def count_words(text: str) -> int:
    """Counts the whitespace-separated words of a doc, code blocks and diagrams included."""
    return len(text.split())


def is_exempt(text: str) -> bool:
    return any(EXEMPTION_MARKER in line for line in text.splitlines()[:MARKER_MAX_LINE])


def classify(words: int) -> Status:
    if words <= OK_MAX_WORDS:
        return Status.OK
    if words <= WARNING_MAX_WORDS:
        return Status.WARNING
    return Status.ERROR


def check_file(path: Path) -> Result:
    text = path.read_text(encoding="utf-8")
    words = count_words(text)
    return Result(path, Status.SKIPPED if is_exempt(text) else classify(words), words)


def budget_share(words: int) -> str:
    return f"{words * 100 // BUDGET_WORDS}% of the {BUDGET_WORDS}-word budget"


def format_line(result: Result, display: str) -> str:
    detail = "exempt" if result.status is Status.SKIPPED else budget_share(result.words)
    line = f"{result.status.value:<7}  {display}  {result.words} words ({detail})"
    instruction = INSTRUCTIONS.get(result.status)
    return f"{line}: {instruction}" if instruction else line


def escape_data(value: str) -> str:
    """Escapes an annotation message, as GitHub Actions workflow commands require."""
    return value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def escape_property(value: str) -> str:
    """Escapes an annotation property, such as `file`, as GitHub Actions workflow commands require."""
    return escape_data(value).replace(":", "%3A").replace(",", "%2C")


def format_annotation(result: Result, display: str) -> Optional[str]:
    level = ANNOTATION_LEVELS.get(result.status)
    if level is None:
        return None
    message = f"{result.words} words ({budget_share(result.words)}): {INSTRUCTIONS[result.status]}"
    return f"::{level} file={escape_property(display)}::{escape_data(message)}"


def discover(directory: Path) -> List[Path]:
    return sorted(path for path in directory.rglob("*.md") if path.is_file())


def display_path(path: Path, root: Path) -> str:
    try:
        return path.resolve().relative_to(root.resolve()).as_posix()
    except ValueError:
        return str(path)


def parse_args(argv: Optional[List[str]]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="check_arch_doc_size.py",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        description=(
            f"Checks the word count of architecture docs against their budget of {BUDGET_WORDS} words:\n"
            f"OK up to {OK_MAX_WORDS} words, WARNING up to {WARNING_MAX_WORDS} (compacting recommended),\n"
            f"ERROR above (compacting required). A doc with {EXEMPTION_MARKER}\n"
            f"in its first {MARKER_MAX_LINE} lines is SKIPPED."),
        epilog="Exit status: 0 when no doc is in ERROR, 1 when one is, 2 on a usage error.")
    parser.add_argument("--github", action="store_true",
                        help="also print GitHub Actions annotations for the warnings and errors")
    parser.add_argument("paths", nargs="*", type=Path, metavar="PATH",
                        help="a doc, or a directory of docs, to check (default: every doc under docs/architecture/)")
    return parser.parse_args(argv)


def main(argv: Optional[List[str]] = None, root: Path = REPO_ROOT, out: TextIO = sys.stdout,
         err: TextIO = sys.stderr) -> int:
    args = parse_args(argv)

    missing = [str(path) for path in args.paths if not path.exists()]
    if missing:
        print(f"error: no such file or directory: {', '.join(missing)}", file=err)
        return 2

    if args.paths:
        targets = [doc for path in args.paths for doc in (discover(path) if path.is_dir() else [path])]
    else:
        targets = discover(root / ARCHITECTURE_DIR)
    if not targets:
        print("error: no Markdown docs to check", file=err)
        return 2

    results = [check_file(path) for path in targets]
    for result in results:
        display = display_path(result.path, root)
        print(format_line(result, display), file=out)
        annotation = format_annotation(result, display) if args.github else None
        if annotation:
            print(annotation, file=out)

    return 1 if any(result.status is Status.ERROR for result in results) else 0


if __name__ == "__main__":
    sys.exit(main())

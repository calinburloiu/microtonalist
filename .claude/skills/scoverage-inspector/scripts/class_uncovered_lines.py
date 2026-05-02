#!/usr/bin/env python3
"""Print compressed source-line ranges for uncovered statements in a class.

Usage:
    class_uncovered_lines.py <module> <fully.qualified.ClassName> [--root REPO] [--aggregate]

Per-module mode (default): reads coverage-reports/<module>/scoverage-report/scoverage.xml.
Aggregate mode (--aggregate): reads coverage-reports/root/scoverage-report/scoverage.xml,
which factors in tests from modules that depend on <module>. The <module> arg is
informational in aggregate mode.

Output:
    class=<FQN>  file=<path>
    uncovered statement lines: 12, 18-20, 47
    uncovered branch lines:    33, 88-91

Lines are derived from <statement> elements with invocation-count="0" and
ignored != "true". Branch-only uncovered (a partially-covered conditional)
is reported as a separate "branch" set so it doesn't get lost in the
statement set when invocation-count is 0 but the statement was reached
via the other branch arm.
"""
from __future__ import annotations

import argparse
import os
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def collapse(lines: set[int]) -> str:
    if not lines:
        return "(none)"
    sorted_lines = sorted(lines)
    ranges: list[str] = []
    start = prev = sorted_lines[0]
    for n in sorted_lines[1:]:
        if n == prev + 1:
            prev = n
            continue
        ranges.append(str(start) if start == prev else f"{start}-{prev}")
        start = prev = n
    ranges.append(str(start) if start == prev else f"{start}-{prev}")
    return ", ".join(ranges)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("module")
    parser.add_argument("fqn")
    parser.add_argument("--root", default=os.getcwd())
    parser.add_argument(
        "--aggregate",
        action="store_true",
        help="read the cross-module aggregate report at coverage-reports/root/",
    )
    args = parser.parse_args()

    report_module = "root" if args.aggregate else args.module
    xml = Path(args.root) / "coverage-reports" / report_module / "scoverage-report" / "scoverage.xml"
    if not xml.exists():
        print(f"error: {xml} does not exist", file=sys.stderr)
        return 2

    in_class = False
    found = False
    class_filename = "?"
    stmt_lines: set[int] = set()
    branch_lines: set[int] = set()

    for event, elem in ET.iterparse(str(xml), events=("start", "end")):
        if event == "start" and elem.tag == "class":
            if elem.attrib.get("name") == args.fqn:
                in_class = True
                found = True
                class_filename = elem.attrib.get("filename", "?")
        elif event == "end":
            if elem.tag == "statement" and in_class:
                if elem.attrib.get("ignored") != "true" and elem.attrib.get("invocation-count", "0") == "0":
                    try:
                        line = int(elem.attrib.get("line", "0"))
                    except ValueError:
                        line = 0
                    if line > 0:
                        if elem.attrib.get("branch") == "true":
                            branch_lines.add(line)
                        else:
                            stmt_lines.add(line)
                elem.clear()
            elif elem.tag == "class":
                if in_class:
                    break
                elem.clear()

    if not found:
        print(f"error: class '{args.fqn}' not found in {xml}", file=sys.stderr)
        return 1

    print(f"class={args.fqn}  file={class_filename}")
    print(f"uncovered statement lines: {collapse(stmt_lines)}")
    print(f"uncovered branch lines:    {collapse(branch_lines)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

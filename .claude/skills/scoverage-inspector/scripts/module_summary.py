#!/usr/bin/env python3
"""Print a terse per-class summary of a module's scoverage.xml.

Per-module mode (default):
    Reads `coverage-reports/<module>/scoverage-report/scoverage.xml`.
    Coverage reflects only `<module>`'s own tests.

Aggregate mode (--aggregate):
    Reads `coverage-reports/root/scoverage-report/scoverage.xml` and filters
    to classes whose source files live under `<module>/src/`. Coverage
    reflects every test that exercises those classes, including tests in
    modules that depend on `<module>`.

Output: one header line with overall stmt/branch %, then one line per class:

    <FQN>  stmt=<%>  branch=<%>  uncovered-stmts=<N>

Sorted by stmt% ascending so the worst-covered classes float to the top.
Uses xml.etree.iterparse so memory stays constant even on large reports.
"""
from __future__ import annotations

import argparse
import os
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("module", help="sbt module ID, e.g. 'intonation'")
    parser.add_argument("--root", default=os.getcwd(), help="repo root (default: $PWD)")
    parser.add_argument(
        "--aggregate",
        action="store_true",
        help="read the cross-module aggregate report and filter to <module>'s classes",
    )
    args = parser.parse_args()

    report_module = "root" if args.aggregate else args.module
    xml = Path(args.root) / "coverage-reports" / report_module / "scoverage-report" / "scoverage.xml"
    if not xml.exists():
        print(f"error: {xml} does not exist", file=sys.stderr)
        return 2

    module_marker = f"/{args.module}/src/"

    aggregate_overall_stmt = aggregate_overall_branch = "?"
    rows: list[tuple[str, float, float, int, int, int]] = []

    current_class: dict | None = None
    class_source: str | None = None
    uncovered = 0

    for event, elem in ET.iterparse(str(xml), events=("start", "end")):
        if event == "start":
            if elem.tag == "scoverage" and aggregate_overall_stmt == "?":
                aggregate_overall_stmt = elem.attrib.get("statement-rate", "?")
                aggregate_overall_branch = elem.attrib.get("branch-rate", "?")
            elif elem.tag == "class":
                current_class = dict(elem.attrib)
                class_source = None
                uncovered = 0
        elif event == "end":
            if elem.tag == "statement" and current_class is not None:
                if class_source is None:
                    class_source = elem.attrib.get("source", "") or None
                if elem.attrib.get("ignored") == "true":
                    pass
                elif elem.attrib.get("invocation-count", "0") == "0":
                    uncovered += 1
                elem.clear()
            elif elem.tag == "class" and current_class is not None:
                include = True
                if args.aggregate:
                    include = bool(class_source and module_marker in class_source)
                if include:
                    rows.append((
                        current_class.get("name", "?"),
                        float(current_class.get("statement-rate", "0") or 0),
                        float(current_class.get("branch-rate", "0") or 0),
                        uncovered,
                        int(current_class.get("statement-count", "0") or 0),
                        int(current_class.get("statements-invoked", "0") or 0),
                    ))
                current_class = None
                class_source = None
                elem.clear()

    rows.sort(key=lambda r: (r[1], r[2], -r[3]))

    if args.aggregate:
        total_stmts = sum(r[4] for r in rows)
        invoked_stmts = sum(r[5] for r in rows)
        if total_stmts:
            overall_stmt_str = f"{invoked_stmts / total_stmts * 100:.2f}"
            overall_branch_str = f"{sum(r[2] * r[4] for r in rows) / total_stmts:.2f}"
        else:
            overall_stmt_str = overall_branch_str = "0.00"
        source = "root aggregate, filtered to <module>"
    else:
        overall_stmt_str = aggregate_overall_stmt
        overall_branch_str = aggregate_overall_branch
        source = "per-module report"

    print(
        f"module={args.module}  stmt={overall_stmt_str}%  branch={overall_branch_str}%"
        f"  classes={len(rows)}  source={source}"
    )
    name_w = max((len(r[0]) for r in rows), default=20)
    for name, stmt, branch, unc, _, _ in rows:
        print(f"  {name:<{name_w}}  stmt={stmt:6.2f}%  branch={branch:6.2f}%  uncovered-stmts={unc}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

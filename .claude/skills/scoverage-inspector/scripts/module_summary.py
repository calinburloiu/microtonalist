#!/usr/bin/env python3
"""Print a terse per-class summary of a module's scoverage.xml.

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
    args = parser.parse_args()

    xml = Path(args.root) / "coverage-reports" / args.module / "scoverage-report" / "scoverage.xml"
    if not xml.exists():
        print(f"error: {xml} does not exist", file=sys.stderr)
        return 2

    overall_stmt = overall_branch = "?"
    rows: list[tuple[str, float, float, int]] = []

    current_class: dict | None = None
    uncovered = 0

    for event, elem in ET.iterparse(str(xml), events=("start", "end")):
        if event == "start":
            if elem.tag == "scoverage" and overall_stmt == "?":
                overall_stmt = elem.attrib.get("statement-rate", "?")
                overall_branch = elem.attrib.get("branch-rate", "?")
            elif elem.tag == "class":
                current_class = dict(elem.attrib)
                uncovered = 0
        elif event == "end":
            if elem.tag == "statement" and current_class is not None:
                if elem.attrib.get("ignored") == "true":
                    pass
                elif elem.attrib.get("invocation-count", "0") == "0":
                    uncovered += 1
                elem.clear()
            elif elem.tag == "class" and current_class is not None:
                rows.append((
                    current_class.get("name", "?"),
                    float(current_class.get("statement-rate", "0") or 0),
                    float(current_class.get("branch-rate", "0") or 0),
                    uncovered,
                ))
                current_class = None
                elem.clear()

    rows.sort(key=lambda r: (r[1], r[2], -r[3]))

    print(f"module={args.module}  stmt={overall_stmt}%  branch={overall_branch}%  classes={len(rows)}")
    name_w = max((len(r[0]) for r in rows), default=20)
    for name, stmt, branch, unc in rows:
        print(f"  {name:<{name_w}}  stmt={stmt:6.2f}%  branch={branch:6.2f}%  uncovered-stmts={unc}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

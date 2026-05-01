#!/usr/bin/env python3
"""Print stmt/branch coverage for a single class plus its methods.

Usage:
    class_summary.py <module> <fully.qualified.ClassName> [--root REPO]

Output:
    class=<FQN>  file=<path>  stmt=<%>  branch=<%>
      <method>  stmt=<%>  branch=<%>

Method names in scoverage XML look like "<package>/<class>/<method>" — only
the trailing <method> segment is printed. Uses iterparse for streaming.
"""
from __future__ import annotations

import argparse
import os
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def short_method(qualified: str) -> str:
    return qualified.rsplit("/", 1)[-1] if "/" in qualified else qualified


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("module")
    parser.add_argument("fqn", help="fully-qualified class name")
    parser.add_argument("--root", default=os.getcwd())
    args = parser.parse_args()

    xml = Path(args.root) / "coverage-reports" / args.module / "scoverage-report" / "scoverage.xml"
    if not xml.exists():
        print(f"error: {xml} does not exist", file=sys.stderr)
        return 2

    in_class = False
    class_attrs: dict | None = None
    methods: list[tuple[str, float, float]] = []

    for event, elem in ET.iterparse(str(xml), events=("start", "end")):
        if event == "start" and elem.tag == "class":
            if elem.attrib.get("name") == args.fqn:
                in_class = True
                class_attrs = dict(elem.attrib)
        elif event == "end":
            if elem.tag == "method" and in_class:
                methods.append((
                    short_method(elem.attrib.get("name", "?")),
                    float(elem.attrib.get("statement-rate", "0") or 0),
                    float(elem.attrib.get("branch-rate", "0") or 0),
                ))
                elem.clear()
            elif elem.tag == "class":
                if in_class:
                    break
                elem.clear()

    if class_attrs is None:
        print(f"error: class '{args.fqn}' not found in {xml}", file=sys.stderr)
        return 1

    print(
        f"class={class_attrs['name']}  file={class_attrs.get('filename', '?')}"
        f"  stmt={float(class_attrs.get('statement-rate', '0') or 0):6.2f}%"
        f"  branch={float(class_attrs.get('branch-rate', '0') or 0):6.2f}%"
    )
    name_w = max((len(m[0]) for m in methods), default=10)
    for name, stmt, branch in methods:
        print(f"  {name:<{name_w}}  stmt={stmt:6.2f}%  branch={branch:6.2f}%")
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Check whether a module's scoverage.xml report is still fresh.

Compares the mtime of `coverage-reports/<module>/scoverage-report/scoverage.xml`
against the newest mtime under `<module>/src/main/scala` and
`<module>/src/test/scala`.

Exit codes:
  0 — fresh (XML is newer than every source/test file)
  1 — stale (at least one source/test file is newer)
  2 — missing (XML does not exist)

Prints a one-line human-readable status to stdout. No XML is parsed.
"""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path


def newest_mtime(root: Path) -> float:
    newest = 0.0
    if not root.exists():
        return newest
    for dirpath, _, filenames in os.walk(root):
        for name in filenames:
            if not name.endswith(".scala"):
                continue
            mtime = (Path(dirpath) / name).stat().st_mtime
            if mtime > newest:
                newest = mtime
    return newest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("module", help="sbt module ID, e.g. 'intonation'")
    parser.add_argument("--root", default=os.getcwd(), help="repo root (default: $PWD)")
    args = parser.parse_args()

    root = Path(args.root)
    xml = root / "coverage-reports" / args.module / "scoverage-report" / "scoverage.xml"
    if not xml.exists():
        print(f"missing: {xml} does not exist — run `sbt \"coverageModules {args.module}\"`")
        return 2

    xml_mtime = xml.stat().st_mtime
    main_src = newest_mtime(root / args.module / "src" / "main" / "scala")
    test_src = newest_mtime(root / args.module / "src" / "test" / "scala")
    newest_source = max(main_src, test_src)

    if newest_source > xml_mtime:
        print(f"stale: source newer than report — re-run `sbt \"coverageModules {args.module}\"`")
        return 1

    print(f"fresh: {xml.relative_to(root)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

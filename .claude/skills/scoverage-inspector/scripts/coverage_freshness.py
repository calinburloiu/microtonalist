#!/usr/bin/env python3
"""Check whether a scoverage.xml report is still fresh.

Per-module mode (default):
    Compares the mtime of `coverage-reports/<module>/scoverage-report/scoverage.xml`
    against the newest mtime under `<module>/src/main/scala` and
    `<module>/src/test/scala` only.

Aggregate mode (--aggregate):
    Compares the mtime of `coverage-reports/root/scoverage-report/scoverage.xml`
    against the newest mtime under EVERY `*/src/main/scala` and
    `*/src/test/scala` in the repo. The aggregate report combines each
    module's tests with its dependents', so a change in any module can
    invalidate it. The `<module>` argument is still required for symmetry
    with the other commands but is not used to scope the freshness check.

Exit codes:
  0 — fresh (XML is newer than every checked source/test file)
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


def newest_aggregate_mtime(repo_root: Path) -> float:
    newest = 0.0
    for entry in repo_root.iterdir():
        if not entry.is_dir() or entry.name.startswith("."):
            continue
        for sub in ("src/main/scala", "src/test/scala"):
            mtime = newest_mtime(entry / sub)
            if mtime > newest:
                newest = mtime
    return newest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("module", help="sbt module ID, e.g. 'intonation' (ignored when --aggregate is set)")
    parser.add_argument("--root", default=os.getcwd(), help="repo root (default: $PWD)")
    parser.add_argument(
        "--aggregate",
        action="store_true",
        help="check freshness of the cross-module aggregate report at coverage-reports/root/",
    )
    args = parser.parse_args()

    root = Path(args.root)
    report_module = "root" if args.aggregate else args.module
    xml = root / "coverage-reports" / report_module / "scoverage-report" / "scoverage.xml"
    if not xml.exists():
        if args.aggregate:
            hint = "run `sbt coverageAll`"
        else:
            hint = f"run `sbt \"coverageModules {args.module}\"`"
        print(f"missing: {xml} does not exist — {hint}")
        return 2

    xml_mtime = xml.stat().st_mtime
    if args.aggregate:
        newest_source = newest_aggregate_mtime(root)
        rerun = "sbt coverageAll"
    else:
        main_src = newest_mtime(root / args.module / "src" / "main" / "scala")
        test_src = newest_mtime(root / args.module / "src" / "test" / "scala")
        newest_source = max(main_src, test_src)
        rerun = f"sbt \"coverageModules {args.module}\""

    if newest_source > xml_mtime:
        print(f"stale: source newer than report — re-run `{rerun}`")
        return 1

    print(f"fresh: {xml.relative_to(root)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

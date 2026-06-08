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

"""Thin argparse CLI over `scoverage_core`, for humans and CI.

Preserves the standalone command-line use the retired helper scripts provided,
without the MCP. Subcommands:

    freshness <module> [--aggregate]
    class-summary <module> <fqn> [--aggregate] [--overall-only]
    class-uncovered <module> <fqn> [--aggregate]
    module-summary <module> [--aggregate] [--overall-only]
    run-coverage [<module> ...] [--aggregate]

All commands accept `--root` (defaults to the repo root inferred from this file).

Run, e.g.:
    python3 .claude/mcp/scoverage_inspector/cli.py class-summary intonation \
        org.calinburloiu.music.intonation.Scale
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import scoverage_core as core

_FRESHNESS_EXIT = {
    core.Freshness.FRESH: 0,
    core.Freshness.STALE: 1,
    core.Freshness.MISSING: 2,
}


def _add_root(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--root", default=None, help="repo root (default: inferred)")


def _root(args) -> Path:
    return Path(args.root) if args.root else core.repo_root_default()


def _cmd_freshness(args) -> int:
    state = core.freshness(_root(args), args.module, aggregate=args.aggregate)
    print(f"{state.value}: {args.module}{' (aggregate)' if args.aggregate else ''}")
    return _FRESHNESS_EXIT[state]


def _cmd_class_summary(args) -> int:
    summary = core.class_summary(_root(args), args.module, args.fqn, aggregate=args.aggregate)
    print(f"class={summary.fqn}  file={summary.file}  "
          f"stmt={summary.stmt_pct:6.2f}%  branch={summary.branch_pct:6.2f}%")
    if not args.overall_only:
        width = max((len(m.name) for m in summary.methods), default=10)
        for m in summary.methods:
            print(f"  {m.name:<{width}}  stmt={m.stmt_pct:6.2f}%  branch={m.branch_pct:6.2f}%")
    return 0


def _cmd_class_uncovered(args) -> int:
    unc = core.class_uncovered_lines(_root(args), args.module, args.fqn, aggregate=args.aggregate)
    print(f"class={unc.fqn}  file={unc.file}")
    print(f"uncovered statement lines: {core.collapse_ranges(unc.stmt_lines) or '(none)'}")
    print(f"uncovered branch lines:    {core.collapse_ranges(unc.branch_lines) or '(none)'}")
    return 0


def _cmd_module_summary(args) -> int:
    summary = core.module_summary(_root(args), args.module, aggregate=args.aggregate)
    print(f"module={summary.module}  stmt={summary.stmt_pct:6.2f}%  "
          f"branch={summary.branch_pct:6.2f}%  classes={len(summary.classes)}")
    if not args.overall_only:
        width = max((len(c.fqn) for c in summary.classes), default=20)
        for c in summary.classes:
            print(f"  {c.fqn:<{width}}  stmt={c.stmt_pct:6.2f}%  "
                  f"branch={c.branch_pct:6.2f}%  uncovered-stmts={c.uncovered_stmts}")
    return 0


def _cmd_run_coverage(args) -> int:
    result = core.run_coverage(list(args.modules), _root(args), aggregate=args.aggregate)
    print(f"status={result.status}  modules={','.join(result.modules)}  log={result.log_path}")
    return 0 if result.status == "ok" else 1


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="scoverage", description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("freshness", help="check whether a module's report is fresh")
    p.add_argument("module")
    p.add_argument("--aggregate", action="store_true")
    _add_root(p)
    p.set_defaults(func=_cmd_freshness)

    p = sub.add_parser("class-summary", help="per-class percentages + methods")
    p.add_argument("module")
    p.add_argument("fqn")
    p.add_argument("--aggregate", action="store_true")
    p.add_argument("--overall-only", action="store_true")
    _add_root(p)
    p.set_defaults(func=_cmd_class_summary)

    p = sub.add_parser("class-uncovered", help="uncovered source lines for a class")
    p.add_argument("module")
    p.add_argument("fqn")
    p.add_argument("--aggregate", action="store_true")
    _add_root(p)
    p.set_defaults(func=_cmd_class_uncovered)

    p = sub.add_parser("module-summary", help="module percentages + class breakdown")
    p.add_argument("module")
    p.add_argument("--aggregate", action="store_true")
    p.add_argument("--overall-only", action="store_true")
    _add_root(p)
    p.set_defaults(func=_cmd_module_summary)

    p = sub.add_parser("run-coverage", help="run sbt coverage for modules or aggregate")
    p.add_argument("modules", nargs="*")
    p.add_argument("--aggregate", action="store_true")
    _add_root(p)
    p.set_defaults(func=_cmd_run_coverage)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)
    try:
        return args.func(args)
    except core.ScoverageError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())

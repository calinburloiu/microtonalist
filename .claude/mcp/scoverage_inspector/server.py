"""Stdio MCP server exposing scoverage inspection over `scoverage_core`.

Two tools are exposed (see the `scoverage-inspector` skill for the policy that
drives them):

* `coverage_report` — per-class statement/branch percentages (and, on request,
  uncovered source ranges) for a list of `(module, fqn)` pairs.
* `module_coverage` — module-level percentages with an optional per-class
  breakdown.

Both tools share one precondition: the report for every requested module is
freshness-checked, and stale/missing reports are rebuilt via a single batched
`sbt` run (unless `allow_rebuild=False`). The rebuild is made observable through
the `rebuilt` list and the `log` path in every result; `status` is `ok` or
`error` only (no retry, no TASTy classification).

Class -> sbt-module resolution is **not** done here. The skill/main agent
resolves each fully-qualified class name to its sbt module ID via Metals and
passes the resolved IDs in.

The FastMCP import is deferred into `build_server` so this module imports cleanly
(for unit tests and `cli.py`) without the `mcp` package present.
"""
from __future__ import annotations

from pathlib import Path
from typing import Callable

import scoverage_core as core

Runner = Callable[[list, Path], int]


# ---------------------------------------------------------------------------
# Freshness / rebuild precondition
# ---------------------------------------------------------------------------

def _ensure_fresh(
    modules: list[str],
    root: Path,
    aggregate: bool,
    allow_rebuild: bool,
    runner: Runner,
) -> dict:
    """Freshness-check the module set; rebuild stale/missing ones if allowed.

    Returns a dict with `rebuilt`, `log`, `status`, and (on error) `message`.
    """
    if aggregate:
        check_targets = [core.AGGREGATE_REPORT_ID]
    else:
        check_targets = modules

    stale = []
    for target in check_targets:
        state = core.freshness(root, target, aggregate=aggregate)
        if state is not core.Freshness.FRESH:
            stale.append(target)

    if not stale:
        return {"rebuilt": [], "log": None, "status": "ok"}

    if not allow_rebuild:
        return {
            "rebuilt": [],
            "log": None,
            "status": "error",
            "message": f"stale or missing reports for: {', '.join(stale)} (allow_rebuild=False)",
        }

    rebuild_modules = [] if aggregate else stale
    run = core.run_coverage(rebuild_modules, root, aggregate=aggregate, runner=runner)
    if run.status != "ok":
        return {
            "rebuilt": run.modules,
            "log": run.log_path,
            "status": "error",
            "message": f"sbt coverage run failed; see {run.log_path}",
        }
    return {"rebuilt": run.modules, "log": run.log_path, "status": "ok"}


def _uncovered_ranges(file: str, lines: list[int]) -> list[str]:
    collapsed = core.collapse_ranges(lines)
    if not collapsed:
        return []
    return [f"{file}:L{token.strip()}" for token in collapsed.split(",")]


# ---------------------------------------------------------------------------
# Tool implementations (pure, testable — no MCP types)
# ---------------------------------------------------------------------------

def do_coverage_report(
    classes: list[dict],
    include_uncovered: bool = False,
    aggregate: bool = False,
    allow_rebuild: bool = True,
    root: Path | None = None,
    runner: Runner = core._default_runner,
) -> dict:
    root = Path(root) if root is not None else core.repo_root_default()
    modules = sorted({c["module"] for c in classes})

    pre = _ensure_fresh(modules, root, aggregate, allow_rebuild, runner)
    if pre["status"] != "ok":
        return {"classes": [], "rebuilt": pre["rebuilt"], "log": pre["log"],
                "status": "error", "message": pre["message"]}

    records = []
    try:
        for entry in classes:
            module, fqn = entry["module"], entry["fqn"]
            summary = core.class_summary(root, module, fqn, aggregate=aggregate)
            record = {
                "fqn": summary.fqn,
                "module": module,
                "file": summary.file,
                "stmt_pct": summary.stmt_pct,
                "branch_pct": summary.branch_pct,
                "methods": [
                    {"name": m.name, "stmt_pct": m.stmt_pct, "branch_pct": m.branch_pct}
                    for m in summary.methods
                ],
            }
            if include_uncovered:
                unc = core.class_uncovered_lines(root, module, fqn, aggregate=aggregate)
                record["uncovered_ranges"] = _uncovered_ranges(unc.file, unc.stmt_lines)
                record["uncovered_branch_ranges"] = _uncovered_ranges(unc.file, unc.branch_lines)
            records.append(record)
    except core.ScoverageError as exc:
        return {"classes": [], "rebuilt": pre["rebuilt"], "log": pre["log"],
                "status": "error", "message": str(exc)}

    return {"classes": records, "rebuilt": pre["rebuilt"], "log": pre["log"], "status": "ok"}


def do_module_coverage(
    modules: list[str],
    include_classes: bool = True,
    aggregate: bool = False,
    allow_rebuild: bool = True,
    root: Path | None = None,
    runner: Runner = core._default_runner,
) -> dict:
    root = Path(root) if root is not None else core.repo_root_default()

    pre = _ensure_fresh(list(modules), root, aggregate, allow_rebuild, runner)
    if pre["status"] != "ok":
        return {"modules": [], "rebuilt": pre["rebuilt"], "log": pre["log"],
                "status": "error", "message": pre["message"]}

    results = []
    try:
        for module in modules:
            summary = core.module_summary(root, module, aggregate=aggregate)
            entry = {
                "module": summary.module,
                "stmt_pct": summary.stmt_pct,
                "branch_pct": summary.branch_pct,
            }
            if include_classes:
                entry["classes"] = [
                    {"fqn": c.fqn, "stmt_pct": c.stmt_pct, "branch_pct": c.branch_pct,
                     "uncovered_stmts": c.uncovered_stmts}
                    for c in summary.classes
                ]
            results.append(entry)
    except core.ScoverageError as exc:
        return {"modules": [], "rebuilt": pre["rebuilt"], "log": pre["log"],
                "status": "error", "message": str(exc)}

    return {"modules": results, "rebuilt": pre["rebuilt"], "log": pre["log"], "status": "ok"}


# ---------------------------------------------------------------------------
# MCP wiring (FastMCP imported lazily so this module imports without `mcp`)
# ---------------------------------------------------------------------------

def build_server(server_factory=None):
    if server_factory is None:
        from mcp.server.fastmcp import FastMCP
        server_factory = FastMCP

    mcp = server_factory("scoverage-inspector")

    @mcp.tool()
    def coverage_report(
        classes: list[dict],
        include_uncovered: bool = False,
        aggregate: bool = False,
        allow_rebuild: bool = True,
    ) -> dict:
        """Per-class statement/branch coverage for resolved (module, fqn) pairs.

        `classes` is a list of objects `{ "module": <sbt module ID>, "fqn":
        <fully.qualified.ClassName> }` where `module` has already been resolved
        upstream via Metals. Set `include_uncovered=True` to also get uncovered
        source ranges, `aggregate=True` to read the cross-module root report, and
        `allow_rebuild=False` to fail instead of triggering an `sbt` rebuild when a
        report is stale.
        """
        return do_coverage_report(classes, include_uncovered, aggregate, allow_rebuild)

    @mcp.tool()
    def module_coverage(
        modules: list[str],
        include_classes: bool = True,
        aggregate: bool = False,
        allow_rebuild: bool = True,
    ) -> dict:
        """Module-level statement/branch coverage with an optional class breakdown.

        `modules` is a list of sbt module IDs. Same freshness/rebuild precondition
        as `coverage_report`.
        """
        return do_module_coverage(modules, include_classes, aggregate, allow_rebuild)

    return mcp


def main() -> None:
    build_server().run()


if __name__ == "__main__":
    main()

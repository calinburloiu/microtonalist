"""Pure coverage-inspection logic for the microtonalist scoverage MCP.

No MCP, no argparse — just importable, unit-testable functions returning
dataclasses. The MCP server (`server.py`) and the human/CI CLI (`cli.py`) are
thin wrappers over this module.

This module relies on the build convention that an sbt module's ID equals its base
directory name (`build.sbt` enforces this with `.withId(<dir>)`). That means a module
ID *is* its source directory, so no `build.sbt` parsing is needed: scoverage reports
live at `coverage-reports/<id>/...` (sbt's `thisProject.value.id`) and sources at
`<id>/src/...`.

Responsibilities:

* `freshness` — compare a report's mtime against the newest source/test file.
* `run_coverage` — run `sbt coverageModules`/`coverageAll` with the
  `-Dmicrotonalist.build.targetSuffix=-scoverage` isolation flag, capturing output
  to a log file. No retry, no TASTy classification — `status` is `ok` or `error`.
* `class_summary`, `class_uncovered_lines`, `module_summary` — stream the report
  XML with `iterparse` (constant memory) and return structured data.
"""
from __future__ import annotations

import enum
import os
import subprocess
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Iterable

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

TARGET_SUFFIX_FLAG = "-Dmicrotonalist.build.targetSuffix=-scoverage"
LOG_SUBPATH = Path("logs") / "mcp" / "scoverage-inspector"
LOG_FILE_NAME = "sbt-run.log"
PREVIOUS_LOG_FILE_NAME = "sbt-run-previous.log"
AGGREGATE_REPORT_ID = "root"


# ---------------------------------------------------------------------------
# Errors
# ---------------------------------------------------------------------------

class ScoverageError(Exception):
    """Base class for all errors raised by this module."""


class ReportMissingError(ScoverageError):
    """The scoverage.xml report does not exist (coverage has not been run)."""


class ClassNotFoundError(ScoverageError):
    """The requested fully-qualified class is absent from the report."""


# ---------------------------------------------------------------------------
# Dataclasses
# ---------------------------------------------------------------------------

class Freshness(enum.Enum):
    FRESH = "fresh"
    STALE = "stale"
    MISSING = "missing"


@dataclass
class MethodCoverage:
    name: str
    stmt_pct: float
    branch_pct: float


@dataclass
class ClassSummary:
    fqn: str
    file: str
    stmt_pct: float
    branch_pct: float
    methods: list[MethodCoverage] = field(default_factory=list)


@dataclass
class UncoveredLines:
    fqn: str
    file: str
    stmt_lines: list[int]
    branch_lines: list[int]


@dataclass
class ClassRow:
    fqn: str
    stmt_pct: float
    branch_pct: float
    uncovered_stmts: int


@dataclass
class ModuleSummary:
    module: str
    stmt_pct: float
    branch_pct: float
    classes: list[ClassRow] = field(default_factory=list)


@dataclass
class RunResult:
    status: str  # "ok" | "error"
    log_path: str  # relative to repo root
    modules: list[str]


# ---------------------------------------------------------------------------
# Repo / build.sbt helpers
# ---------------------------------------------------------------------------

def repo_root_default() -> Path:
    """Repo root inferred from this file's location.

    This file lives at `<root>/.claude/mcp/scoverage_inspector/scoverage_core.py`,
    so the root is four parents up.
    """
    return Path(__file__).resolve().parents[3]


def _module_dirs(root: Path) -> list[str]:
    """Every module's base directory, i.e. each top-level dir containing a `src/`.

    Relies on the build convention that an sbt module's ID equals its base directory
    name (`build.sbt` enforces this with `.withId(<dir>)`), so a module ID *is* its
    source directory — no `build.sbt` parsing is needed. Used to scope the aggregate
    freshness check across all modules.
    """
    if not root.is_dir():
        return []
    return sorted(d.name for d in root.iterdir() if (d / "src").is_dir())


def report_xml_path(root: Path, module_id: str, aggregate: bool = False) -> Path:
    report_id = AGGREGATE_REPORT_ID if aggregate else module_id
    return root / "coverage-reports" / report_id / "scoverage-report" / "scoverage.xml"


# ---------------------------------------------------------------------------
# Freshness
# ---------------------------------------------------------------------------

def _newest_scala_mtime(directory: Path) -> float:
    """Newest mtime under `directory`, counting both `.scala` files and the
    directories themselves.

    Including directory mtimes is what lets the freshness check notice a *deleted*
    (or moved-out) source/test file: removing a directory entry bumps the parent
    directory's mtime, even though no remaining file is newer than the report.
    File mtimes still cover in-place edits, and added files are newer than the
    report on their own. The trade-off is that any change to a tracked source
    directory's contents (even a stray non-`.scala` file) marks the report stale,
    which only ever errs toward an extra rebuild — never toward serving stale data.
    """
    newest = 0.0
    if not directory.exists():
        return newest
    for dirpath, _dirnames, filenames in os.walk(directory):
        dir_mtime = os.stat(dirpath).st_mtime
        if dir_mtime > newest:
            newest = dir_mtime
        for name in filenames:
            if not name.endswith(".scala"):
                continue
            mtime = (Path(dirpath) / name).stat().st_mtime
            if mtime > newest:
                newest = mtime
    return newest


def _module_source_dirs(root: Path, directory: str) -> list[Path]:
    base = root / directory
    return [base / "src" / "main" / "scala", base / "src" / "test" / "scala"]


def _newest_source_mtime(root: Path, directories: Iterable[str]) -> float:
    newest = 0.0
    for directory in directories:
        for src in _module_source_dirs(root, directory):
            mtime = _newest_scala_mtime(src)
            if mtime > newest:
                newest = mtime
    return newest


def freshness(
    root: Path,
    module_id: str,
    aggregate: bool = False,
) -> Freshness:
    """Return whether the report for `module_id` is FRESH, STALE, or MISSING.

    Per-module mode compares the report mtime against the newest `.scala` (and
    directory) under the module's `src/{main,test}/scala`. Because the sbt module ID
    equals its base directory name by build convention, the module ID *is* the source
    directory — no `build.sbt` lookup is needed. Aggregate mode compares the root
    report against the newest source across every module directory. Directory mtimes
    are included so that deleting or moving out a source/test file — which lowers
    coverage but leaves no newer file behind — is still detected as stale (see
    `_newest_scala_mtime`).
    """
    root = Path(root)

    xml = report_xml_path(root, module_id, aggregate)
    if not xml.exists():
        return Freshness.MISSING

    directories = _module_dirs(root) if aggregate else [module_id]
    newest_source = _newest_source_mtime(root, directories)
    if newest_source > xml.stat().st_mtime:
        return Freshness.STALE
    return Freshness.FRESH


# ---------------------------------------------------------------------------
# Running coverage
# ---------------------------------------------------------------------------

def sbt_command(module_ids: list[str], aggregate: bool = False) -> list[str]:
    if aggregate:
        task = "coverageAll"
    else:
        task = "coverageModules " + " ".join(module_ids)
    return ["sbt", TARGET_SUFFIX_FLAG, task]


def _default_runner(cmd: list[str], log_file: Path) -> int:
    """Run `cmd`, capturing combined stdout/stderr to `log_file`. Returns rc."""
    with open(log_file, "w") as handle:
        handle.write("command: " + " ".join(cmd) + "\n")
        handle.flush()
        process = subprocess.run(cmd, stdout=handle, stderr=subprocess.STDOUT)
    return process.returncode


def run_coverage(
    module_ids: list[str],
    root: Path,
    aggregate: bool = False,
    runner: Callable[[list[str], Path], int] = _default_runner,
) -> RunResult:
    """Run coverage for the given modules (or aggregate), logging to a file.

    No retry and no failure classification: `status` is `ok` on a zero exit and
    `error` otherwise. The caller inspects the log to decide what to do.
    """
    root = Path(root)
    log_dir = root / LOG_SUBPATH
    log_dir.mkdir(parents=True, exist_ok=True)

    log_file = log_dir / LOG_FILE_NAME
    if log_file.exists():
        log_file.replace(log_dir / PREVIOUS_LOG_FILE_NAME)

    cmd = sbt_command(module_ids, aggregate)
    return_code = runner(cmd, log_file)

    status = "ok" if return_code == 0 else "error"
    modules = [AGGREGATE_REPORT_ID] if aggregate else list(module_ids)
    log_path = str((log_dir / LOG_FILE_NAME).relative_to(root))
    return RunResult(status=status, log_path=log_path, modules=modules)


# ---------------------------------------------------------------------------
# Report parsing helpers
# ---------------------------------------------------------------------------

def _require_report(xml: Path) -> None:
    if not xml.exists():
        raise ReportMissingError(f"report does not exist: {xml}")


def _to_float(value: str | None) -> float:
    return float(value or 0)


def _short_method(qualified: str) -> str:
    return qualified.rsplit("/", 1)[-1] if "/" in qualified else qualified


def collapse_ranges(lines: Iterable[int]) -> str:
    """Collapse a set of line numbers into a compact range string, e.g. "12, 18-20"."""
    sorted_lines = sorted(set(lines))
    if not sorted_lines:
        return ""
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


# ---------------------------------------------------------------------------
# Readers
# ---------------------------------------------------------------------------

def class_summary(
    root: Path,
    module_id: str,
    fqn: str,
    aggregate: bool = False,
) -> ClassSummary:
    """Statement/branch percentages for a class plus its per-method breakdown."""
    xml = report_xml_path(Path(root), module_id, aggregate)
    _require_report(xml)

    in_class = False
    class_attrs: dict | None = None
    methods: list[MethodCoverage] = []

    for event, elem in ET.iterparse(str(xml), events=("start", "end")):
        if event == "start" and elem.tag == "class":
            if elem.attrib.get("name") == fqn:
                in_class = True
                class_attrs = dict(elem.attrib)
        elif event == "end":
            if elem.tag == "method" and in_class:
                methods.append(MethodCoverage(
                    name=_short_method(elem.attrib.get("name", "?")),
                    stmt_pct=_to_float(elem.attrib.get("statement-rate")),
                    branch_pct=_to_float(elem.attrib.get("branch-rate")),
                ))
                elem.clear()
            elif elem.tag == "class":
                if in_class:
                    break
                elem.clear()

    if class_attrs is None:
        raise ClassNotFoundError(f"class {fqn!r} not found in {xml}")

    return ClassSummary(
        fqn=class_attrs["name"],
        file=class_attrs.get("filename", "?"),
        stmt_pct=_to_float(class_attrs.get("statement-rate")),
        branch_pct=_to_float(class_attrs.get("branch-rate")),
        methods=methods,
    )


def class_uncovered_lines(
    root: Path,
    module_id: str,
    fqn: str,
    aggregate: bool = False,
) -> UncoveredLines:
    """Uncovered statement and branch source lines for a class."""
    xml = report_xml_path(Path(root), module_id, aggregate)
    _require_report(xml)

    in_class = False
    found = False
    class_filename = "?"
    stmt_lines: set[int] = set()
    branch_lines: set[int] = set()

    for event, elem in ET.iterparse(str(xml), events=("start", "end")):
        if event == "start" and elem.tag == "class":
            if elem.attrib.get("name") == fqn:
                in_class = True
                found = True
                class_filename = elem.attrib.get("filename", "?")
        elif event == "end":
            if elem.tag == "statement" and in_class:
                ignored = elem.attrib.get("ignored") == "true"
                uncovered = elem.attrib.get("invocation-count", "0") == "0"
                if not ignored and uncovered:
                    line = _safe_int(elem.attrib.get("line"))
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
        raise ClassNotFoundError(f"class {fqn!r} not found in {xml}")

    return UncoveredLines(
        fqn=fqn,
        file=class_filename,
        stmt_lines=sorted(stmt_lines),
        branch_lines=sorted(branch_lines),
    )


def _safe_int(value: str | None) -> int:
    try:
        return int(value or 0)
    except ValueError:
        return 0


def module_summary(
    root: Path,
    module_id: str,
    aggregate: bool = False,
) -> ModuleSummary:
    """Module-level percentages plus a per-class breakdown sorted worst-first.

    Per-module mode reads `coverage-reports/<module_id>/` and includes every class.
    Aggregate mode reads `coverage-reports/root/` and filters to classes whose source
    lives under the module's directory (unless `module_id` is the aggregate root,
    which includes all classes). Since the module ID equals its base directory name
    by build convention, the filter keys directly off `/<module_id>/src/`.
    """
    root = Path(root)
    xml = report_xml_path(root, module_id, aggregate)
    _require_report(xml)

    show_all = aggregate and module_id == AGGREGATE_REPORT_ID
    module_marker = ""
    if aggregate and not show_all:
        module_marker = f"/{module_id}/src/"

    top_stmt = top_branch = 0.0
    seen_top = False
    rows: list[tuple[ClassRow, int, int]] = []  # (row, statement_count, statements_invoked)

    current: dict | None = None
    class_source: str | None = None
    uncovered = 0

    for event, elem in ET.iterparse(str(xml), events=("start", "end")):
        if event == "start":
            if elem.tag == "scoverage" and not seen_top:
                top_stmt = _to_float(elem.attrib.get("statement-rate"))
                top_branch = _to_float(elem.attrib.get("branch-rate"))
                seen_top = True
            elif elem.tag == "class":
                current = dict(elem.attrib)
                class_source = None
                uncovered = 0
        elif event == "end":
            if elem.tag == "statement" and current is not None:
                if class_source is None:
                    class_source = elem.attrib.get("source") or None
                if elem.attrib.get("ignored") != "true" and elem.attrib.get("invocation-count", "0") == "0":
                    uncovered += 1
                elem.clear()
            elif elem.tag == "class" and current is not None:
                include = True
                if aggregate and not show_all:
                    include = bool(class_source and module_marker in class_source)
                if include:
                    row = ClassRow(
                        fqn=current.get("name", "?"),
                        stmt_pct=_to_float(current.get("statement-rate")),
                        branch_pct=_to_float(current.get("branch-rate")),
                        uncovered_stmts=uncovered,
                    )
                    rows.append((
                        row,
                        _safe_int(current.get("statement-count")),
                        _safe_int(current.get("statements-invoked")),
                    ))
                current = None
                class_source = None
                elem.clear()

    rows.sort(key=lambda r: (r[0].stmt_pct, r[0].branch_pct, -r[0].uncovered_stmts))
    class_rows = [r[0] for r in rows]

    if aggregate and not show_all:
        total = sum(r[1] for r in rows)
        invoked = sum(r[2] for r in rows)
        if total:
            stmt_pct = invoked / total * 100
            branch_pct = sum(r[0].branch_pct * r[1] for r in rows) / total
        else:
            stmt_pct = branch_pct = 0.0
    else:
        stmt_pct = top_stmt
        branch_pct = top_branch

    return ModuleSummary(
        module=module_id,
        stmt_pct=stmt_pct,
        branch_pct=branch_pct,
        classes=class_rows,
    )

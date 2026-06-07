"""Unit tests for scoverage_core — the pure coverage-inspection logic.

Run with: python3 -m unittest discover -s .claude/mcp/scoverage_inspector/tests
"""
from __future__ import annotations

import os
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import scoverage_core as core  # noqa: E402

RESOURCES = Path(__file__).resolve().parent / "resources"
SAMPLE_XML = RESOURCES / "scoverage-sample.xml"


def _write_report(root: Path, module_id: str, xml_src: Path = SAMPLE_XML) -> Path:
    dest = root / "coverage-reports" / module_id / "scoverage-report" / "scoverage.xml"
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy(xml_src, dest)
    return dest


def _set_mtime(path: Path, mtime: float) -> None:
    os.utime(path, (mtime, mtime))


def _age_tree(directory: Path, mtime: float) -> None:
    """Set the mtime of every file and directory under `directory` (and itself)."""
    for dirpath, _dirnames, filenames in os.walk(directory):
        for name in filenames:
            _set_mtime(Path(dirpath) / name, mtime)
        _set_mtime(Path(dirpath), mtime)


class ModuleDirsTest(unittest.TestCase):
    def test_lists_only_dirs_with_a_src_subdir(self):
        # Given a repo root with two module dirs (each with src/) and one without
        tmp = Path(tempfile.mkdtemp())
        try:
            (tmp / "sc-midi" / "src").mkdir(parents=True)
            (tmp / "config" / "src").mkdir(parents=True)
            (tmp / "coverage-reports").mkdir()  # not a module — no src/
            # Then only the module dirs are returned, sorted
            self.assertEqual(core._module_dirs(tmp), ["config", "sc-midi"])
        finally:
            shutil.rmtree(tmp, ignore_errors=True)


class RepoRootDefaultTest(unittest.TestCase):
    def test_points_at_repo_root(self):
        root = core.repo_root_default()
        self.assertTrue((root / ".claude" / "mcp" / "scoverage_inspector").exists())


class SafeIntTest(unittest.TestCase):
    def test_handles_none_and_bad_values(self):
        self.assertEqual(core._safe_int(None), 0)
        self.assertEqual(core._safe_int("abc"), 0)
        self.assertEqual(core._safe_int("42"), 42)


class FreshnessTest(unittest.TestCase):
    def setUp(self):
        # The module ID equals its base directory name by build convention, so the
        # report dir, source dir, and queried module ID are all "sc-midi".
        self.tmp = Path(tempfile.mkdtemp())
        self.src = self.tmp / "sc-midi" / "src" / "main" / "scala"
        self.src.mkdir(parents=True)
        self.source_file = self.src / "Foo.scala"
        self.source_file.write_text("object Foo")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_missing_when_no_report(self):
        self.assertEqual(core.freshness(self.tmp, "sc-midi"), core.Freshness.MISSING)

    def test_fresh_when_report_newer_than_sources(self):
        report = _write_report(self.tmp, "sc-midi")
        _age_tree(self.src, 1000)
        _set_mtime(report, 2000)
        self.assertEqual(core.freshness(self.tmp, "sc-midi"), core.Freshness.FRESH)

    def test_stale_when_source_newer_than_report(self):
        report = _write_report(self.tmp, "sc-midi")
        _age_tree(self.src, 1000)
        _set_mtime(report, 1500)
        _set_mtime(self.source_file, 2000)
        self.assertEqual(core.freshness(self.tmp, "sc-midi"), core.Freshness.STALE)

    def test_stale_when_source_file_deleted(self):
        # Given a fresh report (newer than every source file AND directory)
        report = _write_report(self.tmp, "sc-midi")
        _age_tree(self.src, 1000)
        _set_mtime(report, 2000)
        self.assertEqual(core.freshness(self.tmp, "sc-midi"), core.Freshness.FRESH)
        # When a source/test file is deleted (no remaining file is newer than the report)
        self.source_file.unlink()
        # Then the parent directory's bumped mtime makes the report stale
        self.assertEqual(core.freshness(self.tmp, "sc-midi"), core.Freshness.STALE)

    def test_uses_module_id_as_source_directory(self):
        # The report lives at coverage-reports/sc-midi while sources live at sc-midi/src;
        # freshness must resolve the source dir from the module ID alone (no build.sbt).
        report = _write_report(self.tmp, "sc-midi")
        _age_tree(self.src, 1000)
        _set_mtime(report, 1500)
        _set_mtime(self.source_file, 2000)
        self.assertEqual(core.freshness(self.tmp, "sc-midi"), core.Freshness.STALE)

    def test_aggregate_uses_root_report_and_all_sources(self):
        report = _write_report(self.tmp, "root")
        _age_tree(self.src, 1000)
        _set_mtime(report, 2000)
        self.assertEqual(core.freshness(self.tmp, "sc-midi", aggregate=True), core.Freshness.FRESH)
        _set_mtime(self.source_file, 3000)
        self.assertEqual(core.freshness(self.tmp, "sc-midi", aggregate=True), core.Freshness.STALE)

    def test_aggregate_missing(self):
        self.assertEqual(core.freshness(self.tmp, "sc-midi", aggregate=True), core.Freshness.MISSING)


class ClassSummaryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        _write_report(self.tmp, "sc-midi")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_class_percentages_and_methods(self):
        summary = core.class_summary(self.tmp, "sc-midi", "org.example.Foo")
        self.assertEqual(summary.fqn, "org.example.Foo")
        self.assertEqual(summary.file, "org/example/Foo.scala")
        self.assertAlmostEqual(summary.stmt_pct, 80.0)
        self.assertAlmostEqual(summary.branch_pct, 70.0)
        method_names = {m.name for m in summary.methods}
        self.assertEqual(method_names, {"bar", "baz"})
        baz = next(m for m in summary.methods if m.name == "baz")
        self.assertAlmostEqual(baz.stmt_pct, 60.0)
        self.assertAlmostEqual(baz.branch_pct, 40.0)

    def test_class_not_found_raises(self):
        with self.assertRaises(core.ClassNotFoundError):
            core.class_summary(self.tmp, "sc-midi", "org.example.Missing")

    def test_missing_report_raises(self):
        shutil.rmtree(self.tmp / "coverage-reports")
        with self.assertRaises(core.ReportMissingError):
            core.class_summary(self.tmp, "sc-midi", "org.example.Foo")


class ClassUncoveredLinesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        _write_report(self.tmp, "sc-midi")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_splits_statement_and_branch_lines_excluding_ignored(self):
        result = core.class_uncovered_lines(self.tmp, "sc-midi", "org.example.Foo")
        self.assertEqual(result.stmt_lines, [25])
        self.assertEqual(result.branch_lines, [26])  # line 27 is ignored=true -> excluded

    def test_class_not_found_raises(self):
        with self.assertRaises(core.ClassNotFoundError):
            core.class_uncovered_lines(self.tmp, "sc-midi", "org.example.Missing")


class ModuleSummaryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def test_per_module_overall_and_sorted_classes(self):
        _write_report(self.tmp, "sc-midi")
        summary = core.module_summary(self.tmp, "sc-midi")
        self.assertAlmostEqual(summary.stmt_pct, 75.0)
        self.assertAlmostEqual(summary.branch_pct, 60.0)
        # Sorted by stmt ascending -> Bar (70) before Foo (80)
        self.assertEqual([c.fqn for c in summary.classes], ["org.example.Bar", "org.example.Foo"])
        bar = summary.classes[0]
        self.assertEqual(bar.uncovered_stmts, 3)
        foo = summary.classes[1]
        self.assertEqual(foo.uncovered_stmts, 2)  # lines 25 + 26; line 27 ignored

    def test_aggregate_filters_to_module_directory(self):
        _write_report(self.tmp, "root")
        # sc-midi sources live under sc-midi/ -> only Foo matches
        summary = core.module_summary(self.tmp, "sc-midi", aggregate=True)
        self.assertEqual([c.fqn for c in summary.classes], ["org.example.Foo"])
        self.assertAlmostEqual(summary.stmt_pct, 80.0)
        self.assertAlmostEqual(summary.branch_pct, 70.0)

    def test_aggregate_root_includes_all_classes(self):
        _write_report(self.tmp, "root")
        summary = core.module_summary(self.tmp, "root", aggregate=True)
        self.assertEqual({c.fqn for c in summary.classes}, {"org.example.Foo", "org.example.Bar"})
        self.assertAlmostEqual(summary.stmt_pct, 75.0)

    def test_missing_report_raises(self):
        with self.assertRaises(core.ReportMissingError):
            core.module_summary(self.tmp, "sc-midi")

    def test_aggregate_with_no_matching_classes_is_zero(self):
        _write_report(self.tmp, "root")
        # No class in the sample lives under intonation/ — filter yields nothing.
        # (An unrecognised module ID behaves the same: it simply matches no sources.)
        summary = core.module_summary(self.tmp, "intonation", aggregate=True)
        self.assertEqual(summary.classes, [])
        self.assertEqual(summary.stmt_pct, 0.0)
        self.assertEqual(summary.branch_pct, 0.0)


class CollapseRangesTest(unittest.TestCase):
    def test_collapses_consecutive_runs(self):
        self.assertEqual(core.collapse_ranges([25]), "25")
        self.assertEqual(core.collapse_ranges([12, 18, 19, 20, 47]), "12, 18-20, 47")
        self.assertEqual(core.collapse_ranges([]), "")


class RunCoverageTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.calls = []

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def _runner(self, return_code):
        def runner(cmd, log_file):
            self.calls.append(cmd)
            Path(log_file).write_text("sbt output")
            return return_code
        return runner

    def test_sbt_command_for_modules(self):
        cmd = core.sbt_command(["sc-midi", "tuner"], aggregate=False)
        self.assertEqual(cmd[0], "sbt")
        self.assertIn("-Dmicrotonalist.build.targetSuffix=-scoverage", cmd)
        self.assertEqual(cmd[-1], "coverageModules sc-midi tuner")

    def test_sbt_command_for_aggregate(self):
        cmd = core.sbt_command([], aggregate=True)
        self.assertEqual(cmd[-1], "coverageAll")

    def test_ok_status_on_zero_exit(self):
        result = core.run_coverage(["sc-midi"], self.tmp, runner=self._runner(0))
        self.assertEqual(result.status, "ok")
        self.assertEqual(result.modules, ["sc-midi"])
        self.assertTrue((self.tmp / result.log_path).exists())
        self.assertEqual(len(self.calls), 1)  # no retry

    def test_error_status_on_nonzero_exit(self):
        result = core.run_coverage(["sc-midi"], self.tmp, runner=self._runner(1))
        self.assertEqual(result.status, "error")
        self.assertEqual(len(self.calls), 1)  # no retry even on failure

    def test_aggregate_modules_reported_as_root(self):
        result = core.run_coverage([], self.tmp, aggregate=True, runner=self._runner(0))
        self.assertEqual(result.modules, ["root"])

    def test_default_runner_executes_command_and_logs(self):
        log_file = self.tmp / "run.log"
        rc = core._default_runner(["true"], log_file)
        self.assertEqual(rc, 0)
        self.assertIn("command:", log_file.read_text())

    def test_rotates_previous_log(self):
        core.run_coverage(["sc-midi"], self.tmp, runner=self._runner(0))
        log_dir = self.tmp / "logs" / "mcp" / "scoverage-inspector"
        (log_dir / "sbt-run.log").write_text("first run")
        core.run_coverage(["sc-midi"], self.tmp, runner=self._runner(0))
        self.assertTrue((log_dir / "sbt-run-previous.log").exists())


if __name__ == "__main__":
    unittest.main()

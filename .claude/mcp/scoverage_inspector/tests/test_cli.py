"""Unit tests for cli.py — thin argparse shims over scoverage_core.

The core is mocked; these tests only verify argument parsing, delegation, and
exit-code mapping.
"""
from __future__ import annotations

import io
import sys
import unittest
from contextlib import redirect_stdout, redirect_stderr
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import scoverage_core as core  # noqa: E402
import cli  # noqa: E402


def _run(argv):
    out = io.StringIO()
    err = io.StringIO()
    with redirect_stdout(out), redirect_stderr(err):
        code = cli.main(argv)
    return code, out.getvalue(), err.getvalue()


class FreshnessCliTest(unittest.TestCase):
    def test_fresh_returns_zero(self):
        with mock.patch.object(cli.core, "freshness", return_value=core.Freshness.FRESH) as m:
            code, out, _ = _run(["freshness", "sc-midi"])
        self.assertEqual(code, 0)
        self.assertIn("fresh", out.lower())
        m.assert_called_once()
        self.assertEqual(m.call_args.args[1], "sc-midi")

    def test_stale_returns_one(self):
        with mock.patch.object(cli.core, "freshness", return_value=core.Freshness.STALE):
            code, _, _ = _run(["freshness", "sc-midi"])
        self.assertEqual(code, 1)

    def test_missing_returns_two(self):
        with mock.patch.object(cli.core, "freshness", return_value=core.Freshness.MISSING):
            code, _, _ = _run(["freshness", "sc-midi"])
        self.assertEqual(code, 2)

    def test_aggregate_flag_passed(self):
        with mock.patch.object(cli.core, "freshness", return_value=core.Freshness.FRESH) as m:
            _run(["freshness", "sc-midi", "--aggregate"])
        self.assertTrue(m.call_args.kwargs["aggregate"])


class ClassSummaryCliTest(unittest.TestCase):
    def test_delegates_and_prints(self):
        summary = core.ClassSummary(
            fqn="org.example.Foo", file="Foo.scala", stmt_pct=80.0, branch_pct=70.0,
            methods=[core.MethodCoverage("bar", 100.0, 100.0)],
        )
        with mock.patch.object(cli.core, "class_summary", return_value=summary) as m:
            code, out, _ = _run(["class-summary", "sc-midi", "org.example.Foo"])
        self.assertEqual(code, 0)
        self.assertIn("org.example.Foo", out)
        self.assertIn("bar", out)
        self.assertEqual(m.call_args.args[1:3], ("sc-midi", "org.example.Foo"))

    def test_overall_only_suppresses_methods(self):
        summary = core.ClassSummary(
            fqn="org.example.Foo", file="Foo.scala", stmt_pct=80.0, branch_pct=70.0,
            methods=[core.MethodCoverage("bar", 100.0, 100.0)],
        )
        with mock.patch.object(cli.core, "class_summary", return_value=summary):
            code, out, _ = _run(["class-summary", "sc-midi", "org.example.Foo", "--overall-only"])
        self.assertNotIn("bar", out)

    def test_error_returns_nonzero(self):
        with mock.patch.object(cli.core, "class_summary", side_effect=core.ClassNotFoundError("nope")):
            code, _, err = _run(["class-summary", "sc-midi", "org.example.X"])
        self.assertNotEqual(code, 0)
        self.assertIn("nope", err)


class ClassUncoveredCliTest(unittest.TestCase):
    def test_delegates_and_prints_ranges(self):
        unc = core.UncoveredLines("org.example.Foo", "Foo.scala", [25], [26])
        with mock.patch.object(cli.core, "class_uncovered_lines", return_value=unc) as m:
            code, out, _ = _run(["class-uncovered", "sc-midi", "org.example.Foo"])
        self.assertEqual(code, 0)
        self.assertIn("25", out)
        self.assertIn("26", out)
        self.assertEqual(m.call_args.args[1:3], ("sc-midi", "org.example.Foo"))


class ModuleSummaryCliTest(unittest.TestCase):
    def test_delegates_and_prints(self):
        summary = core.ModuleSummary(
            module="sc-midi", stmt_pct=75.0, branch_pct=60.0,
            classes=[core.ClassRow("org.example.Foo", 80.0, 70.0, 2)],
        )
        with mock.patch.object(cli.core, "module_summary", return_value=summary):
            code, out, _ = _run(["module-summary", "sc-midi"])
        self.assertEqual(code, 0)
        self.assertIn("sc-midi", out)
        self.assertIn("org.example.Foo", out)

    def test_overall_only_suppresses_classes(self):
        summary = core.ModuleSummary(
            module="sc-midi", stmt_pct=75.0, branch_pct=60.0,
            classes=[core.ClassRow("org.example.Foo", 80.0, 70.0, 2)],
        )
        with mock.patch.object(cli.core, "module_summary", return_value=summary):
            code, out, _ = _run(["module-summary", "sc-midi", "--overall-only"])
        self.assertNotIn("org.example.Foo", out)


class RunCoverageCliTest(unittest.TestCase):
    def test_modules_delegates(self):
        result = core.RunResult(status="ok", log_path="logs/mcp/scoverage-inspector/sbt-run.log",
                                modules=["sc-midi", "tuner"])
        with mock.patch.object(cli.core, "run_coverage", return_value=result) as m:
            code, out, _ = _run(["run-coverage", "sc-midi", "tuner"])
        self.assertEqual(code, 0)
        self.assertEqual(m.call_args.args[0], ["sc-midi", "tuner"])
        self.assertFalse(m.call_args.kwargs["aggregate"])
        self.assertIn("sbt-run.log", out)

    def test_aggregate_delegates(self):
        result = core.RunResult(status="ok", log_path="x", modules=["root"])
        with mock.patch.object(cli.core, "run_coverage", return_value=result) as m:
            code, _, _ = _run(["run-coverage", "--aggregate"])
        self.assertEqual(code, 0)
        self.assertTrue(m.call_args.kwargs["aggregate"])

    def test_error_status_returns_nonzero(self):
        result = core.RunResult(status="error", log_path="x", modules=["sc-midi"])
        with mock.patch.object(cli.core, "run_coverage", return_value=result):
            code, _, _ = _run(["run-coverage", "sc-midi"])
        self.assertNotEqual(code, 0)


if __name__ == "__main__":
    unittest.main()

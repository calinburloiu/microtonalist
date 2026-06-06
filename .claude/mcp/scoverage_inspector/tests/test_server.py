"""Unit tests for server.py — the MCP wrapper over scoverage_core.

The sbt runner is always mocked so these tests never shell out, and FastMCP is
never imported (a fake server factory is injected) so the tests run under any
python3 without the `mcp` package installed.
"""
from __future__ import annotations

import shutil
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import scoverage_core as core  # noqa: E402
import server  # noqa: E402

RESOURCES = Path(__file__).resolve().parent / "resources"
SAMPLE_XML = RESOURCES / "scoverage-sample.xml"
SAMPLE_SBT = RESOURCES / "build-sample.sbt"


def _ok_runner(cmd, log_file):
    Path(log_file).write_text("ok")
    return 0


def _err_runner(cmd, log_file):
    Path(log_file).write_text("boom")
    return 1


class FixtureRoot:
    """A temp repo root with build.sbt, a module report, and a source tree."""

    def __init__(self, report_id="scMidi", source_dir="sc-midi"):
        self.root = Path(tempfile.mkdtemp())
        shutil.copy(SAMPLE_SBT, self.root / "build.sbt")
        report = self.root / "coverage-reports" / report_id / "scoverage-report" / "scoverage.xml"
        report.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy(SAMPLE_XML, report)
        self.report = report
        src = self.root / source_dir / "src" / "main" / "scala"
        src.mkdir(parents=True)
        self.source_file = src / "Foo.scala"
        self.source_file.write_text("object Foo")

    def make_fresh(self):
        import os
        os.utime(self.source_file, (1000, 1000))
        os.utime(self.report, (2000, 2000))

    def make_stale(self):
        import os
        os.utime(self.report, (1000, 1000))
        os.utime(self.source_file, (2000, 2000))

    def cleanup(self):
        shutil.rmtree(self.root, ignore_errors=True)


class CoverageReportTest(unittest.TestCase):
    def setUp(self):
        self.fx = FixtureRoot()

    def tearDown(self):
        self.fx.cleanup()

    def test_fresh_report_returns_class_records_without_rebuild(self):
        self.fx.make_fresh()
        result = server.do_coverage_report(
            [{"module": "scMidi", "fqn": "org.example.Foo"}],
            root=self.fx.root,
            runner=_ok_runner,
        )
        self.assertEqual(result["status"], "ok")
        self.assertEqual(result["rebuilt"], [])
        self.assertEqual(len(result["classes"]), 1)
        record = result["classes"][0]
        self.assertEqual(record["fqn"], "org.example.Foo")
        self.assertEqual(record["module"], "scMidi")
        self.assertAlmostEqual(record["stmt_pct"], 80.0)
        self.assertIn("methods", record)
        self.assertNotIn("uncovered_ranges", record)

    def test_include_uncovered_adds_file_line_ranges(self):
        self.fx.make_fresh()
        result = server.do_coverage_report(
            [{"module": "scMidi", "fqn": "org.example.Foo"}],
            include_uncovered=True,
            root=self.fx.root,
            runner=_ok_runner,
        )
        record = result["classes"][0]
        self.assertEqual(record["uncovered_ranges"], ["org/example/Foo.scala:L25"])
        self.assertEqual(record["uncovered_branch_ranges"], ["org/example/Foo.scala:L26"])

    def test_stale_without_allow_rebuild_is_error(self):
        self.fx.make_stale()
        result = server.do_coverage_report(
            [{"module": "scMidi", "fqn": "org.example.Foo"}],
            allow_rebuild=False,
            root=self.fx.root,
            runner=_ok_runner,
        )
        self.assertEqual(result["status"], "error")
        self.assertEqual(result["classes"], [])
        self.assertIn("scMidi", result["message"])

    def test_stale_with_allow_rebuild_runs_sbt_once(self):
        self.fx.make_stale()
        calls = []

        def runner(cmd, log_file):
            calls.append(cmd)
            Path(log_file).write_text("rebuilt")
            return 0

        result = server.do_coverage_report(
            [{"module": "scMidi", "fqn": "org.example.Foo"}],
            root=self.fx.root,
            runner=runner,
        )
        self.assertEqual(result["status"], "ok")
        self.assertEqual(result["rebuilt"], ["scMidi"])
        self.assertEqual(len(calls), 1)
        self.assertTrue(result["log"].endswith("sbt-run.log"))

    def test_rebuild_failure_returns_error_and_log(self):
        self.fx.make_stale()
        result = server.do_coverage_report(
            [{"module": "scMidi", "fqn": "org.example.Foo"}],
            root=self.fx.root,
            runner=_err_runner,
        )
        self.assertEqual(result["status"], "error")
        self.assertTrue(result["log"].endswith("sbt-run.log"))
        self.assertEqual(result["classes"], [])

    def test_class_not_found_is_error(self):
        self.fx.make_fresh()
        result = server.do_coverage_report(
            [{"module": "scMidi", "fqn": "org.example.Missing"}],
            root=self.fx.root,
            runner=_ok_runner,
        )
        self.assertEqual(result["status"], "error")
        self.assertIn("Missing", result["message"])

    def test_dedupes_modules_for_single_rebuild(self):
        self.fx.make_stale()
        calls = []

        def runner(cmd, log_file):
            calls.append(cmd)
            Path(log_file).write_text("x")
            return 0

        server.do_coverage_report(
            [
                {"module": "scMidi", "fqn": "org.example.Foo"},
                {"module": "scMidi", "fqn": "org.example.Bar"},
            ],
            root=self.fx.root,
            runner=runner,
        )
        # One sbt run, one module listed
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0][-1], "coverageModules scMidi")


class AggregateAndHelpersTest(unittest.TestCase):
    def test_uncovered_ranges_empty(self):
        self.assertEqual(server._uncovered_ranges("Foo.scala", []), [])

    def test_aggregate_uses_root_report(self):
        fx = FixtureRoot(report_id="root")
        try:
            import os
            os.utime(fx.source_file, (1000, 1000))
            os.utime(fx.report, (2000, 2000))
            result = server.do_coverage_report(
                [{"module": "scMidi", "fqn": "org.example.Foo"}],
                aggregate=True, root=fx.root, runner=_ok_runner,
            )
            self.assertEqual(result["status"], "ok")
            self.assertEqual(result["classes"][0]["fqn"], "org.example.Foo")
        finally:
            fx.cleanup()


class ModuleCoverageTest(unittest.TestCase):
    def setUp(self):
        self.fx = FixtureRoot()

    def tearDown(self):
        self.fx.cleanup()

    def test_module_with_classes(self):
        self.fx.make_fresh()
        result = server.do_module_coverage(
            ["scMidi"], root=self.fx.root, runner=_ok_runner,
        )
        self.assertEqual(result["status"], "ok")
        self.assertEqual(len(result["modules"]), 1)
        mod = result["modules"][0]
        self.assertEqual(mod["module"], "scMidi")
        self.assertAlmostEqual(mod["stmt_pct"], 75.0)
        self.assertIn("classes", mod)
        self.assertEqual(len(mod["classes"]), 2)

    def test_module_without_classes(self):
        self.fx.make_fresh()
        result = server.do_module_coverage(
            ["scMidi"], include_classes=False, root=self.fx.root, runner=_ok_runner,
        )
        mod = result["modules"][0]
        self.assertNotIn("classes", mod)

    def test_stale_without_allow_rebuild_is_error(self):
        self.fx.make_stale()
        result = server.do_module_coverage(
            ["scMidi"], allow_rebuild=False, root=self.fx.root, runner=_ok_runner,
        )
        self.assertEqual(result["status"], "error")

    def test_report_missing_after_rebuild_is_error(self):
        # Given the report is removed (MISSING) and the rebuild runner does not
        # regenerate it, parsing must surface a ScoverageError as status=error.
        self.fx.report.unlink()
        result = server.do_module_coverage(
            ["scMidi"], root=self.fx.root, runner=_ok_runner,
        )
        self.assertEqual(result["status"], "error")
        self.assertEqual(result["modules"], [])
        self.assertIn("report", result["message"].lower())


class BuildServerTest(unittest.TestCase):
    def test_registers_both_tools_and_delegates(self):
        registered = {}

        class FakeServer:
            def __init__(self, name):
                self.name = name

            def tool(self):
                def decorator(fn):
                    registered[fn.__name__] = fn
                    return fn
                return decorator

        server.build_server(server_factory=FakeServer)
        self.assertIn("coverage_report", registered)
        self.assertIn("module_coverage", registered)

        with mock.patch.object(server, "do_coverage_report", return_value={"ok": 1}) as m:
            out = registered["coverage_report"]([{"module": "m", "fqn": "F"}])
            self.assertEqual(out, {"ok": 1})
            m.assert_called_once()

        with mock.patch.object(server, "do_module_coverage", return_value={"ok": 2}) as m:
            out = registered["module_coverage"](["m"])
            self.assertEqual(out, {"ok": 2})
            m.assert_called_once()


if __name__ == "__main__":
    unittest.main()

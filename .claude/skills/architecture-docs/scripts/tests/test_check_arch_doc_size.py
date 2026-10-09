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

"""Tests of check_arch_doc_size.py, run with:

    python3 -m unittest discover -s .claude/skills/architecture-docs/scripts/tests -p "test_*.py"

Most tests call `main` in-process against a throwaway repository root; `CliTest` runs the script itself.
"""

import io
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS_DIR))

import check_arch_doc_size as size  # noqa: E402

SCRIPT = SCRIPTS_DIR / "check_arch_doc_size.py"
MARKER = "<!-- arch-doc-size: exempt (paper) -->"
HOW = "rephrase, remove details or split; see the architecture-docs skill"


def words(count):
    """A Markdown body of exactly `count` words, ten per line."""
    tokens = ["word"] * count
    return "".join(" ".join(tokens[i:i + 10]) + "\n" for i in range(0, count, 10))


class RepoTestCase(unittest.TestCase):
    """A throwaway repository root, with helpers to write docs and run `main` against it."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = Path(self._tmp.name)

    def write(self, relative_path, text):
        path = self.root / relative_path
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return path

    def run_main(self, *args):
        out, err = io.StringIO(), io.StringIO()
        code = size.main(list(args), root=self.root, out=out, err=err)
        return code, out.getvalue().splitlines(), err.getvalue()


class CountWordsTest(unittest.TestCase):

    def test_counts_whitespace_separated_words(self):
        self.assertEqual(size.count_words("one two\tthree\n\n  four\n"), 4)

    def test_counts_code_blocks_and_diagrams(self):
        text = "```mermaid\nflowchart LR\n  a --> b\n```\n"
        self.assertEqual(size.count_words(text), 7)

    def test_counts_non_ascii_words(self):
        self.assertEqual(size.count_words("Cireșar → ⟶ scale"), 4)

    def test_counts_nothing_in_an_empty_text(self):
        self.assertEqual(size.count_words(""), 0)


class ClassifyTest(unittest.TestCase):

    def test_ok_up_to_1200_words(self):
        self.assertIs(size.classify(0), size.Status.OK)
        self.assertIs(size.classify(1200), size.Status.OK)

    def test_warning_from_1201_to_1500_words(self):
        self.assertIs(size.classify(1201), size.Status.WARNING)
        self.assertIs(size.classify(1500), size.Status.WARNING)

    def test_error_above_1500_words(self):
        self.assertIs(size.classify(1501), size.Status.ERROR)


class CheckFileTest(RepoTestCase):

    def test_skips_a_doc_with_the_marker_on_line_10(self):
        path = self.write("doc.md", "\n" * 9 + MARKER + "\n" + words(2000))
        self.assertIs(size.check_file(path).status, size.Status.SKIPPED)

    def test_checks_a_doc_with_the_marker_on_line_11(self):
        path = self.write("doc.md", "\n" * 10 + MARKER + "\n" + words(2000))
        self.assertIs(size.check_file(path).status, size.Status.ERROR)

    def test_skips_a_doc_with_the_marker_surrounded_by_whitespace(self):
        path = self.write("doc.md", "# Paper\n\n  " + MARKER + "  \n" + words(2000))
        self.assertIs(size.check_file(path).status, size.Status.SKIPPED)

    def test_reports_the_word_count(self):
        path = self.write("doc.md", words(1234))
        self.assertEqual(size.check_file(path), size.Result(path, size.Status.WARNING, 1234))


class MainTest(RepoTestCase):

    def test_checks_every_doc_under_docs_architecture_by_default(self):
        # Given
        self.write("docs/architecture/b/README.md", words(10))
        self.write("docs/architecture/a.md", words(10))
        self.write("docs/architecture/notes.txt", words(10))
        self.write("docs/development/guide.md", words(10))

        # When
        code, lines, _ = self.run_main()

        # Then
        self.assertEqual(code, 0)
        self.assertEqual([line.split()[1] for line in lines],
                         ["docs/architecture/a.md", "docs/architecture/b/README.md"])

    def test_checks_only_the_paths_given(self):
        # Given
        self.write("docs/architecture/a.md", words(10))
        given = self.write("docs/architecture/b.md", words(10))

        # When
        code, lines, _ = self.run_main(str(given))

        # Then
        self.assertEqual(code, 0)
        self.assertEqual([line.split()[1] for line in lines], ["docs/architecture/b.md"])

    def test_expands_a_directory_given_to_its_docs(self):
        # Given
        self.write("docs/architecture/tuner/README.md", words(10))
        self.write("docs/architecture/tuner/mpe.md", words(10))
        self.write("docs/architecture/other.md", words(10))

        # When
        code, lines, _ = self.run_main(str(self.root / "docs/architecture/tuner"))

        # Then
        self.assertEqual(code, 0)
        self.assertEqual([line.split()[1] for line in lines],
                         ["docs/architecture/tuner/README.md", "docs/architecture/tuner/mpe.md"])

    def test_prints_status_path_words_and_percentage(self):
        # Given
        self.write("docs/architecture/a.md", words(1000))

        # When
        _, lines, _ = self.run_main()

        # Then
        self.assertEqual(lines, ["OK       docs/architecture/a.md  1000 words (100% of the 1000-word budget)"])

    def test_tells_how_to_compact_a_doc_with_a_warning(self):
        # Given
        self.write("docs/architecture/a.md", words(1283))

        # When
        code, lines, _ = self.run_main()

        # Then
        self.assertEqual(code, 0)
        self.assertEqual(lines, ["WARNING  docs/architecture/a.md  1283 words (128% of the 1000-word budget): "
                                 "compacting recommended above 1200 words: " + HOW])

    def test_fails_and_tells_how_to_compact_a_doc_with_an_error(self):
        # Given
        self.write("docs/architecture/a.md", words(1501))
        self.write("docs/architecture/b.md", words(10))

        # When
        code, lines, _ = self.run_main()

        # Then
        self.assertEqual(code, 1)
        self.assertEqual(lines, [
            "ERROR    docs/architecture/a.md  1501 words (150% of the 1000-word budget): "
            "compacting required above 1500 words: " + HOW,
            "OK       docs/architecture/b.md  10 words (1% of the 1000-word budget)",
        ])

    def test_reports_an_exempt_doc_as_skipped(self):
        # Given
        self.write("docs/architecture/paper.md", MARKER + "\n" + words(5000))

        # When
        code, lines, _ = self.run_main()

        # Then
        self.assertEqual(code, 0)
        self.assertEqual(lines, ["SKIPPED  docs/architecture/paper.md  5005 words (exempt)"])

    def test_prints_no_annotations_without_the_github_flag(self):
        # Given
        self.write("docs/architecture/a.md", words(1501))

        # When
        _, lines, _ = self.run_main()

        # Then
        self.assertFalse([line for line in lines if line.startswith("::")])

    def test_annotates_warnings_and_errors_for_github(self):
        # Given
        self.write("docs/architecture/error.md", words(1501))
        self.write("docs/architecture/ok.md", words(10))
        self.write("docs/architecture/paper.md", MARKER + "\n" + words(5000))
        self.write("docs/architecture/warning.md", words(1283))

        # When
        code, lines, _ = self.run_main("--github")

        # Then
        self.assertEqual(code, 1)
        self.assertEqual([line for line in lines if line.startswith("::")], [
            "::error file=docs/architecture/error.md::1501 words (150%25 of the 1000-word budget): "
            "compacting required above 1500 words: " + HOW,
            "::warning file=docs/architecture/warning.md::1283 words (128%25 of the 1000-word budget): "
            "compacting recommended above 1200 words: " + HOW,
        ])

    def test_escapes_the_annotation_file_property(self):
        self.assertEqual(size.escape_property("a:b,c%d"), "a%3Ab%2Cc%25d")

    def test_rejects_a_missing_path(self):
        # Given
        self.write("docs/architecture/a.md", words(10))

        # When
        code, lines, err = self.run_main(str(self.root / "docs/architecture/missing.md"))

        # Then
        self.assertEqual(code, 2)
        self.assertEqual(lines, [])
        self.assertIn("missing.md", err)

    def test_rejects_finding_no_docs(self):
        # Given
        self.write("docs/development/guide.md", words(10))

        # When
        code, lines, err = self.run_main()

        # Then
        self.assertEqual(code, 2)
        self.assertEqual(lines, [])
        self.assertIn("no Markdown docs", err)


class CliTest(unittest.TestCase):

    def run_script(self, *args, env=None):
        return subprocess.run([sys.executable, str(SCRIPT), *args], capture_output=True, text=True, env=env)

    def test_prints_its_usage(self):
        result = self.run_script("--help")
        self.assertEqual(result.returncode, 0)
        self.assertIn("--github", result.stdout)
        self.assertIn("arch-doc-size: exempt (paper)", result.stdout)

    def test_reads_utf8_docs_whatever_the_locale(self):
        # Given
        with tempfile.TemporaryDirectory() as tmp:
            doc = Path(tmp) / "doc.md"
            doc.write_text("Cireșar → ⟶ scale\n", encoding="utf-8")
            env = dict(os.environ, LC_ALL="C", LANG="C", PYTHONUTF8="0")

            # When
            result = self.run_script(str(doc), env=env)

        # Then
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("4 words", result.stdout)


if __name__ == "__main__":
    unittest.main()

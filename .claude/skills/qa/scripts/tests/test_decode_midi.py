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

"""Tests of decode_midi.py, run with:

    python3 -m unittest discover -s .claude/skills/qa/scripts/tests -p "test_*.py"

The expected values are worked out by hand from the MIDI Tuning Standard (MIDI 1.0 specification), not from the code
under test or from the tuners. `annotate` is tested in-process; `CliTest` runs the script itself.
"""
from __future__ import annotations

import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS_DIR))

from decode_midi import annotate  # noqa: E402

SCRIPT = SCRIPTS_DIR / "decode_midi.py"
TIMESTAMP = "22:59:36.996   "


def octave_line(header: str, mask: str, data: str) -> str:
    """A ReceiveMIDI line for an MTS octave message: `header` is the bytes up to and including the format (e.g.
    "7E 7F 08 08"), `mask` the three channel mask bytes, `data` the tuning bytes."""
    return f"{TIMESTAMP}system-exclusive hex {header} {mask} {data} dec"


def pitch_bend_line(channel: int, value: int) -> str:
    return f"{TIMESTAMP}channel {channel:2d}   pitch-bend {value:14d}"


class OctaveTuningTest(unittest.TestCase):

    def test_annotates_a_non_real_time_one_byte_octave_tuning(self):
        # Given
        line = "22:59:36.996   system-exclusive hex 7E 7F 08 08 03 7F 7F 40 40 44 40 32 3E 40 42 40 30 40 34 dec"

        # When
        result = annotate(line)

        # Then
        self.assertEqual(result, line + "  # MTS octave 1-byte non-real-time, device 7F, channels 1-16: "
                         "C +0 C♯ +0 D +4 E♭ +0 E -14 F -2 F♯ +0 G +2 G♯ +0 A -16 B♭ +0 B -12")

    def test_annotates_the_extreme_one_byte_values(self):
        # Given
        line = octave_line("7F 00 08 08", "03 7F 7F", "00 7F 40 40 40 40 40 40 40 40 40 40")

        # When
        result = annotate(line)

        # Then
        self.assertTrue(result.endswith("  # MTS octave 1-byte real-time, device 00, channels 1-16: "
                                        "C -64 C♯ +63 D +0 E♭ +0 E +0 F +0 F♯ +0 G +0 G♯ +0 A +0 B♭ +0 B +0"),
                        result)

    def test_reads_the_channel_mask(self):
        cases = {
            "00 00 01": "channels 1",
            "02 00 00": "channels 16",
            "00 01 05": "channels 1, 3, 8",
            "01 40 00": "channels 14-15",
            "00 00 7F": "channels 1-7",
            "00 7F 00": "channels 8-14",
            "03 00 00": "channels 15-16",
            "00 00 00": "channels none",
        }
        for mask, expected in cases.items():
            with self.subTest(mask=mask):
                # Given
                line = octave_line("7E 7F 08 08", mask, " ".join(["40"] * 12))

                # When
                result = annotate(line)

                # Then
                self.assertIn(f", {expected}: ", result)

    def test_annotates_a_real_time_two_byte_octave_tuning(self):
        # Given
        data = "40 00  40 00  42 40  40 00  7F 7F  00 00  40 00  40 00  40 00  40 00  40 00  40 00".replace("  ", " ")
        line = octave_line("7F 00 08 09", "03 7F 7F", data)

        # When
        result = annotate(line)

        # Then
        self.assertEqual(result, line + "  # MTS octave 2-byte real-time, device 00, channels 1-16: "
                         "C +0.00 C♯ +0.00 D +3.91 E♭ +0.00 E +99.99 F -100.00 F♯ +0.00 G +0.00 G♯ +0.00 A +0.00 "
                         "B♭ +0.00 B +0.00")

    def test_annotates_a_non_real_time_two_byte_octave_tuning(self):
        # Given
        line = octave_line("7E 10 08 09", "00 00 01", " ".join(["20 00"] * 12))

        # When
        result = annotate(line)

        # Then
        self.assertIn("  # MTS octave 2-byte non-real-time, device 10, channels 1: C -50.00 C♯ -50.00 ", result)

    def test_annotates_a_two_byte_value_with_a_nonzero_least_significant_byte(self):
        # Given: (0x41 * 128 + 0x20 - 8192) * 100 / 8192 = 160 * 100 / 8192 = 1.953125
        line = octave_line("7F 7F 08 09", "03 7F 7F", "41 20 " + " ".join(["40 00"] * 11))

        # When
        result = annotate(line)

        # Then
        self.assertIn(": C +1.95 C♯ +0.00 ", result)

    def test_rounds_a_half_cent_away_from_zero(self):
        # Given: 0x42 0x00 is +256 / 8192 * 100 = +3.125 cents and 0x3E 0x00 is -3.125 cents, exact in binary
        line = octave_line("7F 7F 08 09", "03 7F 7F", "42 00 3E 00 " + " ".join(["40 00"] * 10))

        # When
        result = annotate(line)

        # Then
        self.assertIn(": C +3.13 C♯ -3.13 D +0.00 ", result)

    def test_flags_a_truncated_one_byte_octave_tuning(self):
        # Given
        line = octave_line("7E 7F 08 08", "03 7F 7F", " ".join(["40"] * 11))

        # When
        result = annotate(line)

        # Then
        self.assertEqual(result, line + "  # malformed MTS octave 1-byte: 11 data bytes, expected 12")

    def test_flags_an_overlong_one_byte_octave_tuning(self):
        # Given
        line = octave_line("7F 7F 08 08", "03 7F 7F", " ".join(["40"] * 13))

        # When
        result = annotate(line)

        # Then
        self.assertEqual(result, line + "  # malformed MTS octave 1-byte: 13 data bytes, expected 12")

    def test_flags_a_truncated_two_byte_octave_tuning(self):
        # Given
        line = octave_line("7F 7F 08 09", "03 7F 7F", " ".join(["40"] * 23))

        # When
        result = annotate(line)

        # Then
        self.assertEqual(result, line + "  # malformed MTS octave 2-byte: 23 data bytes, expected 24")

    def test_flags_an_octave_tuning_cut_inside_the_channel_mask(self):
        # Given
        line = f"{TIMESTAMP}system-exclusive hex 7E 7F 08 08 03 7F dec"

        # When
        result = annotate(line)

        # Then
        self.assertEqual(result, line + "  # malformed MTS octave 1-byte: 0 data bytes, expected 12")

    def test_annotates_a_line_without_a_timestamp(self):
        # Given
        line = "system-exclusive hex 7E 7F 08 08 00 00 01 " + " ".join(["40"] * 12) + " dec"

        # When
        result = annotate(line)

        # Then
        self.assertIn("  # MTS octave 1-byte non-real-time, device 7F, channels 1: C +0 ", result)


class PitchBendTest(unittest.TestCase):

    def test_annotates_a_pitch_bend_with_the_default_range(self):
        cases = {
            8192: "+0.00",
            16383: "+199.98",
            0: "-200.00",
            12288: "+100.00",
        }
        for value, expected in cases.items():
            with self.subTest(value=value):
                # Given
                line = pitch_bend_line(1, value)

                # When
                result = annotate(line)

                # Then
                self.assertEqual(result, f"{line}  # pitch bend {expected} cents (±2 semitones)")

    def test_rounds_a_half_cent_away_from_zero(self):
        # Given: 128 / 8192 * 2 * 100 = 3.125 cents, exact in binary
        # When
        above = annotate(pitch_bend_line(1, 8192 + 128))
        below = annotate(pitch_bend_line(1, 8192 - 128))

        # Then
        self.assertTrue(above.endswith("  # pitch bend +3.13 cents (±2 semitones)"), above)
        self.assertTrue(below.endswith("  # pitch bend -3.13 cents (±2 semitones)"), below)

    def test_annotates_a_pitch_bend_with_the_range_of_the_option(self):
        # Given
        line = pitch_bend_line(3, 12288)

        # When
        result = annotate(line, pb_range=12)

        # Then
        self.assertEqual(result, f"{line}  # pitch bend +600.00 cents (±12 semitones)")

    def test_takes_a_range_per_channel(self):
        # Given
        channel_pb_ranges = {2: 48}

        # When
        result_on_channel_2 = annotate(pitch_bend_line(2, 12288), channel_pb_ranges=channel_pb_ranges)
        result_on_channel_1 = annotate(pitch_bend_line(1, 12288), channel_pb_ranges=channel_pb_ranges)

        # Then
        self.assertTrue(result_on_channel_2.endswith("  # pitch bend +2400.00 cents (±48 semitones)"),
                        result_on_channel_2)
        self.assertTrue(result_on_channel_1.endswith("  # pitch bend +100.00 cents (±2 semitones)"),
                        result_on_channel_1)

    def test_prefers_the_range_of_the_channel_to_the_default_range(self):
        # Given
        line = pitch_bend_line(2, 16384 // 4 * 3)

        # When
        result = annotate(line, pb_range=12, channel_pb_ranges={2: 1.5})

        # Then
        self.assertEqual(result, f"{line}  # pitch bend +75.00 cents (±1.5 semitones)")

    def test_annotates_a_pitch_bend_with_single_spaced_columns(self):
        # Given
        line = "channel 16 pitch-bend 8192"

        # When
        result = annotate(line)

        # Then
        self.assertEqual(result, line + "  # pitch bend +0.00 cents (±2 semitones)")


class UnchangedLinesTest(unittest.TestCase):

    def test_leaves_other_lines_unchanged(self):
        lines = [
            f"{TIMESTAMP}channel  1   control-change    66   127",
            f"{TIMESTAMP}channel  1   note-on          C3  100",
            f"{TIMESTAMP}system-exclusive hex 7E 7F 06 01 dec",
            f"{TIMESTAMP}system-exclusive hex 7F 7F 08 02 00 01 00 40 00 00 dec",
            f"{TIMESTAMP}system-exclusive hex 7E 7F 08 07 dec",
            f"{TIMESTAMP}system-exclusive hex 7D 7F 08 08 03 7F 7F 40 dec",
            f"{TIMESTAMP}system-exclusive hex 7E dec",
            "",
            "not a MIDI line",
        ]
        for line in lines:
            with self.subTest(line=line):
                # When
                result = annotate(line)

                # Then
                self.assertEqual(result, line)


class CliTest(unittest.TestCase):

    ONE_BYTE_LINE = octave_line("7E 7F 08 08", "03 7F 7F", "40 40 44 40 32 3E 40 42 40 30 40 34")
    ONE_BYTE_ANNOTATION = ("  # MTS octave 1-byte non-real-time, device 7F, channels 1-16: "
                           "C +0 C♯ +0 D +4 E♭ +0 E -14 F -2 F♯ +0 G +2 G♯ +0 A -16 B♭ +0 B -12")

    def run_script(self, *args: str, stdin: str | None = None, env: dict | None = None):
        return subprocess.run([sys.executable, str(SCRIPT), *args], input=stdin, capture_output=True, text=True,
                              encoding="utf-8", env=env)

    def test_reads_files_and_stdin(self):
        # Given
        control_change = f"{TIMESTAMP}channel  1   control-change    66   127"
        content = f"{self.ONE_BYTE_LINE}\n{control_change}\n{pitch_bend_line(1, 16383)}\n"
        expected = (f"{self.ONE_BYTE_LINE}{self.ONE_BYTE_ANNOTATION}\n{control_change}\n"
                    f"{pitch_bend_line(1, 16383)}  # pitch bend +199.98 cents (±2 semitones)\n")
        with tempfile.TemporaryDirectory() as tmp:
            file = Path(tmp) / "midi-out-synth.txt"
            file.write_text(content, encoding="utf-8")

            # When
            from_file = self.run_script(str(file))
            from_stdin = self.run_script(stdin=content)

        # Then
        self.assertEqual(from_file.returncode, 0, from_file.stderr)
        self.assertEqual(from_file.stdout, expected)
        self.assertEqual(from_stdin.returncode, 0, from_stdin.stderr)
        self.assertEqual(from_stdin.stdout, expected)

    def test_reads_several_files_in_order(self):
        # Given
        with tempfile.TemporaryDirectory() as tmp:
            first = Path(tmp) / "first.txt"
            second = Path(tmp) / "second.txt"
            first.write_text(pitch_bend_line(1, 8192) + "\n", encoding="utf-8")
            second.write_text(pitch_bend_line(1, 0) + "\n", encoding="utf-8")

            # When
            result = self.run_script(str(first), str(second))

        # Then
        self.assertEqual(result.stdout.splitlines(), [
            pitch_bend_line(1, 8192) + "  # pitch bend +0.00 cents (±2 semitones)",
            pitch_bend_line(1, 0) + "  # pitch bend -200.00 cents (±2 semitones)"])

    def test_takes_the_pitch_bend_ranges_from_its_options(self):
        # Given
        stdin = "".join(pitch_bend_line(channel, 12288) + "\n" for channel in (1, 2, 3))

        # When
        result = self.run_script("--pb-range", "12", "--channel-pb-range", "2=48", "--channel-pb-range", "3=5",
                                 stdin=stdin)

        # Then
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([line.split("  # ")[1] for line in result.stdout.splitlines()], [
            "pitch bend +600.00 cents (±12 semitones)",
            "pitch bend +2400.00 cents (±48 semitones)",
            "pitch bend +250.00 cents (±5 semitones)"])

    def test_rejects_a_malformed_channel_range(self):
        for argument in ("2", "2=", "x=48", "0=48", "17=48", "2=0", "2=abc"):
            with self.subTest(argument=argument):
                # When
                result = self.run_script("--channel-pb-range", argument, stdin="")

                # Then
                self.assertEqual(result.returncode, 2)
                self.assertIn("--channel-pb-range", result.stderr)

    def test_names_a_missing_file(self):
        # When
        result = self.run_script("/nonexistent/midi-out.txt")

        # Then
        self.assertEqual(result.returncode, 1)
        self.assertIn("/nonexistent/midi-out.txt", result.stderr)

    def test_prints_its_usage(self):
        # When
        result = self.run_script("--help")

        # Then
        self.assertEqual(result.returncode, 0)
        self.assertIn("--pb-range", result.stdout)
        self.assertIn("--channel-pb-range", result.stdout)

    def test_reads_and_writes_utf8_whatever_the_locale(self):
        # Given
        env = dict(os.environ, LC_ALL="C", LANG="C", PYTHONUTF8="0", PYTHONIOENCODING="")

        # When
        result = subprocess.run([sys.executable, str(SCRIPT)], input=(self.ONE_BYTE_LINE + "\n").encode("utf-8"),
                                capture_output=True, env=env)

        # Then
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.decode("utf-8"), self.ONE_BYTE_LINE + self.ONE_BYTE_ANNOTATION + "\n")


if __name__ == "__main__":
    unittest.main()

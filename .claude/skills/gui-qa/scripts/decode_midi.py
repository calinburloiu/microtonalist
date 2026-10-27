#!/usr/bin/env python3
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

"""Annotates the text output of ReceiveMIDI with the cents that its MIDI messages tune to.

    decode_midi.py [--pb-range SEMITONES] [--channel-pb-range CH=SEMITONES]... [FILE...]

Reads the given files, or the standard input without a file, and prints every line unchanged. A line that carries a
decodable message also gets a trailing `  # <annotation>`:

* A MIDI Tuning Standard octave tuning (scale/octave tuning, 1-byte or 2-byte form, non-real-time or real-time) gets
  the device ID, the channels that its mask selects and the cents of each pitch class from C to B. A truncated or
  overlong one is flagged as malformed instead.
* A pitch bend gets its cents, from the pitch bend range of its channel: `--channel-pb-range CH=SEMITONES` if given
  for that channel, else `--pb-range`, which is ±2 semitones by default.

It is written from the MIDI Tuning Standard of the MIDI 1.0 specification, and shares no code with the tuners'
encoders, so that a bug in one cannot cancel out in the other.

    receivemidi virt synth ts nn | decode_midi.py --channel-pb-range 2=48
"""
from __future__ import annotations

import argparse
import math
import re
import sys
from decimal import ROUND_HALF_UP, Decimal
from typing import Iterable, Mapping, Optional, Sequence

KEY_NAMES = ["C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "G♯", "A", "B♭", "B"]

NON_REAL_TIME_ID = 0x7E
REAL_TIME_ID = 0x7F
MTS_SUB_ID_1 = 0x08
# The sub-ID 2 of the scale/octave tuning messages, mapped to the bytes per pitch class of their tuning data.
OCTAVE_SUB_ID_2_BYTES = {0x08: 1, 0x09: 2}
CHANNEL_MASK_BYTES = 3
HEADER_BYTES = 4
PITCH_CLASSES = 12

CENTER_14_BIT = 8192
DEFAULT_PB_RANGE = 2
CENTS_PER_SEMITONE = 100
CENTS_PRECISION = Decimal("0.01")

PITCH_BEND_PATTERN = re.compile(r"\bchannel\s+(\d+)\s+pitch-bend\s+(\d+)\s*$")
HEX_BYTE_PATTERN = re.compile(r"[0-9A-Fa-f]{2}")


def annotate(line: str, pb_range: float = DEFAULT_PB_RANGE,
             channel_pb_ranges: Optional[Mapping[int, float]] = None) -> str:
    """Returns `line` followed by `  # <annotation>` when it carries a message that this script decodes, else `line`.

    `line` is a ReceiveMIDI line without its terminator. `pb_range` is the pitch bend range in semitones of the
    channels not in `channel_pb_ranges`, which maps a channel (1-16) to its range.
    """
    annotation = _annotate_sysex(line) or _annotate_pitch_bend(line, pb_range, channel_pb_ranges or {})
    return f"{line}  # {annotation}" if annotation else line


def _sysex_bytes(line: str) -> Optional[list[int]]:
    """The bytes of a `system-exclusive hex <bytes> dec` line, or `None` for any other line."""
    tokens = line.split()
    if "system-exclusive" not in tokens:
        return None
    tokens = tokens[tokens.index("system-exclusive") + 1:]
    if not tokens or tokens[0] != "hex":
        return None

    data = []
    for token in tokens[1:]:
        if not HEX_BYTE_PATTERN.fullmatch(token):
            break
        data.append(int(token, 16))
    return data


def _annotate_sysex(line: str) -> Optional[str]:
    data = _sysex_bytes(line)
    if (data is None or len(data) < HEADER_BYTES or data[0] not in (NON_REAL_TIME_ID, REAL_TIME_ID)
            or data[2] != MTS_SUB_ID_1 or data[3] not in OCTAVE_SUB_ID_2_BYTES):
        return None

    bytes_per_pitch_class = OCTAVE_SUB_ID_2_BYTES[data[3]]
    form = f"{bytes_per_pitch_class}-byte"
    tuning = data[HEADER_BYTES + CHANNEL_MASK_BYTES:]
    expected = PITCH_CLASSES * bytes_per_pitch_class
    if len(tuning) != expected:
        return f"malformed MTS octave {form}: {len(tuning)} data bytes, expected {expected}"

    timing = "non-real-time" if data[0] == NON_REAL_TIME_ID else "real-time"
    ff, gg, hh = data[HEADER_BYTES:HEADER_BYTES + CHANNEL_MASK_BYTES]
    cents = [_octave_cents(tuning[i:i + bytes_per_pitch_class]) for i in range(0, expected, bytes_per_pitch_class)]
    tunings = " ".join(f"{key} {value}" for key, value in zip(KEY_NAMES, cents))
    return f"MTS octave {form} {timing}, device {data[1]:02X}, channels {_channels(ff, gg, hh)}: {tunings}"


def _octave_cents(pitch_class_bytes: Sequence[int]) -> str:
    """The cents offset of a pitch class from its tuning bytes: `value - 64` for one byte; for two bytes, 100 cents
    times the 14-bit value (MSB * 128 + LSB) relative to 8192: -100.00 for 00 00, 0 for 40 00, +99.99 for 7F 7F."""
    if len(pitch_class_bytes) == 1:
        return f"{pitch_class_bytes[0] - 64:+d}"
    msb, lsb = pitch_class_bytes
    return _format_cents(Decimal((msb * 128 + lsb) - CENTER_14_BIT) * CENTS_PER_SEMITONE / CENTER_14_BIT)


def _channels(ff: int, gg: int, hh: int) -> str:
    """The channels that the mask bytes of a message select, as comma-separated numbers and ranges: bits 0-6 of `hh`
    are channels 1-7, bits 0-6 of `gg` channels 8-14 and bits 0-1 of `ff` channels 15-16."""
    channels = [first + bit for first, count, mask in ((1, 7, hh), (8, 7, gg), (15, 2, ff))
                for bit in range(count) if mask >> bit & 1]
    if not channels:
        return "none"

    runs = [[channels[0], channels[0]]]
    for channel in channels[1:]:
        if channel == runs[-1][1] + 1:
            runs[-1][1] = channel
        else:
            runs.append([channel, channel])
    return ", ".join(str(first) if first == last else f"{first}-{last}" for first, last in runs)


def _annotate_pitch_bend(line: str, pb_range: float, channel_pb_ranges: Mapping[int, float]) -> Optional[str]:
    match = PITCH_BEND_PATTERN.search(line)
    if not match:
        return None

    channel, value = int(match.group(1)), int(match.group(2))
    semitones = channel_pb_ranges.get(channel, pb_range)
    # Exact decimal arithmetic, so that a half cent rounds the same everywhere.
    cents = Decimal(value - CENTER_14_BIT) * Decimal(repr(semitones)) * CENTS_PER_SEMITONE / CENTER_14_BIT
    return f"pitch bend {_format_cents(cents)} cents (±{semitones:g} semitones)"


def _format_cents(cents: Decimal) -> str:
    return f"{cents.quantize(CENTS_PRECISION, rounding=ROUND_HALF_UP):+.2f}"


def _positive_semitones(text: str) -> float:
    try:
        semitones = float(text)
    except ValueError:
        raise argparse.ArgumentTypeError(f"expected a number of semitones, got {text!r}") from None
    if not (math.isfinite(semitones) and semitones > 0):
        raise argparse.ArgumentTypeError(f"semitones must be positive, got {text!r}")
    return semitones


def _channel_range(text: str) -> tuple[int, float]:
    channel_text, _, semitones_text = text.partition("=")
    try:
        channel = int(channel_text)
    except ValueError:
        raise argparse.ArgumentTypeError(f"expected CH=SEMITONES, got {text!r}") from None
    if not 1 <= channel <= 16:
        raise argparse.ArgumentTypeError(f"the channel must be 1-16, got {channel_text!r}")
    return channel, _positive_semitones(semitones_text)


def _parse_args(argv: Optional[Sequence[str]]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="decode_midi.py",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        description=("Prints every line of ReceiveMIDI's output, and annotates the MTS octave tunings and the pitch "
                     "bends\nwith `  # <cents per pitch class>` and `  # pitch bend <cents> cents (±<range> "
                     "semitones)`."),
        epilog="Reads the standard input without a file. Exit status: 0, 1 on an unreadable file, 2 on a usage error.")
    parser.add_argument("files", nargs="*", metavar="FILE", help="ReceiveMIDI output to annotate; stdin if none")
    parser.add_argument("--pb-range", type=_positive_semitones, default=DEFAULT_PB_RANGE, metavar="SEMITONES",
                        help="pitch bend range, in semitones, of the channels without --channel-pb-range "
                             f"(default: {DEFAULT_PB_RANGE})")
    parser.add_argument("--channel-pb-range", type=_channel_range, action="append", default=[], metavar="CH=SEMITONES",
                        dest="channel_pb_ranges",
                        help="pitch bend range, in semitones, of channel CH (1-16); repeatable, e.g. 2=48")
    return parser.parse_args(argv)


def _lines(path: str) -> Iterable[str]:
    if not path:
        yield from sys.stdin
    else:
        with open(path, encoding="utf-8") as file:
            yield from file


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = _parse_args(argv)
    channel_pb_ranges = dict(args.channel_pb_ranges)
    # The annotations have non-ASCII characters, and ReceiveMIDI writes UTF-8 whatever the locale.
    sys.stdin.reconfigure(encoding="utf-8")
    sys.stdout.reconfigure(encoding="utf-8")

    try:
        for path in args.files or [""]:
            for line in _lines(path):
                print(annotate(line.rstrip("\r\n"), args.pb_range, channel_pb_ranges), flush=True)
    except OSError as error:
        print(f"decode_midi.py: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

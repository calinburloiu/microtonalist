# gui-qa: the agent's reference

You test microtonalist's GUI through the `qa` MCP server's tools, which start the app with virtual MIDI ports.

## The run directory

`get_run` gives the run's ID, e.g. `qa-3f9c`, and its directory, `$TMPDIR/microtonalist-qa/<run>/`. Every path a tool
takes is inside it, and it holds:

| File | Holds |
| ---- | ----- |
| `app.log` | The app's standard output and error |
| `midi-in-<device>.txt` | Each line that `send_midi` sent to the device, after the time it was sent |
| `midi-out-<device>.txt` | What the device received from the app, as ReceiveMIDI prints it, and ReceiveMIDI's errors |
| `midi-tools.log` | The output and errors of every `sendmidi` |
| `snapshots/<n>.png` | Each `snapshot`, `<n>` counting from 1 |
| `screenshots/os-<n>-<window ID>.png` | Each window of each `os_screenshot`, `<n>` counting the calls from 1 |
| `tools.log` | One line per tool call: the time, the tool, its arguments, and `ok` with its result or `error` with the message |
| `qa.conf` | The app's configuration, which the server writes |

`d.sock`, `server.json` and `processes.json` are the server's.

## The fixtures

Copy them into the run directory, then start the app from there; the repo's copies stay unchanged:

```bash
cp .claude/skills/gui-qa/fixtures/qa.mtlist .claude/skills/gui-qa/fixtures/qa.mtlist.tracks "<runDir>/"
```

- `qa.mtlist` has three tunings on C, which the app names C Just Major, C Romanian Minor and C Septimal. In cents from
  12-EDO, the keys not listed being 0:

  | Tuning | D | E♭ | E | F | F♯ | G | A | B♭ | B |
  | ------ | - | -- | - | - | -- | - | - | -- | - |
  | C Just Major | +4 | | −14 | −2 | | +2 | −16 | | −12 |
  | C Romanian Minor | +4 | +16 | | | −17 | +2 | +6 | −4 | |
  | C Septimal | +31 | −33 | | −29 | | +2 | +33 | −31 | |

- `qa.mtlist.tracks`, its rig, has one track: the input `qa:keys`, the pedal tuning changer, the tuner
  `mtsOctave1ByteNonRealTime`, and the output `qa:synth`. Neither the notes nor the pedals pass through, so `synth`
  receives only the MTS messages.

A rig names a QA device `qa:<device>`, `<device>` matching `[A-Za-z0-9_-]{1,32}`. `start_app` rewrites the rig in place
so that each becomes the run's virtual port, `{ "name": "CoreMIDI4J - <run>-<device>", "vendor": "<Unknown
Manufacturer>" }`, and opens it. A device that a track uses as its input takes `send_midi`; one used as an output
records in `midi-out-<device>.txt`. Other tools take the `<device>` name, e.g. `keys`.

`start_app` refuses:

- a composition outside the run directory, or whose path, relative to the run directory, has a name with a character
  other than a letter, a digit, `.`, `_` or `-`;
- a composition with a `$ref` at its root;
- a rig outside the run directory: it's `<composition>.tracks`, or the file that the composition's `tracksUrl` names;
- a rig that names any device but a QA device, a QA device elsewhere than a track's `input` or `output`, or any
  `$ref`.

## The tools

Optional arguments are in brackets, with their defaults.

| Tool | Arguments | Does, and answers |
| ---- | --------- | ----------------- |
| `get_run` | | `{runId, runDir}` |
| `start_app` | `composition`, [`platform`: `headless`], [`unplugged`: `[]`] | Starts the app, one at a time, and answers once it shows a window: `{pid, platform, devices, appLog, config, rig}`, each device `{device, port, input, output, plugged, midiIn, midiOut}`. `unplugged` names QA devices to leave unplugged. If the app exits first, or shows no window within 60 s, the error points at `app.log`. |
| `stop_app` | | Sends `SIGTERM`, waits up to 10 s, then kills the app; then stops its MIDI ports: `{exitCode, killed}` |
| `send_midi` | `device`, `lines` | Sends SendMIDI command lines to an input device: `{sent, file}`; see [Sending MIDI](#sending-midi) |
| `unplug`, `replug` | `device` | Removes, or restores, both ports of a device, like pulling or plugging its cable: `{device, plugged}`. The output keeps going to the same file. |
| `list_windows` | | `{windows}`, each `{index, type, title, x, y, width, height, focused, owner}`. A menu, a tooltip or a dialog is a window of its own; `owner` is a popup's owner window. |
| `dump` | [`window`: all] | The visible scene graph, `{windows}`, each `{index, type, title, nodes}`. A node line is `<Type>[ #<id>][ text="…"][ value="…"][ accessibleText="…"][ selected][ focused][ disabled][ truncated]`, indented two spaces per ancestor shown. |
| `snapshot` | [`window`], [`selector`], [`scale`: 1] | A PNG, inline, of the selector's node, else of the window, else of the first window, with its popups pasted over it; `{file, width, height}`. `scale` is in (0, 4]; use 2 for small text. |
| `click` | `selector`, [`window`: all], [`button`: `primary`], [`clickCount`: 1] | Clicks the node; `secondary` opens a context menu, `clickCount` 2 double-clicks: `{matchedBy, node, disabled}` |
| `press_key` | `key` | Presses a JavaFX `KeyCode` name, or a combination, in the focused window or the dialog on top: `ENTER`, `DIGIT1`, `DOWN`, `ESCAPE`, `Shortcut+N` (`Shortcut` is Command on macOS) |
| `type_text` | `text` | Types into the focused control; click it first |
| `wait_idle` | | Waits until the JavaFX Application Thread has handled every pending event |
| `read_clipboard`, `write_clipboard` | —, `text` | The clipboard's text, `{text}`, `null` if none. Headless, it's in memory; on the desktop, the macOS pasteboard. |
| `os_screenshot` | | A PNG, inline, of each on-screen window of the app, as macOS draws it: `{files}` |

- `window` is an index from `list_windows`.
- The input tools, `click`, `press_key` and `type_text`, answer once the app has handled the input, so the next `dump`
  sees its effect. MIDI input is handled on other threads: call `wait_idle` after `send_midi`, and check again before
  you call a missing change a failure.
- The app notices an `unplug` or a `replug` only when macOS tells it, about half a second later, which `wait_idle`
  doesn't wait for: read `midi-out-<device>.txt` again a second later before you call a missing reset a failure, and
  don't stop the app before then.
- Trust `dump` for the state, and a snapshot for what a user sees: truncation, order, alignment, a control that draws
  itself, such as a canvas.
- `os_screenshot` works only on the desktop platform, with the Screen Recording permission of the app that runs Claude
  Code; without it, the tool says so. Headless, use `snapshot`.
- While the Mac's screen is locked, `os_screenshot` fails with screencapture's `could not create image from window`.

## Selectors

A selector is a string. Among the visible nodes, it names those whose `accessibleText` equals it; if none, those whose
`id` equals it; if none, those whose visible text equals it, such as a button's, a label's or a list row's. The tool
acts on the single match and tells in `matchedBy` which kind matched; quote it in the report. Several matches, or none,
are an error that lists them. `dump` shows each node's `#<id>`, `text` and `accessibleText`. Give `window` to scope a
selector to one window.

## Sending MIDI

`send_midi` takes a QA input device and SendMIDI command lines, e.g.
`{ "device": "keys", "lines": ["ch 1 cc 66 127", "ch 1 cc 66 0"] }`, and sends them in order. Its `sendmidi` lives for
the whole run, so a command's state carries over to later calls:

- Start every line with `ch <n>`, the channel, from 1 to 16.
- Write a hex number with an `H` suffix, e.g. `raw 90H 3CH 00H`.
- A relative timestamp between commands, `+SS.MIL` or `+HH:MM:SS.MIL`, delays the next one, e.g.
  `ch 1 cc 66 127 +00.500 cc 66 0`.

Each token must be one of:

- a command, short or long form: `ch`, `on`, `off`, `pp`, `cc`, `cc14`, `pc`, `cp`, `pb`, `rpn`, `nrpn`, `syx`, `raw`,
  `panic`, `rst`, `as`, `tun`, `clock`, `mc`, `start`, `stop`, `cont`, `tc`, `spp`, `ss`, `mpe`, `mpetest`;
- a parameter, within the count its command takes: a decimal number, also with an `M` suffix; a hex number with an `H`
  suffix; a pitch bend's `value/range`; a note name, e.g. `C3`, `F#2` or `Db4`, where `C3` is 60;
- a timestamp.

`dev`, `virt`, `file`, `syf`, `mpp`, `hex`, `dec`, `omc`, `nowait` and `list`, with their long forms, are refused, and
so is any other token. A bad line refuses the whole call, and nothing is sent.

| Message | Line |
| ------- | ---- |
| Note on, note off | `ch 1 on 60 100`, `ch 1 off 60 0` |
| Control change | `ch 1 cc 66 127` |
| Pitch bend, from 0 to 16383, 8192 being none | `ch 1 pb 8192` |
| Channel pressure | `ch 1 cp 64` |
| Raw bytes | `ch 1 raw 90H 3CH 64H` |
| System exclusive, without `F0` and `F7` | `ch 1 syx 7EH 7FH 06H 01H` |
| An RPN, e.g. pitch bend sensitivity ±12 semitones, as its CCs | `ch 2 cc 100 0 cc 101 0 cc 6 12 cc 38 0` |

### The pedal tuning changer

By default, it selects the previous tuning on CC 67, the soft pedal, and the next one on CC 66, the sostenuto, on any
channel, when the pedal is pressed: its value goes from 0 to above 0. Press and release, e.g.
`["ch 1 cc 66 127", "ch 1 cc 66 0"]`; a pedal held down triggers no second change.

## Reading the output

Mark the output by its line count before you act, and read only what came after:

```bash
N=$(wc -l < "<runDir>/midi-out-synth.txt")
# act, then:
tail -n +$((N + 1)) "<runDir>/midi-out-synth.txt" | python3 .claude/skills/gui-qa/scripts/decode_midi.py
```

ReceiveMIDI prints one message per line, timestamped, notes as numbers:

```
22:59:36.996   channel  1   control-change    66   127
22:59:36.996   channel  2   pitch-bend          8192
22:59:36.996   system-exclusive hex 7E 7F 08 08 03 7F 7F 40 40 44 50 40 40 2F 42 40 46 3C 40 dec
```

A pitch bend is one 14-bit number. A system exclusive message is its bytes in hex, without `F0` and `F7`.

`decode_midi.py` prints every line and annotates each MTS octave tuning with the cents of each pitch class, and each
pitch bend with its cents for a pitch bend range, ±2 semitones by default. Run
`python3 .claude/skills/gui-qa/scripts/decode_midi.py --help` for its usage; don't read its source. The line above
decodes as C Romanian Minor:

```
… dec  # MTS octave 1-byte non-real-time, device 7F, channels 1-16: C +0 C♯ +0 D +4 E♭ +16 E +0 F +0 F♯ -17 G +2 G♯ +0 A +6 B♭ -4 B +0
```

Check the log with `grep -E 'ERROR|Exception' "<runDir>/app.log"`.

## What the tuners send

The rig's `tuner` sets what the output receives. Each tuner resets when the app starts and when the output is plugged
in again, sending what its section says. An RPN shows as its CC lines: CC 100, the parameter's LSB, then CC 101, its
MSB; then the value, CC 6 and maybe CC 38; then CC 100 127 and CC 101 127, the null RPN. RPN 0, the pitch bend
sensitivity, takes the semitones in CC 6 and the cents in CC 38; RPN 6, the MPE Configuration Message, takes the Zone's
member count in CC 6.

### MTS octave

`mtsOctave1ByteNonRealTime`, `mtsOctave2ByteNonRealTime`, `mtsOctave1ByteRealTime` and `mtsOctave2ByteRealTime` send
one system exclusive message per tuning, `system-exclusive hex <7E|7F> 7F 08 <08|09> 03 7F 7F <tuning> dec`:

- `7E` non-real-time, `7F` real-time; device `7F`, all devices; `08`, MTS; `08` the 1-byte form, `09` the 2-byte form;
  `03 7F 7F`, all 16 channels.
- 1-byte form: 12 bytes, C to B, each `40H` plus the cents, rounded, from −64 to +63.
- 2-byte form: 24 bytes, C to B, each pitch class a 14-bit value, MSB then LSB: `40H 00H` is 0 cents, `00H 00H` −100,
  `7FH 7FH` about +100.

On reset, they send the current tuning. They pass no input through, by default.

### MPE

`mpe` gives each note a Member Channel, by default those of the Lower Zone: channels 2 to 16, the Master Channel
being 1, with pitch bend sensitivities of ±48 semitones on the Member Channels and ±2 on the Master Channel. On reset,
after stopping the notes sounding, it configures the Lower Zone, then the Upper Zone: each Zone's MPE Configuration
Message on its Master Channel, then, for an enabled Zone, the pitch bend sensitivity of its Master Channel and of each
Member Channel. By default, that is the Lower Zone's MPE Configuration Message on channel 1, then the pitch bend
sensitivity of channels 1 to 16, then the disabled Upper Zone's MPE Configuration Message on channel 16 with 0
members. A tuning sends nothing until notes sound:
each note sends its channel's pitch bend, the offset of its pitch class plus any expression, before its note on, and a
tuning change retunes the channels of the notes sounding. Decode with `--pb-range 48 --channel-pb-range 1=2`.

### Monophonic pitch bend

`monophonicPitchBend` plays one note at a time on one output channel, channel 1 by default, with a pitch bend
sensitivity of ±2 semitones by default. On reset, it sends a pitch bend of 8192, then the pitch bend sensitivity. Each
note sends its pitch bend, the offset of its pitch class plus the performer's pitch bend, if it changed, then its note
on; a tuning change retunes the note sounding. Decode with the default range.

## The MIDI tools

The server runs the `sendmidi` and `receivemidi` that the setup installs from their releases' `.pkg`, at the versions
that [`docs/development/gui-qa.md`](../../../docs/development/gui-qa.md#one-time-setup) gives as known to work;
`send_midi`'s checks follow that SendMIDI's help. They have these quirks:

- A note on with velocity 0 shows as a `note-off`.
- ReceiveMIDI's `hex` mode drops a system exclusive message's newline; the server uses the default mode, as above.
- SendMIDI's `dec` after `hex raw` on one line makes `raw` read its bytes as decimal; the server refuses both, so use
  the `H` suffix.

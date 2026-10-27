# GUI QA acceptance report: desktop

- **Date:** 2026-10-27
- **Commit tested:** `fe6236b`. The working tree was clean.
- **Issue:** #397, the acceptance run of [design §6](2026-10-26-design.md#6-acceptance).
- **Platform:** desktop, on macOS 26, with the Screen Recording permission.
- **App under test:** the throwaway JavaFX stand-in that the [headless report](2026-10-27-qa-report-headless.md)
  describes, with the same JAR (SHA-256 `2cee1785…`).
- **Test plan:** the same throwaway plan, not committed:
  `/private/tmp/claude-501/-Users-calinburloiu-Development-microtonalist2/0973e388-3d9c-4b2b-8a97-2c55570a8850/scratchpad/task26/test-plan.md`.
- **Fixtures:** the `gui-qa` skill's.
- **Run:** `qa-6fe2`, under `$TMPDIR/microtonalist-qa/`, which also holds its snapshots and OS screenshots.
  - The first app start covered TP-01 to TP-18 and the exploration.
  - A second, fresh start rechecked TP-03.

## Results

| ID | Priority | Result | Note |
| -- | -------- | ------ | ---- |
| TP-01 | must | pass | |
| TP-02 | must | pass | |
| TP-03 | must | **fail** | Bug 1, the planted bug; it failed again on the retry and after the fresh start |
| TP-04 | should | pass | |
| TP-05 | should | pass | |
| TP-06 | must | pass | |
| TP-07 | should | pass | |
| TP-08 | should | pass | |
| TP-09 | must | pass | |
| TP-10 | should | pass | |
| TP-11 | must | pass | |
| TP-12 | must | pass | |
| TP-13 | must | pass | |
| TP-14 | should | pass | |
| TP-15 | should | pass | |
| TP-16 | must | pass | |
| TP-17 | should, desktop | pass | |
| TP-18 | must | pass | |
| TP-19 | should, human | not run | |

## Evidence

The MIDI lines are `midi-out-synth.txt` through the skill's decoder, shortened to the MTS octave 1-byte message and its
cents.

- **Start-up (TP-01):** the `dump` matches the headless run's: three rows, C Just Major selected,
  `Button accessibleText="Next tuning"` and `Label #status text="C Just Major"`. The synth received:

  ```
  15:57:15.354 MTS C +0 C♯ +0 D +0 E♭ +0 E +0 F +0 F♯ +0 G +0 G♯ +0 A +0 B♭ +0 B +0
  15:57:15.357 MTS C +0 C♯ +0 D +4 E♭ +0 E -14 F -2 F♯ +0 G +2 G♯ +0 A -16 B♭ +0 B -12
  ```

- **The rewritten rig:** the run's `qa.mtlist.tracks` names `CoreMIDI4J - qa-6fe2-keys` and
  `CoreMIDI4J - qa-6fe2-synth`, with the vendor `<Unknown Manufacturer>`.
  - The log opened both devices.
  - The 15 `send_midi` calls are in `midi-in-keys.txt`.
  - Each pedal press sent one MTS message (TP-06 to TP-08).
- **Unplugging and replugging the synth (TP-09, TP-10):** `unplug synth` at 15:59:56.633 and `replug synth` at
  16:00:06.447. Then the log reads `16:00:06.782 … Successfully opened output device "CoreMIDI4J - qa-6fe2-synth"`,
  and the synth received the current tuning:

  ```
  16:00:06.783 MTS C +0 C♯ +0 D +4 E♭ +16 E +0 F +0 F♯ -17 G +2 G♯ +0 A +6 B♭ -4 B +0
  ```

  TP-10's replug sent C Romanian Minor at 16:00:38.664.
- **Selectors:** `tools.log` has 33 successful clicks:
  - 10 matched by `accessibleText` ("Next tuning");
  - 6 by `id` (`virtual-flow`, the list's inner flow, for the context menu);
  - 17 by `text`.
- **Context menu, clipboard, dialog and typing (TP-11 to TP-14):**
  - With the clipboard first set to `sentinel`, "Copy name" made `read_clipboard` give `"C Romanian Minor"`.
  - The "Rename" Stage shows `Label text="Rename the tuning"` and `TextField value="C Septimal"`.
  - Typing a name and pressing Enter renamed the row and the status to "Septimal 7-limit".
  - Command+R, Command+A, Command+V and Enter renamed C Just Major to "Doina".
  - No rename sent MIDI.
- **OS screenshots (TP-16, TP-17):** `os_screenshot` was called four times:
  - `os-1-5370.png`: the main window, whose title bar reads "Microtonalist";
  - `os-2-5380.png`: the context menu, a window of its own;
  - `os-3-5385.png` and `os-3-5370.png`: the Rename dialog in front, and the main window inactive behind it;
  - `os-4-5370.png`: a long name, ellipsized in the status.
- **Log (TP-18):** `grep -ciE 'ERROR|Exception' app.log` gives 0. Both `stop_app` calls returned
  `{"exitCode":143,"killed":false}`.

## Bugs

All three are in the throwaway stand-in and match the headless run's. They are reported, not fixed.

### Bug 1: "Next tuning" moves two tunings (TP-03, must; the planted bug)

1. Start the app with the QA composition, so that C Just Major is selected.
2. Click "Next tuning" once.

- **Expected:** C Romanian Minor is selected, and the synth receives one MTS message.
- **Actual:** C Septimal is selected, and the synth receives two MTS messages in the same millisecond. The log shows
  `Tuning to "C Romanian Minor"`, then `Tuning to "C Septimal"`.

  ```
  15:57:59.803 MTS C +0 C♯ +0 D +4 E♭ +16 E +0 F +0 F♯ -17 G +2 G♯ +0 A +6 B♭ -4 B +0
  15:57:59.803 MTS C +0 C♯ +0 D +31 E♭ -33 E +0 F -29 F♯ +0 G +2 G♯ +0 A +33 B♭ -31 B +0
  ```

- Space on the focused button does the same (15:58:55.256), so the button's action is at fault, not the click.
- From C Septimal, a click moves one step, to C Just Major. The pedals and the arrow keys always move one step.

### Bug 2: confirming a rename brings back the tuning that the pedal changed (exploration)

1. Press Command+R on "Doina", the renamed C Just Major.
2. While the dialog shows, press Sostenuto. The synth receives C Romanian Minor (16:04:04.248).
3. Type "Renamed X" and press Enter.

- **Expected:** the tuning stays on C Romanian Minor.
- **Actual:** the renamed row is selected again, and the synth is retuned to C Just Major: `16:04:12.217 MTS … D +4 …
  E -14 F -2 G +2 … A -16 … B -12`. With the soft pedal instead, the same happened, from C Septimal at 16:04:49.504.

### Bug 3: an empty name is accepted (exploration)

1. Press Command+R.
2. Select all, delete the text, and press Enter.

- **Expected:** OK is refused.
- **Actual:** the row and the status are blank (`snapshots/4.png`).

## Exploration

The findings match the headless run's:

- the fast pedals stay consistent;
- a pedal behind the menu or the dialog changes the tuning;
- a duplicate name is accepted;
- a long name clips the row and ellipsizes the status;
- an empty band is left under the button row.

Command+R with the context menu open closed the menu here, while the headless run left it open.

Unplugging the input `keys` sent CC 64 0, CC 66 0 and CC 123 0 on all 16 channels, then the current tuning
(16:05:37.860). This is the app's existing behaviour when an input goes away. The plan was wrong to say that the synth
receives only tunings; it isn't a bug.

## Deviation

The agent also wrote copies of its first app start's log and MIDI output, `app-run1.log` and
`midi-out-synth-run1.txt`, outside its run directory, into the session's scratchpad. This breaks its rule to write only
in the run directory. Nothing was written in the repo: `git status --porcelain` and `git diff` are unchanged.

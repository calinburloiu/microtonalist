# GUI QA acceptance report: headless

- **Date:** 2026-10-27
- **Commit tested:** `59f0647` for run `qa-fdc1`; `fe6236b` for the rerun `qa-9528`; `8c97e50` for `qa-a223`;
  `0025d10` for `qa-c395`. The working tree was clean in every run.
- **Versions:** SendMIDI 1.4.4, ReceiveMIDI 1.6.2, JDK 25.0.4.1 (Temurin), OpenJFX 26.0.2, macOS 26 (26.6.2).
- **Issue:** #397, the acceptance run of [design §6](2026-10-26-design.md#6-acceptance).
- **Platform:** headless.
- **App under test:** a throwaway JavaFX stand-in, kept outside the repo. It is a clone at `f85c517` whose Swing tuning
  list is replaced by a JavaFX window, with one planted bug. Its fat JAR (SHA-256 `2cee1785…`) was copied over
  `app/app/target-bsp/scala-3.9.0/microtonalist-app.jar`.
- **Test plan:** a throwaway plan, written from `test-plan-template.md` and not committed:
  `/private/tmp/claude-501/-Users-calinburloiu-Development-microtonalist2/0973e388-3d9c-4b2b-8a97-2c55570a8850/scratchpad/task26/test-plan.md`.
  It has 19 scenarios, TP-01 to TP-19, and an exploration list.
- **Fixtures:** the `gui-qa` skill's.
- **Runs:** all live under `$TMPDIR/microtonalist-qa/`, which also holds their snapshots.
  - `qa-fdc1`: the full plan, with one app start.
  - `qa-9528`: a partial rerun, after the driver fixes below, of TP-01 and TP-12 to TP-14, plus checks of the tooling
    defects. It had four app starts.
  - `qa-a223` and `qa-c395`: partial reruns, after [defects 3 and 4](#qa-tooling-defects), of a second throwaway plan,
    not committed either:
    `/private/tmp/claude-501/-Users-calinburloiu-Development-microtonalist2/7ce6aa30-6cf4-4ef0-8946-05d01561daa9/scratchpad/task26/popup-rerun-plan.md`.
    Its scenarios, TP-16b to TP-16e, cover the context menu's look and a right-click on a row.

## Results

| ID | Priority | Result | Note |
| -- | -------- | ------ | ---- |
| TP-01 | must | pass | Rerun: pass |
| TP-02 | must | pass | |
| TP-03 | must | **fail** | Bug 1, the planted bug; it failed again on the retry |
| TP-04 | should | pass | |
| TP-05 | should | pass | |
| TP-06 | must | pass | |
| TP-07 | should | pass | |
| TP-08 | should | pass | |
| TP-09 | must | pass | |
| TP-10 | should | pass | |
| TP-11 | must | pass | |
| TP-12 | must | pass | Rerun: pass |
| TP-13 | must | pass | Its Command+A hid [a QA tooling defect](#qa-tooling-defects). Rerun: pass |
| TP-14 | should | pass | Rerun: pass |
| TP-15 | should | pass | |
| TP-16 | must | pass | |
| TP-17 | should, desktop | not run | It runs on the desktop only |
| TP-18 | must | pass | |
| TP-19 | should, human | not run | |

The second plan's scenarios:

| ID | Priority | `qa-a223` | `qa-c395` |
| -- | -------- | --------- | --------- |
| TP-16b | must | pass | pass |
| TP-16c | should | pass | pass |
| TP-16d | must | — | pass |
| TP-16e | must | — | pass |

`qa-a223` ran TP-16b and TP-16c only.

## Evidence

The MIDI lines are `midi-out-synth.txt` through the skill's decoder, shortened to the MTS octave 1-byte message and its
cents.

- **Start-up (TP-01):** `list_windows` gives one Stage, titled "Microtonalist". `dump`:

  ```
  ListView #tunings focused
    VirtualFlow #virtual-flow
      ListCell text="C Just Major" selected focused
      ListCell text="C Romanian Minor"
      ListCell text="C Septimal"
  Button accessibleText="Next tuning"
  Label #status text="C Just Major"
  ```

  The synth received 12-EDO, then C Just Major:

  ```
  15:33:47.686 MTS C +0 C♯ +0 D +0 E♭ +0 E +0 F +0 F♯ +0 G +0 G♯ +0 A +0 B♭ +0 B +0
  15:33:47.689 MTS C +0 C♯ +0 D +4 E♭ +0 E -14 F -2 F♯ +0 G +2 G♯ +0 A -16 B♭ +0 B -12
  ```

- **The rewritten rig:** `start_app` rewrote `qa:keys` and `qa:synth` in the run's `qa.mtlist.tracks` to
  `CoreMIDI4J - qa-fdc1-keys` and `CoreMIDI4J - qa-fdc1-synth`, with the vendor `<Unknown Manufacturer>`.
  - The 13 `send_midi` calls are in `midi-in-keys.txt`, e.g. `15:35:09.865 ch 1 cc 66 127`.
  - Each pedal press sent one MTS message (TP-06 to TP-08), e.g. Sostenuto from C Just Major gave
    `15:35:09.868 MTS … D +4 E♭ +16 … F♯ -17 G +2 … A +6 B♭ -4`.
- **Unplugging and replugging the synth (TP-09, TP-10):** `unplug synth` at 15:36:03.318 and `replug synth` at
  15:36:11.069. The log then reads `Successfully opened output device "CoreMIDI4J - qa-fdc1-synth"`, and the synth
  received the current tuning again:

  ```
  15:36:11.401 MTS C +0 C♯ +0 D +4 E♭ +16 E +0 F +0 F♯ -17 G +2 G♯ +0 A +6 B♭ -4 B +0
  ```

  For TP-10, the pedal changed the tuning while the synth was unplugged, and the replug sent it at 15:36:42.027.
- **Selectors:** `tools.log` has 36 successful clicks:
  - 9 matched by `accessibleText` ("Next tuning");
  - 7 by `id` (`tunings`, the secondary click that opens the context menu);
  - 20 by `text` (rows, menu items, the dialog's `OK`).
- **Context menu and clipboard (TP-11):** `list_windows` showed a `ContextMenu` owned by window 0, holding
  `MenuLabel text="Copy name"` and `MenuLabel text="Rename…"`. After "Copy name", `read_clipboard` gave
  `{"text":"C Romanian Minor"}`.
- **Dialog, typing and paste (TP-12, TP-13):**
  - The dialog is a Stage titled "Rename", with `Label text="Rename the tuning"` and `TextField value="C Septimal"`.
  - `type_text` with "Septimal 7-limit", then Enter, gave `ListCell text="Septimal 7-limit" selected focused` and
    `Label #status text="Septimal 7-limit"`, with no MIDI.
  - `write_clipboard "Doina"`, then Command+V in the dialog, renamed C Just Major to "Doina".
- **Layout (TP-16):** `snapshots/1.png`, `2.png`, `3.png`, `5.png` and `6.png` show no truncation and nothing cut off.
- **Log (TP-18):** `grep -ciE 'ERROR|Exception' app.log` gives 0, in every run. `stop_app` returned
  `{"exitCode":143,"killed":false}`, and the log ends with `Bye bye!`.

### Every tool

`qa-fdc1`'s `tools.log` has 217 calls of 15 of the server's 16 tools. The 16th, `os_screenshot`, works on the desktop
only.

| Tool | Calls | | Tool | Calls |
| ---- | ----- | - | ---- | ----- |
| `get_run` | 1 | | `type_text` | 10 |
| `start_app` | 1 | | `read_clipboard` | 4 |
| `stop_app` | 1 | | `write_clipboard` | 2 |
| `list_windows` | 24 | | `send_midi` | 13 |
| `dump` | 53 | | `wait_idle` | 13 |
| `snapshot` | 11 | | `unplug` | 3 |
| `click` | 38, 2 of them errors | | `replug` | 3 |
| `press_key` | 40 | | | |

## Bugs

All three are in the throwaway stand-in, which is never fixed: they are reported, not triaged into the repo.

### Bug 1: "Next tuning" moves two tunings (TP-03, must; the planted bug)

1. Start the app with the QA composition, so that C Just Major is selected.
2. Click "Next tuning" once.

- **Expected:** C Romanian Minor is selected, and the synth receives one MTS message.
- **Actual:** C Septimal is selected, and the synth receives two MTS messages, 1 ms apart:

  ```
  15:34:24.036 MTS C +0 C♯ +0 D +4 E♭ +16 E +0 F +0 F♯ -17 G +2 G♯ +0 A +6 B♭ -4 B +0
  15:34:24.037 MTS C +0 C♯ +0 D +31 E♭ -33 E +0 F -29 F♯ +0 G +2 G♯ +0 A +33 B♭ -31 B +0
  ```

- From C Romanian Minor, one click goes to C Just Major, through C Septimal (15:34:51.512). From C Septimal, it moves
  one step, to C Just Major (TP-04 passes).
- The pedals always move one step.
- `tools.log` shows one click per attempt.

### Bug 2: confirming a rename brings back the tuning that the pedal changed (exploration)

1. Select C Romanian Minor and press Command+R.
2. While the dialog shows, press and release Sostenuto. The list and the synth move to the next tuning.
3. Type a name and click OK.

- **Expected:** C Romanian Minor is renamed, and the tuning stays where the pedal put it.
- **Actual:** the renamed row is selected again, and the synth is retuned to it:
  `15:40:49.788 MTS … D +4 E♭ +16 … F♯ -17 …`, logged as `Tuning to "C Romanian Minor"`. It happened again from C
  Septimal at 15:42:37.723.

### Bug 3: an empty name is accepted (exploration)

1. Open Rename.
2. Delete the text and press Enter.

- **Expected:** OK is refused, or the old name is kept.
- **Actual:** the row and the status are blank (`ListCell selected focused`, `Label #status`, both with no text;
  `snapshots/9.png`). "Copy name" then copies `""`.

## QA tooling defects

Four defects were in the QA driver, not in the app. Each was fixed test first, and a rerun confirms each fix.
Defects 1, 2 and 4 were in the headless input, `RobotInput`. Each has a failing shared `InputBehaviors` case, which
holds the desktop's `EventInput` to the same behaviour. `EventInput` had none of them.

1. **Enter does nothing in the Rename dialog right after Command+R** (the run's bug 4). Any other Command chord in the
   dialog, such as TP-13's Command+A, cleared it.
   - Cause: the driver sent a chord's presses and releases in one queued step. The accelerator's `showAndWait`
     blocked that step after the press of R, so JavaFX's headless `Robot` kept Command down while the dialog showed,
     and the dialog got Command+Enter.
   - In `tools.log`: Shortcut+R, then ENTER, left the dialog open at 15:40:15, 15:41:16 and 15:41:33.
   - Fixed in `370bde2`: one step per press and release.
2. **Space on the focused "Next tuning" button does nothing.**
   - Cause: OpenJFX 26.0.2's headless `Robot` reports a released mouse button as still down, so a clicked node stays
     pressed, and a pressed button ignores Space. The click at 15:44:31, then SPACE at 15:44:37, sent no MIDI.
   - Fixed in `fe6236b`: after a click, the driver clears the pressed state.
3. **A popup in a snapshot hides the window under it with an opaque white box.** The task's review found it in the
   desktop run's `qa-6fe2/snapshots/2.png`, as a white frame around the context menu's shadow.
   - Cause: `Snapshots` filled each window's image with its scene's fill. A popup's scene has none, and JavaFX
     paints a snapshot with no fill white.
   - Fixed in `8c97e50`: a transparent fill when the scene has none.
4. **A right-click on an unselected row opens the context menu but doesn't select the row.** A comparison of the
   reruns found it. On the desktop, `qa-8ec4` at 20:08:11.368, the row is selected. Headless, `qa-a223` at
   20:05:02.400, the selection stays on C Just Major, and no tuning change follows.
   - Cause: two quirks of OpenJFX 26.0.2's headless `Robot`. Its press is marked synthesized
     (`HeadlessRobot.java:152`), so a list cell selects on the release, not on the press (`CellBehaviorBase.java`,
     lines 170–187 and 244). Its release then goes to the window under the pointer (line 170): that is the context
     menu, which the press opened. So the cell never gets the release.
   - Fixed in `0025d10`: when the `Robot`'s release misses the clicked window, the driver fires the release and the
     click there.

The reruns confirm the fixes:

- **Defects 1 and 2, `qa-9528`:**
  - Shortcut+R at 16:11:22.651, then ENTER at 16:11:28.080, closed the dialog. `list_windows` then lists only
    "Microtonalist".
  - Space on the button now does what a click does, bug 1 included:

    ```
    16:12:09.891 MTS C +0 C♯ +0 D +4 E♭ +16 E +0 F +0 F♯ -17 G +2 G♯ +0 A +6 B♭ -4 B +0
    16:12:09.891 MTS C +0 C♯ +0 D +31 E♭ -33 E +0 F -29 F♯ +0 G +2 G♯ +0 A +33 B♭ -31 B +0
    ```

- **Defect 3:** the [desktop report](2026-10-27-qa-report-desktop.md#qa-tooling-defects) has its confirmation. A
  headless popup has no shadow margin: its window is the menu's 82×58 box. So the headless snapshots never showed the
  white box (`qa-9528/snapshots/2.png`, before the fix). `qa-a223`, after the fix, shows that the menu's box is the
  only part of the window that changes, so nothing regressed.
- **Defect 4, `qa-c395`:** a right-click on a row that isn't selected now selects it, and the synth receives one MTS
  message; the context menu opens as before. Right-clicking C Romanian Minor at 20:17:19.520, and later C Septimal at
  20:17:50.649, sent:

  ```
  20:17:19.516 MTS C +0 C♯ +0 D +4 E♭ +16 E +0 F +0 F♯ -17 G +2 G♯ +0 A +6 B♭ -4 B +0
  20:17:50.645 MTS C +0 C♯ +0 D +31 E♭ -33 E +0 F -29 F♯ +0 G +2 G♯ +0 A +33 B♭ -31 B +0
  ```

  Left clicks, "Copy name", and "Rename…" with Cancel still behave as before (TP-16e).

## Exploration

- **Fast pedals:** five Sostenuto presses in one call sent five MTS messages in order, and the window agreed with the
  last one.
- **A pedal while the context menu or the dialog shows:** the selection changes behind it. With the dialog, confirming
  leads to bug 2.
- **Duplicate and very long names:**
  - A duplicate name is accepted.
  - A 102-character name clips the row, gives the list a horizontal scroll bar, and ellipsizes the status
    (`Label #status … truncated`, `snapshots/10.png`).
- **Leftover context menu:** "Rename…" leaves the context menu open behind the dialog until the dialog closes, also in
  `qa-9528` and `qa-c395`. This is how the stand-in behaves, not a defect of the QA tooling.
- **Unplugging the input `keys`:** the synth received CC 64 0, CC 66 0 and CC 123 0 on all 16 channels, then the
  current tuning (15:43:54.408). This is the app's existing behaviour when an input goes away. The plan was wrong to
  say that the synth receives only tunings; it isn't a bug.
- **Layout:** an empty band of about 200 px is left under the button row. A rename changes only the GUI's label: the log
  keeps the composition's names.

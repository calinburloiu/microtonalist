# Manual QA of the JavaFX GUI, in process: guide

- **Date:** 2026-10-22
- **Commit:** `89b5f06d`
- **Issue:** #397. Related: #99, #383.
- **Background:** [`2026-10-22-gui-qa-research.md`](2026-10-22-gui-qa-research.md).

How to test the initial JavaFX GUI of #99 by hand, end to end, as a coding agent or a human in a terminal. The app runs
headless inside its own JVM, a small Java agent loaded into that JVM reads and drives its scene graph, and two virtual
MIDI ports stand in for the instrument. No macOS permission is needed, and the desktop stays free during a run.

This guide was written before #99. Its steps were run on 2026-10-22 against a stand-in JavaFX app (OpenJFX 26.0.2,
Temurin 25.0.4.1) and, for MIDI, against the current Swing app. Expect to adjust the control names and the checks to the
GUI that #99 builds.

## What to test

Unit tests cover the view models. A manual run covers what they can't:

- the wiring between each view and its view model: bindings, handlers, shortcuts;
- the application wiring, and the hops between the UI and business threads;
- layout: truncated labels, ordering, resizing;
- the full loop: a GUI action produces the right MIDI output, and MIDI input, such as a pedal, updates the GUI.

## One-time setup

- **JDK 25**, as for the build.
- **SendMIDI and ReceiveMIDI** ([gbevin](https://github.com/gbevin/SendMIDI)): install the `.pkg` of each tool's latest
  GitHub release, which puts the binary in `/usr/local/bin`. The Homebrew formulas build with Xcode, and a Homebrew
  copy in `/opt/homebrew/bin` shadows the `.pkg` one; check with `which -a sendmidi receivemidi`. Versions 1.3.1 and
  1.4.4 were used here.

## 1. Build the app

```bash
sbtn assembly
JAR="$PWD/app/app/target-bsp/scala-$(cat project/scala-version)/microtonalist-app.jar"
ls -l "$JAR"
```

Check that the JAR's time is after your last change. Don't use `bin/mtlist`: it runs the `target/` JAR, which only a
plain `sbt assembly` builds, and which may come from another branch.

## 2. Create the workspace

Use a scratch directory, e.g. the agent's scratchpad, never a directory in the repository:

```bash
export QA=<scratch directory>/qa
mkdir -p "$QA"
```

`$QA/qa.mtlist`, a composition with three tunings on C:

```json
{
  "intonationStandard": "justIntonation",
  "tuningReference": { "basePitchClass": 0 },
  "tuningReducer": "direct",
  "tunings": [
    { "name": "Just Major", "scale": ["9/8", "5/4", "4/3", "3/2", "5/3", "15/8", "2"] },
    { "name": "Romanian Minor", "scale": ["9/8", "6/5", "7/5", "3/2", "27/16", "16/9", "2"] },
    { "name": "Septimal", "scale": ["8/7", "7/6", "21/16", "3/2", "12/7", "7/4", "2"] }
  ]
}
```

`$QA/qa.mtlist.tracks`, its rig, next to it. CoreMIDI4J prefixes the port names with `CoreMIDI4J - `, and reports the
vendor of the ports from SendMIDI and ReceiveMIDI as the literal `<Unknown Manufacturer>`:

```json
{
  "tracks": [
    {
      "id": "qa",
      "name": "QA",
      "input": { "midiDeviceId": { "name": "CoreMIDI4J - QA In", "vendor": "<Unknown Manufacturer>" } },
      "tuningChangers": ["pedal"],
      "tuner": "mtsOctave1ByteNonRealTime",
      "output": { "midiDeviceId": { "name": "CoreMIDI4J - QA Out", "vendor": "<Unknown Manufacturer>" } }
    }
  ]
}
```

`$QA/qa.conf`, a config passed on the command line, so that `~/.microtonalist/microtonalist.conf` is never used:

```bash
printf 'core {\n  libraryBaseUrl = "%s/"\n}\n' "$QA" > "$QA/qa.conf"
```

If another clone, such as `microtonalist2`, runs a test at the same time, give its ports other names.

## 3. Build the driver

The driver is a Java agent that the app's JVM loads with `-javaagent`, so the app needs no test code. It executes each
line appended to `$QA/cmd.txt` on the JavaFX Application Thread, and writes the answer to the N-th command to
`$QA/resp/N.txt`.

Save it as `$QA/QaDriver.java`:

```java
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Cell;
import javafx.scene.control.Control;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextInputControl;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.robot.Robot;
import javafx.scene.transform.Transform;
import javafx.stage.Stage;
import javafx.stage.Window;
import javax.imageio.ImageIO;

/**
 * Throwaway QA driver, loaded with -javaagent:qa-driver.jar=<dir>. It executes the commands appended to <dir>/cmd.txt,
 * one per line, and writes the answer to command N into <dir>/resp/N.txt.
 */
public class QaDriver {

  private static Path dir;
  private static Robot robot;

  public static void premain(String args) {
    dir = Path.of(args);
    Thread thread = new Thread(QaDriver::loop, "qa-driver");
    thread.setDaemon(true);
    thread.start();
  }

  private static void loop() {
    try {
      Files.createDirectories(dir.resolve("resp"));
      Path cmdFile = dir.resolve("cmd.txt");
      if (!Files.exists(cmdFile)) {
        Files.createFile(cmdFile);
      }
      waitForToolkit();
      int done = 0;
      while (true) {
        List<String> lines = Files.readAllLines(cmdFile);
        while (done < lines.size()) {
          String cmd = lines.get(done).trim();
          done++;
          String answer;
          try {
            answer = execute(cmd);
          } catch (Throwable t) {
            answer = "ERROR " + t;
          }
          Path tmp = dir.resolve("resp/" + done + ".tmp");
          Files.writeString(tmp, answer + "\n");
          Files.move(tmp, dir.resolve("resp/" + done + ".txt"), StandardCopyOption.ATOMIC_MOVE);
        }
        Thread.sleep(50);
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  /** Waits until the app has started JavaFX and shown a window. */
  private static void waitForToolkit() throws InterruptedException {
    while (true) {
      try {
        if (fx(() -> !Window.getWindows().isEmpty())) {
          return;
        }
      } catch (IllegalStateException e) {
        // The toolkit isn't initialized yet.
      }
      Thread.sleep(200);
    }
  }

  private static String execute(String cmd) throws Exception {
    String[] words = cmd.split("\\s+", 2);
    String arg = words.length > 1 ? words[1] : "";
    return switch (words[0]) {
      case "windows" -> fx(QaDriver::windows);
      case "dump" -> fx(QaDriver::dump);
      case "snap" -> snap(arg);
      case "click" -> click(arg, MouseButton.PRIMARY);
      case "rclick" -> click(arg, MouseButton.SECONDARY);
      case "key" -> key(arg);
      case "type" -> type(arg);
      case "idle" -> idle();
      default -> "ERROR unknown command: " + cmd;
    };
  }

  private static String windows() {
    StringBuilder sb = new StringBuilder();
    List<Window> windows = Window.getWindows();
    for (int i = 0; i < windows.size(); i++) {
      Window w = windows.get(i);
      String title = w instanceof Stage s ? " \"" + s.getTitle() + "\"" : "";
      sb.append(String.format("%d %s%s at %.0f,%.0f size %.0fx%.0f focused=%s%n", i, w.getClass().getSimpleName(),
          title, w.getX(), w.getY(), w.getWidth(), w.getHeight(), w.isFocused()));
    }
    return sb.toString().stripTrailing();
  }

  private static String dump() {
    StringBuilder sb = new StringBuilder();
    List<Window> windows = Window.getWindows();
    for (int i = 0; i < windows.size(); i++) {
      Window w = windows.get(i);
      sb.append("window ").append(i).append(' ').append(w.getClass().getSimpleName()).append('\n');
      if (w.getScene() != null) {
        dump(w.getScene().getRoot(), 1, sb);
      }
    }
    return sb.toString().stripTrailing();
  }

  /** Prints the controls, the nodes with an ID and the canvases; skips what isn't visible and the empty cells. */
  private static void dump(Node n, int depth, StringBuilder sb) {
    if (!n.isVisible() || (n instanceof Cell<?> c && c.isEmpty())) {
      return;
    }
    boolean shown = n instanceof Control || n.getId() != null || n instanceof Canvas;
    if (shown) {
      sb.append("  ".repeat(depth)).append(typeName(n));
      if (n.getId() != null) {
        sb.append(" #").append(n.getId());
      }
      if (n instanceof Labeled l && l.getText() != null && !l.getText().isEmpty()) {
        sb.append(" text=\"").append(l.getText()).append('"');
      }
      if (n instanceof TextInputControl t) {
        sb.append(" value=\"").append(t.getText()).append('"');
      }
      if (n.getAccessibleText() != null) {
        sb.append(" accessibleText=\"").append(n.getAccessibleText()).append('"');
      }
      if (n instanceof Cell<?> c && c.isSelected()) {
        sb.append(" selected");
      }
      if (n.isFocused()) {
        sb.append(" focused");
      }
      if (n.isDisabled()) {
        sb.append(" disabled");
      }
      if (n instanceof Labeled l && isTruncated(l)) {
        sb.append(" truncated");
      }
      sb.append('\n');
    }
    if (n instanceof Parent p) {
      for (Node child : p.getChildrenUnmodifiable()) {
        dump(child, shown ? depth + 1 : depth, sb);
      }
    }
  }

  /** The simple name of the node's class, or of its closest named superclass, since skins create anonymous cells. */
  private static String typeName(Node n) {
    Class<?> c = n.getClass();
    while (c.getSimpleName().isEmpty()) {
      c = c.getSuperclass();
    }
    return c.getSimpleName();
  }

  private static boolean isTruncated(Labeled l) {
    return l.lookupAll(".text").stream()
        .anyMatch(t -> t instanceof javafx.scene.text.Text text && !text.getText().equals(l.getText())
            && text.getText().endsWith(l.getEllipsisString()));
  }

  /** Snapshots every window to <name>-<window index>.png, at the given scale (1 by default). */
  private static String snap(String arg) throws IOException {
    String[] words = arg.split("\\s+");
    String name = words[0].isEmpty() ? "snap" : words[0];
    double scale = words.length > 1 ? Double.parseDouble(words[1]) : 1;
    idle();
    List<WritableImage> images = fx(() -> {
      List<WritableImage> result = new ArrayList<>();
      for (Window w : Window.getWindows()) {
        SnapshotParameters params = new SnapshotParameters();
        params.setTransform(Transform.scale(scale, scale));
        result.add(w.getScene().getRoot().snapshot(params, null));
      }
      return result;
    });
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < images.size(); i++) {
      File file = dir.resolve(name + "-" + i + ".png").toFile();
      save(images.get(i), file);
      sb.append(file).append('\n');
    }
    return sb.toString().stripTrailing();
  }

  /** Clicks the centre of the first visible node matching `#id` or the exact text of a labeled control or cell. */
  private static String click(String selector, MouseButton button) {
    Point2D point = fx(() -> {
      Node node = find(selector);
      Bounds b = node.localToScreen(node.getLayoutBounds());
      return new Point2D(b.getCenterX(), b.getCenterY());
    });
    fx(() -> {
      robot().mouseMove(point);
      robot().mouseClick(button);
      return null;
    });
    return idle();
  }

  private static Node find(String selector) {
    for (Window w : Window.getWindows()) {
      if (w.getScene() == null) {
        continue;
      }
      List<Node> found = new ArrayList<>();
      collect(w.getScene().getRoot(), selector, found);
      if (!found.isEmpty()) {
        return found.getFirst();
      }
    }
    throw new IllegalArgumentException("no visible node matches " + selector);
  }

  private static void collect(Node n, String selector, List<Node> found) {
    if (!n.isVisible()) {
      return;
    }
    boolean matches = selector.startsWith("#")
        ? selector.substring(1).equals(n.getId())
        : n instanceof Labeled l && selector.equals(l.getText());
    if (matches) {
      found.add(n);
    }
    if (n instanceof Parent p) {
      for (Node child : p.getChildrenUnmodifiable()) {
        collect(child, selector, found);
      }
    }
  }

  /** Presses a key, e.g. `DIGIT1`, `DOWN`, `ENTER`, or `Shortcut+N` (Command on macOS). */
  private static String key(String arg) {
    String[] parts = arg.split("\\+");
    List<KeyCode> codes = new ArrayList<>();
    for (String part : parts) {
      codes.add(part.equalsIgnoreCase("Shortcut") ? KeyCode.COMMAND : KeyCode.valueOf(part.toUpperCase()));
    }
    fx(() -> {
      for (KeyCode code : codes) {
        robot().keyPress(code);
      }
      for (KeyCode code : codes.reversed()) {
        robot().keyRelease(code);
      }
      return null;
    });
    return idle();
  }

  /** Types text into the focused control. */
  private static String type(String text) {
    fx(() -> {
      Node target = Window.getWindows().stream()
          .filter(Window::isFocused).findFirst().orElseThrow()
          .getScene().getFocusOwner();
      for (char c : text.toCharArray()) {
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_TYPED, String.valueOf(c), "", KeyCode.UNDEFINED,
            false, false, false, false));
      }
      return null;
    });
    return idle();
  }

  /** Waits until the JavaFX Application Thread has run what is queued, twice to let a pulse pass. */
  private static String idle() {
    fx(() -> null);
    fx(() -> null);
    return "ok";
  }

  private static Robot robot() {
    if (robot == null) {
      robot = new Robot();
    }
    return robot;
  }

  private static <T> T fx(Supplier<T> supplier) {
    CompletableFuture<T> future = new CompletableFuture<>();
    Platform.runLater(() -> {
      try {
        future.complete(supplier.get());
      } catch (Throwable t) {
        future.completeExceptionally(t);
      }
    });
    try {
      return future.get(10, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new RuntimeException(e.getCause() != null ? e.getCause() : e);
    }
  }

  private static void save(WritableImage image, File file) throws IOException {
    int w = (int) image.getWidth();
    int h = (int) image.getHeight();
    PixelReader reader = image.getPixelReader();
    BufferedImage buffered = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        buffered.setRGB(x, y, reader.getArgb(x, y));
      }
    }
    ImageIO.write(buffered, "png", file);
  }
}
```

Compile it against the fat JAR, which holds JavaFX, and package it with a `Premain-Class`:

```bash
mkdir -p "$QA/driver-classes"
javac -d "$QA/driver-classes" -cp "$JAR" "$QA/QaDriver.java"
printf 'Premain-Class: QaDriver\n' > "$QA/manifest.txt"
jar cfm "$QA/qa-driver.jar" "$QA/manifest.txt" -C "$QA/driver-classes" .
```

Save this helper as `$QA/drv` and make it executable (`chmod +x "$QA/drv"`). It sends one command and prints the
answer:

```bash
#!/bin/bash
# Usage: drv <command>. Sends a command to the QA driver in $QA and prints its answer.
set -eu
echo "$*" >> "$QA/cmd.txt"
n=$(wc -l < "$QA/cmd.txt" | tr -d ' ')
for _ in $(seq 150); do
  if [ -f "$QA/resp/$n.txt" ]; then
    cat "$QA/resp/$n.txt"
    exit 0
  fi
  if ! kill -0 "$(cat "$QA/app.pid")" 2>/dev/null; then
    echo "drv: the app isn't running; see $QA/app.log" >&2
    exit 1
  fi
  sleep 0.1
done
echo "drv: no answer to command $n after 15 s" >&2
exit 1
```

And this one as `$QA/mts-cents`, also executable. It prints the cents per pitch class of each MTS message in
ReceiveMIDI's output:

```python
#!/usr/bin/env python3
"""Prints the cents per pitch class of each MTS octave 1-byte message in receivemidi's output."""
import re
import sys

KEYS = ["C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "G♯", "A", "B♭", "B"]
for line in sys.stdin:
    match = re.search(r"system-exclusive hex 7E [0-9A-F]{2} 08 08 ((?:[0-9A-F]{2} ){15})", line)
    if match:
        data = [int(b, 16) for b in match.group(1).split()][3:]
        print(" ".join(f"{key} {value - 64:+d}" for key, value in zip(KEYS, data)))
```

## 4. Open the MIDI ports

Open both ports before the app, so that it finds them at startup:

```bash
: > "$QA/in.txt"
nohup sh -c "tail -f '$QA/in.txt' | sendmidi virt 'QA In' --" > "$QA/sendmidi.log" 2>&1 &
nohup receivemidi virt "QA Out" ts nn > "$QA/out.txt" 2>&1 &
echo $! > "$QA/receivemidi.pid"
```

- `QA In` is the app's input: append a line to `$QA/in.txt` to send a message, e.g. `echo "cc 66 127" >> "$QA/in.txt"`.
  A one-shot `sendmidi virt` would close the port on exit, which the app sees as an unplug.
- `QA Out` is the app's output: ReceiveMIDI writes each message to `$QA/out.txt`, a SysEx message complete on one line.
  Don't truncate `out.txt` while it runs: it keeps writing at its old offset and pads the file with NULs. Note its line
  count instead, and read from there: `tail -n +$((MARK + 1)) "$QA/out.txt"`.

## 5. Start the app

```bash
nohup java -Dglass.platform=Headless -javaagent:"$QA/qa-driver.jar=$QA" \
  -jar "$JAR" "$QA/qa.mtlist" "$QA/qa.conf" > "$QA/app.log" 2>&1 &
echo $! > "$QA/app.pid"
"$QA/drv" windows
```

`-Dglass.platform=Headless`, with a capital H, selects JavaFX's headless platform: the app shows no window, and the
driver's input never reaches macOS. `drv windows` waits until the app has shown its window, up to 15 s; if the app
dies, `drv` says so, and `$QA/app.log` tells why.

At startup, the app resets the output and sends the first tuning:

```bash
"$QA/mts-cents" < "$QA/out.txt"
```

```
C +0 C♯ +0 D +0 E♭ +0 E +0 F +0 F♯ +0 G +0 G♯ +0 A +0 B♭ +0 B +0
C +0 C♯ +0 D +4 E♭ +0 E -14 F -2 F♯ +0 G +2 G♯ +0 A -16 B♭ +0 B -12
```

## 6. Drive the app and check it

| Command | Does |
| ------- | ---- |
| `windows` | Lists the windows; a context menu, a tooltip or a dialog is a window of its own |
| `dump` | Prints the scene graph of every window: the controls, with their ID, text, value, accessible text, and whether they are selected, focused, disabled or truncated |
| `snap <name> [scale]` | Renders every window to `$QA/<name>-<window index>.png`, at scale 1 by default; use 2 for small text |
| `click <selector>`, `rclick <selector>` | Clicks the centre of the first visible node that matches, with the left or the right button |
| `key <key>` | Presses a key: a JavaFX `KeyCode` name, e.g. `DIGIT1`, `DOWN`, `ESCAPE`, or a combination, e.g. `Shortcut+N` |
| `type <text>` | Types text into the focused control |
| `idle` | Waits until the Application Thread has run what is queued |

A selector is `#<id>`, or the exact text of a label, button, menu item or list row, e.g. `click "C Septimal"`. The
input commands wait for the Application Thread before they answer, so the next `dump` sees their effect.

For each check:

1. **Act** in the GUI with the driver, or on the instrument by appending to `$QA/in.txt`. The pedal tuning changer
   selects the previous tuning on CC 67 (soft pedal) and the next one on CC 66 (sostenuto), when the pedal is pressed:
   send `cc 66 127`, then `cc 66 0`.
2. **Check the GUI** with `dump`, and the layout with `snap`, then read the PNG. Trust `dump` for the state, and the
   snapshot for what a user sees: truncation, order, alignment, a control that draws itself, such as a `Canvas`.
3. **Check the MIDI** that the output received since the action, with `mts-cents`.
4. **Check the log:** `grep -E 'ERROR|Exception' "$QA/app.log"` finds nothing.

A failure may be a flake: repeat the check once before calling it a bug.

## 7. Checks for the initial GUI

The expected tunings, in cents from 12-EDO, keys not listed being 0:

| Tuning | D | E♭ | E | F | F♯ | G | A | B♭ | B |
| ------ | - | -- | - | - | -- | - | - | -- | - |
| C Just Major | +4 | | −14 | −2 | | +2 | −16 | | −12 |
| C Romanian Minor | +4 | +16 | | | −17 | +2 | +6 | −4 | |
| C Septimal | +31 | −33 | | −29 | | +2 | +33 | −31 | |

Adapt this list to what #99 builds:

1. At startup, the tuning list shows the three tunings, in the composition's order, with C Just Major selected, and the
   output received the reset and C Just Major.
2. Clicking a row selects it, and the output receives its tuning, once.
3. The digit keys `1` to `3` select the matching tuning; `4` changes nothing.
4. Up and Down move the selection, and wrap around at both ends.
5. Sostenuto selects the next tuning and soft pedal the previous one, in the list and on the output.
6. Unplugging the output, by killing ReceiveMIDI, and replugging it, by starting it again with `>>` instead of `>`, makes
   the app send the current tuning again; the log says that the device became unavailable, then available.
7. Every control without visible text has an accessible text, shown by `dump`.
8. Nothing is truncated or misaligned in a snapshot at scale 2, and the context menus and dialogs look right in theirs.
9. The log has no error.

Then explore for a few minutes beyond the list, e.g. fast repeated input, a pedal during a click, or a dialog left open
while MIDI arrives.

## 8. Stop

```bash
kill "$(cat "$QA/app.pid")" "$(cat "$QA/receivemidi.pid")"
pkill -f "sendmidi virt QA In"; pkill -f "tail -f $QA/in.txt"
```

The app takes about a second to exit, while it lets the output send what is queued.

## 9. Report

For each check: `pass`, `fail`, `flaky` (failed, then passed when repeated) or `not run`, with the reason. For each bug,
the minimal steps that reproduce it. Quote the relevant `dump`, MIDI and log lines as evidence, and name the commit
tested and whether the working tree was dirty.

## Limitations

Some checks need a human with the app on a real screen:

- No title bar, system menu bar or native dialog. On the headless platform, `FileChooser` and `DirectoryChooser` throw
  `UnsupportedOperationException`, so opening and saving files can't be tested this way.
- The headless layout is close to the desktop's, not identical: list rows were 23 px high against 24 px, though text was
  truncated at the same place. How the app looks on a Retina screen needs the real window.
- The headless clipboard is in memory, never the macOS pasteboard; drag and drop is untested.
- Whether it sounds in tune needs an instrument and an ear.

## Troubleshooting

- **`drv` gets no answer, but the app runs:** the app hasn't started JavaFX, or hasn't shown a window yet. The driver
  waits for both.
- **The app can't find its devices:** check the names and vendor in the rig file against `receivemidi list`, which shows `QA In`, and `sendmidi list`, which shows `QA Out`, and that the
  ports were opened before the app.
- **`Unsupported JavaFX configuration` in the log:** harmless; the fat JAR loads JavaFX from the class path.
- **The headless platform** is experimental in JavaFX 26, selected by an undocumented property. If an OpenJFX upgrade
  breaks it, `windows` shows no window or `snap` fails.

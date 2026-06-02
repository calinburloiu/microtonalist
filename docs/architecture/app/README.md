# `app` module architecture

## Responsibility

`app` is the top-level executable module — the only one with a `main` entry point intended for end users (`cli` and
`experiments` are separate, smaller executables). Its single job is **composition-root wiring** and **application
lifecycle**:

- parse command-line arguments;
- construct and connect the lower-level modules (`appConfig`, `format`, `tuner`, `ui`) and the cross-cutting
  `businessync` event/threading layer;
- load the user's composition, build the resolved `TuningList`, load tracks, and open the GUI;
- own the process lifecycle: top-level error handling, exit codes, and a JVM shutdown hook for clean teardown.

`app` contains almost no domain logic of its own — it is deliberately thin glue, a single Scala source file
(`MicrotonalistApp.scala`). The real work lives in the modules it depends on.

## Key types

**`MicrotonalistApp`** is the `object` holding `main` and the wiring logic. `main` wraps everything in a `Try`,
dispatches on the argument count, and recovers by mapping exceptions to process exits. The method to read first is
`run`, the composition root that builds every module and starts the application. Its sealed `AppException` hierarchy
carries the process exit codes: `AppUsageException` (1, wrong arguments or unparseable URL/path),
`NoDeviceAvailableException` (2), `AppConfigException` (3); any other exception is logged and exits with 1000.

**`BuildInfo`** is generated at compile time by the `sbt-buildinfo` plugin and exposes `name`/`version`/`scalaVersion`/
`sbtVersion`; `MicrotonalistApp` uses `BuildInfo.version` in the startup banner.

**Module wiring objects.** `app` does not define these — it instantiates them, treating each as the seam through which a
lower module exposes its public surface as a small manually-wired container. Each is documented in its own module's
architecture doc:

- **`MainConfigManager`** / **`CoreConfig`** (`appConfig`) — load the HOCON config and expose
  `coreConfig.libraryBaseUrl`.
- **`FormatModule`** (`format`) — the repos/formats container; `app` uses `defaultCompositionRepo` to read the `.mtlist`
  composition and `defaultTrackRepo` (passed into `TunerModule`).
- **`TunerModule`** (`tuner`) — the MIDI/tuning runtime; `app` sets `tuningSession.tunings`, opens tracks via
  `trackService`, hands `tuningService` to the GUI, and calls `close()` from the shutdown hook.
- **`Businessync`** (`businessync`) — created once in `run` and threaded through every module that publishes events or
  runs work on the business thread.
- **`TuningList`** (`composition`) — built from the loaded `Composition` via `TuningList.fromComposition`; feeds
  `TuningSession`.
- **`TuningListFrame`** (`ui`) — the Swing window, constructed with `tuningService`, registered on the bus, and shown.

> Note: `FormatModule` and `TunerModule` are hand-rolled `lazy val` service containers, not a DI framework. Wiring is
> explicit and ordered in `run` — see [Future / planned changes](#future--planned-changes).

## Application entry point

The end-to-end startup sequence in `run`:

1. **Parse CLI args.** `main` logs the welcome banner, then matches `args`: `<url>` or `<url> <config-file>` call `run`,
   anything else raises `AppUsageException`. The composition argument may be a URL or a local path (resolved by
   `common.parseUrlOrPath`).
2. **Resolve config & load it.** The config path is the provided one or `MainConfigManager.defaultConfigFile`;
   `MainConfigManager` parses and resolves the HOCON file, exposing `coreConfig` (including `libraryBaseUrl`).
3. **Create the event/threading layer** — a Guava `EventBus` and the `Businessync` wrapping it, the backbone for
   cross-thread communication used by the tuner runtime and the GUI.
4. **Build `FormatModule` and load the composition** — `defaultCompositionRepo.read(inputUrl)` loads the `.mtlist`
   `Composition`, and `TuningList.fromComposition` resolves it into the ordered list of `Tuning`s.
5. **Build `TunerModule` and load tracks** — if the composition declares a tracks URL, the tracks file is opened
   **synchronously** (an `Await.result` over `trackService.open`); then the resolved tunings are pushed into the
   session. (Opening tracks here is temporary — see `// TODO #87`.)
6. **Open the GUI** — `TuningListFrame(tuningService)` is constructed, registered on the bus, and made visible. It
   observes tuning-index changes through the bus and lets the user switch the active tuning.
7. **Register the shutdown hook** — a JVM shutdown hook calls `tunerModule.close()` (closing the `TrackManager` and
   `MidiManager`) and lets teardown settle.

All process-termination logic lives in one place: `main` runs the whole flow inside `Try { … }.recover { … }`, where
recognized `AppException`s call their own `exitWithMessage()` and anything else exits with 1000.

## Dependencies

`app` sits at the very top of the layer graph: nothing depends on it, and it depends — directly or transitively — on
every other application module. Its direct `dependsOn` set is `appConfig`, `businessync`, `common`, `composition`,
`intonation`, `format`, `scMidi`, `tuner`, and `ui`; it declares the `coreMidi4j`, `guava`, and `playJson` libraries and
enables `BuildInfoPlugin`. `app` is the assembly target whose fat JAR main class is `MicrotonalistApp`.

Because it is thin wiring, `app`'s coverage floor is intentionally 0 (tracked by issue #180).

One naming subtlety: the **config** module's SBT project id is `appConfig` while its directory is `config/`, so
`app.dependsOn(appConfig)` pulls in `config/` (package `org.calinburloiu.music.microtonalist.config`). The transitive
layering is:

```
app → ui → tuner → sc-midi → businessync
app → composition → { intonation, tuner }
app → format → { common, composition, tuner }
app → appConfig → common
```

## Future / planned changes

- **Module wiring (issue #100).** The hand-wired `run` with `lazy val` containers is planned to move toward proper
  module classes/traits and possibly dependency injection. Treat the manual wiring documented here as the current state,
  not a long-term contract.
- **GUI migration (issue #99, Swing → JavaFX).** The GUI-construction step will change when the GUI migrates; the GUI
  internals are documented in the `ui` module doc.
- **Track opening (issue #87).** Opening tracks inside `run` is a temporary placement and is expected to move into a
  dedicated composition-opening workflow.

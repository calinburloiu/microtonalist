# `app` module architecture

## Responsibility

`app` is the top-level executable module. It is the only module with a `main` entry point intended for end users
(the `cli` and `experiments` modules are separate, smaller executables). Its single job is **composition root**
wiring and **application lifecycle**:

- parse command-line arguments;
- construct and connect the lower-level modules (`config`/`appConfig`, `format`, `tuner`, `ui`) and the cross-cutting
  `businessync` event/threading layer;
- load the user's composition, build the resolved `TuningList`, load tracks, and open the GUI;
- own the process lifecycle: top-level error handling, exit codes, and a JVM shutdown hook for clean teardown.

`app` contains almost no domain logic of its own — it is deliberately thin glue. The actual work lives in the modules
it depends on. There is exactly one Scala source file in the module:
`app/src/main/scala/org/calinburloiu/music/microtonalist/MicrotonalistApp.scala`.

## Key types

### `MicrotonalistApp`

`app/src/main/scala/org/calinburloiu/music/microtonalist/MicrotonalistApp.scala:35`

The `object` that holds `main` and the wiring logic. Notable members:

- `main(args)` — `MicrotonalistApp.scala:51`. Wraps everything in a `Try`, dispatches on the argument count, and
  recovers by mapping exceptions to process exits (see error handling below).
- `run(inputUrl, maybeConfigPath)` — `MicrotonalistApp.scala:76`. The composition root: builds every module and starts
  the application. This is the method to read first to understand the wiring.
- `parseUrlArg` / `parsePathArg` — `MicrotonalistApp.scala:68`, `:72`. Parse the composition URI (via
  `common.parseUrlOrPath`) and the optional config-file path; both throw `AppUsageException` on malformed input.
- `AppException` (sealed) and its cases — `MicrotonalistApp.scala:37`. Each carries a `statusCode` and an
  `exitWithMessage()` that prints to `stderr` and calls `System.exit`:
  - `AppUsageException` (exit 1) — wrong argument count or unparseable URL/path.
  - `NoDeviceAvailableException` (exit 2) — declared for the case where no configured MIDI device is available.
  - `AppConfigException(message)` (exit 3) — configuration problem.
  - Any other `Exception` is logged and the process exits with code 1000 (`MicrotonalistApp.scala:64`).

### `BuildInfo`

`org.calinburloiu.music.microtonalist.BuildInfo` is generated at compile time by the `sbt-buildinfo` plugin
(`enablePlugins(BuildInfoPlugin)` in `build.sbt:65`, with `buildInfoPackage` set on `build.sbt:72`). It exposes
`name`, `version`, `scalaVersion`, and `sbtVersion`; `MicrotonalistApp` uses `BuildInfo.version` in the startup banner
(`MicrotonalistApp.scala:52`). It is the only generated type in the module.

### Module wiring objects (constructed by `run`, owned by the depended-on modules)

`app` does not define these classes; it instantiates them. They are the seams through which each lower module exposes
its public surface as a small manually-wired container.

- **`MainConfigManager`** (`config` module) —
  `config/src/main/scala/org/calinburloiu/music/microtonalist/config/ConfigManagement.scala:30`. Loads and manages the
  HOCON application config. `MainConfigManager(configPath)` parses the file (`ConfigFactory.parseFile(...).resolve()`),
  exposes typed sub-configs through `SubConfigManager`/`CoreConfigManager`, periodically auto-saves dirty config, and
  saves on close. `MainConfigManager.defaultConfigFile` (`ConfigManagement.scala:114`) returns
  `~/.microtonalist/microtonalist.conf` on macOS.
- **`CoreConfig`** (`config` module) —
  `config/src/main/scala/org/calinburloiu/music/microtonalist/config/CoreConfig.scala:27`. The typed core config:
  `libraryBaseUrl` (the scale/track library root, defaulting to `~/Music/microtonalist/lib/` on macOS) and a nested
  `MetaConfig` (`saveIntervalMillis`, `saveOnExit`). `app` reads `mainConfigManager.coreConfig.libraryBaseUrl` to wire
  the `FormatModule`.
- **`FormatModule`** (`format` module) —
  `format/src/main/scala/org/calinburloiu/music/microtonalist/format/FormatModule.scala:26`. A lazy container that
  builds all file/HTTP/library repos and their JSON formats. `app` uses `defaultCompositionRepo` (to read the
  `.mtlist` composition) and `defaultTrackRepo` (passed into `TunerModule`). Constructor takes the `Businessync` and
  the `libraryBaseUrl` from config.
- **`TunerModule`** (`tuner` module) —
  `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/TunerModule.scala:22`. A lazy container, also
  `AutoCloseable`, that builds the MIDI/tuning runtime: `tuningSession`, `tuningService`, `trackService`,
  `MidiManager`, and `TrackManager` (registered on the `Businessync` bus). `app` sets `tuningSession.tunings`, opens
  tracks via `trackService`, hands `tuningService` to the GUI, and calls `close()` from the shutdown hook.
- **`Businessync`** (`businessync` module) — the event bus + threading layer (business thread / UI thread). Created
  once in `run` (`MicrotonalistApp.scala:79`) over a Guava `EventBus` and threaded through every module that needs to
  publish events or run work on the business thread. The GUI frame and `TrackManager` register themselves on it.
- **`TuningList`** (`composition` module) — built from the loaded `Composition` via
  `TuningList.fromComposition(composition)` (`MicrotonalistApp.scala:85`); its `tunings` feed `TuningSession`.
- **`TuningListFrame`** (`ui` module) — the Swing window, constructed with the `TunerModule.tuningService`
  (`MicrotonalistApp.scala:96`), registered on the bus, and shown.

> Note: the "module" classes (`FormatModule`, `TunerModule`) are hand-rolled `lazy val` service containers, not a DI
> framework. Wiring is explicit and ordered in `run`. See "Future / planned changes".

## Dependencies

`app` sits at the very top of the layer graph: nothing depends on it, and it depends — directly or transitively — on
every other application module. From `build.sbt:52`:

```scala
lazy val app = (project in file("app"))
  .dependsOn(
    appConfig,            // config/  — HOCON application config
    businessync,          // event bus + threading
    common,               // shared utilities (e.g. parseUrlOrPath, PlatformUtils)
    commonTestUtils % Test,
    composition,          // domain model: Composition, TuningList
    intonation,           // interval math (also reached transitively via composition)
    format,               // JSON/file I/O repos and formats
    scMidi,               // Scala-idiomatic MIDI API (also reached via tuner)
    tuner,                // MIDI tuning runtime
    ui,                   // Swing GUI
  )
  .enablePlugins(BuildInfoPlugin)
```

Library dependencies declared directly on `app` (`build.sbt:73`): `coreMidi4j`, `guava` (Guava `EventBus`), and
`playJson`. The module also enables `BuildInfoPlugin` and `assembly` — `app` is the assembly target whose fat JAR's
main class is `org.calinburloiu.music.microtonalist.MicrotonalistApp` (`build.sbt:70`).

Because it is a thin wiring layer, `app`'s coverage floor is intentionally 0 (`build.sbt:79`, tracked by issue #180).

The relationship between the SBT project id and the directory is worth flagging: the project is `app` in `file("app")`,
but the **config** module's SBT project id is `appConfig` while its directory is `config/` (`build.sbt:82`). So
`app.dependsOn(appConfig)` pulls in `config/`. Throughout the codebase, the package is
`org.calinburloiu.music.microtonalist.config`.

Transitive layering (each arrow is a `dependsOn`):

```
app → ui → tuner → sc-midi → businessync
app → composition → { intonation, tuner }
app → format → { common, composition, tuner }
app → appConfig → common
app → tuner → { businessync, common, sc-midi }
```

## Application entry point

The end-to-end startup sequence, verified against `MicrotonalistApp.scala`:

1. **Parse CLI args** — `main` (`:51`) logs the welcome banner with `BuildInfo.version`, then matches on `args`:
   - `Array(url)` → `run(parseUrlArg(url))`;
   - `Array(url, configFile)` → `run(parseUrlArg(url), Some(parsePathArg(configFile)))`;
   - anything else → `AppUsageException` (usage: `microtonalist <input-composition-url> [config-file]`).
   The composition argument may be a URL or a local path (resolved by `common.parseUrlOrPath`).

2. **Resolve config path & load config** — in `run` (`:76`): the config path is the provided one or
   `MainConfigManager.defaultConfigFile` (`~/.microtonalist/microtonalist.conf` on macOS).
   `MainConfigManager(configPath)` parses and resolves the HOCON file, exposing `coreConfig` (including
   `libraryBaseUrl`).

3. **Create the event/threading layer** — a Guava `EventBus` and a `Businessync` wrapping it are created (`:78`–`:79`).
   This is the backbone for cross-thread communication used by the tuner runtime and the GUI.

4. **Build `FormatModule` and load the composition** — `new FormatModule(businessync, coreConfig.libraryBaseUrl)`
   (`:82`); then `formatModule.defaultCompositionRepo.read(inputUrl)` loads the `.mtlist` `Composition` (`:84`), and
   `TuningList.fromComposition(composition)` (`:85`) resolves it into the ordered list of `Tuning`s (applying the
   composition's `TuningMapper`s, `TuningReducer`, and fill).

5. **Build `TunerModule` and load tracks** — `new TunerModule(businessync, formatModule.defaultTrackRepo)` (`:87`).
   If the composition declares a tracks URL (`composition.tracksUrl`), the tracks file is opened **synchronously** via
   `Await.result(trackService.open(uri), 10 seconds)` (`:91`). Then the resolved tunings are pushed into the session:
   `tunerModule.tuningSession.tunings = tuningList.tunings` (`:93`). (Opening tracks here is temporary — see the
   `// TODO #87` note in the source; it is to move into a composition-opening workflow.)

6. **Open the GUI** — `new TuningListFrame(tunerModule.tuningService)` (`:96`) is constructed, registered on the
   `Businessync` bus (`:97`), and made visible (`:98`). The frame observes tuning-index changes through the bus and
   lets the user switch the active tuning.

7. **Register the shutdown hook** — a JVM shutdown hook (`:100`) logs, calls `tunerModule.close()` (closing the
   `TrackManager` and `MidiManager`), sleeps ~1s to let teardown settle, and logs goodbye.

### Error handling and exit codes

`main` runs the whole flow inside `Try { … }.recover { … }` (`:51`, `:61`). Recognized `AppException`s call their own
`exitWithMessage()` (exit codes 1/2/3). Any other exception is logged as "Unexpected error" and the process exits with
code 1000 (`:64`). This keeps all process-termination logic in one place at the top of the stack.

### Configuration: the `config`/`appConfig` relationship

- The HOCON config file location defaults to `~/.microtonalist/microtonalist.conf` (macOS only today;
  `ConfigManagement.scala:114` throws on other platforms — relevant to cross-platform work, issue #149).
- `MainConfigManager` loads/saves the raw HOCON and dispenses typed sub-configs via `SubConfigManager` subclasses
  (e.g. `CoreConfigManager`), each owning a config root path (`core` for `CoreConfig`).
- `CoreConfig.libraryBaseUrl` (default `~/Music/microtonalist/lib/`) is the scale/track library root that
  `FormatModule` uses to resolve library-relative URIs; `MetaConfig` controls auto-save cadence and save-on-exit.
- All of the above live in the **`appConfig`** SBT project (directory `config/`, package `…microtonalist.config`),
  which `app` depends on directly. The `appConfig` module depends only on `common`.

## Future / planned changes

- **Module wiring (issue #100, "Refactor modules wiring").** The current `run` method wires everything by hand with
  explicit construction order and `lazy val` "module" containers. This is planned to move toward proper module
  classes/traits and possibly a dependency-injection mechanism. Treat the manual wiring documented here as the
  current state, not a long-term contract.
- **GUI migration (issue #99, Swing → JavaFX).** `app` currently opens the Swing `TuningListFrame`. When the GUI
  migrates to JavaFX, the entry-point's GUI-construction step (`:96`–`:98`) will change accordingly. The GUI internals
  themselves are documented in the `ui` module doc, not here.
- **Track opening (issue #87).** Opening tracks inside `run` is a temporary placement and is expected to move into a
  dedicated composition-opening workflow.

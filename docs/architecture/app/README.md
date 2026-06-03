# `app` module architecture

## Responsibility

`app` is the top-level executable module — the only one with a `main` entry point intended for end users (`cli` and
`experiments` are separate, smaller executables). It is deliberately thin glue with almost no domain logic of its own:
its job is **composition-root wiring** (constructing and connecting the lower-level modules) and **application
lifecycle** (CLI parsing, top-level error handling, exit codes, and clean teardown). The real work lives in the modules
it depends on.

## Key types

**`MicrotonalistApp`** is the `object` holding `main` and the wiring logic, currently a single Scala source file. `main`
parses the arguments and wraps the whole run in a `Try` that maps exceptions to process exits; `run` is the composition
root that builds every module and starts the application — read it first. The sealed `AppException` hierarchy carries
the exit codes: `AppUsageException` (1), `NoDeviceAvailableException` (2), `AppConfigException` (3); any other exception
is logged and exits with 1000.

**`BuildInfo`** is generated at compile time by the `sbt-buildinfo` plugin; `MicrotonalistApp` uses `BuildInfo.version`
in the startup banner.

## Wiring

`run` is the composition root. It builds the cross-cutting `Businessync` event/threading layer once and threads it
through every module, then wires the modules in dependency order: load the HOCON config, load the user's `.mtlist`
composition and resolve it into a `TuningList`, start the tuner runtime and open its tracks, and finally open the Swing
GUI. A JVM shutdown hook closes the tuner runtime for clean teardown.

Each lower module exposes its public surface through a small wiring seam that `app` instantiates rather than reaching
into the module's internals; the details of each seam belong to that module's own architecture doc. Today these seams
are hand-rolled `lazy val` service containers (e.g. `FormatModule`, `TunerModule`), not a DI framework — the plan is to
give every module a dedicated `*Module` class (issue #100). Treat the current manual wiring as the present state, not a
long-term contract.

## Dependencies

`app` sits at the top of the layer graph: nothing depends on it, and it depends — directly or transitively — on every
other application module. It is the assembly target whose fat JAR main class is `MicrotonalistApp`. Because it is thin
wiring, its coverage floor is intentionally 0 (issue #180).

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

- **Module wiring (issue #100).** The hand-wired `run` with `lazy val` containers is planned to move toward dedicated
  `*Module` classes/traits and possibly dependency injection.
- **GUI migration (issue #99, Swing → JavaFX).** The GUI-construction step will change when the GUI migrates; the GUI
  internals are documented in the `ui` module doc.
- **Track opening (issue #87).** Opening tracks inside `run` is a temporary placement and is expected to move into a
  dedicated composition-opening workflow.

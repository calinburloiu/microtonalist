# `common` module architecture

## Responsibility

The `common` module is a small collection of cross-cutting utilities and abstractions shared by the rest of
Microtonalist. It is not a domain module: it holds no musical concepts. Instead it provides the few building blocks that
many higher modules need but that do not belong to any single one — the `Plugin` extension point, a lock-scoping helper,
a session lifecycle trait, URL/path parsing, and platform detection.

Its role in the layered architecture:

- It sits near the bottom of the dependency graph and is **widely depended upon** (`tuner`, `format`, `scMidi`,
  `appConfig`, `app`), so it deliberately keeps almost no dependencies of its own. See [Dependencies](#dependencies).
- Each utility here is independent of the others; there is no internal coupling between `Plugin`, `Locking`,
  `OpenableSession`, and the package-object functions.

Package: `org.calinburloiu.music.microtonalist.common` (sub-package `.concurrency`).

## Key types

### `Plugin` (`common/src/main/scala/org/calinburloiu/music/microtonalist/common/Plugin.scala`)

`Plugin` (`Plugin.scala:27`) is the base trait for all of Microtonalist's pluggable, user-selectable components. It
defines a two-level naming contract used both at runtime and for JSON (de)serialization:

- `familyName: String` (`Plugin.scala:28`) — the _family_ (context) a plugin belongs to, e.g. `tuningMapper`.
- `typeName: String` (`Plugin.scala:29`) — the specific _type_ within that family that the user chose, e.g. `auto` for
  `AutoTuningMapper`.

The idea is that within one family the user can swap between multiple types. The plugin families across the project are:

- `TuningMapper` (`composition`) — e.g. `AutoTuningMapper`, `ManualTuningMapper`.
- `TuningReducer` (`composition`) — e.g. `MergeTuningReducer`, `DirectTuningReducer`.
- `TuningReference` (`composition`) — e.g. `StandardTuningReference`, `ConcertPitchTuningReference`.
- `Tuner` (`tuner`) — the MTS Octave / MPE / monophonic pitch-bend implementations.
- `TuningChanger` (`tuner`) — e.g. `PedalTuningChanger`.

The serialization side lives in the `format` module: `JsonPluginFormat[P]`
(`format/.../JsonPluginFormat.scala:36`) parses a whole family and uses each type's `typeName` as the JSON `type`
discriminator (`familyName` names the family; a `defaultTypeName` lets the `type` property be omitted). The `Plugin`
trait itself carries only the two `val`s — it has no serialization logic.

### `Locking` (`common/src/main/scala/org/calinburloiu/music/microtonalist/common/concurrency/Locking.scala`)

`Locking` (`Locking.scala:44`) is a mix-in trait providing `@inline` helpers that run a block while holding a lock and
always release it in a `finally`. The lock is passed implicitly, so call sites read like `withReadLock { _x }`:

- `withLock(block)(implicit lock: Lock)` (`Locking.scala:55`) — a plain `java.util.concurrent.locks.Lock`.
- `withReadLock(block)` / `withWriteLock(block)` (`Locking.scala:73`, `:91`) — the read / write side of an implicit
  `ReadWriteLock`.

Used for thread-safe getters/setters in `appConfig` (`ConfigManagement`), `format` (`DeferrableRead`), and `scMidi`
(`MidiDeviceHandle`, `MidiProcessor`).

### `OpenableSession` (`common/src/main/scala/org/calinburloiu/music/microtonalist/common/OpenableSession.scala`)

`OpenableSession` (`OpenableSession.scala:23`) is a small lifecycle trait extending `java.io.Closeable` for a
resource that can be opened against a `URI`, asynchronously: `open(uri): Future[Unit]`, `close()`, `isOpened: Boolean`,
and `uri: Option[URI]`. Implemented by `TrackSession` in the `tuner` module.

### Package-object functions (`common/src/main/scala/org/calinburloiu/music/microtonalist/common/package.scala`)

- `parseUrlOrPath(urlString): Option[URI]` (`package.scala:31`) — parses a string as either an absolute URL or a local
  file path, returning a `URI`. The private `mapPathToUri` enforces the project convention that a directory URI ends in
  `/` (which the Java `Path` API tends to strip). Used by `app` (`MicrotonalistApp`) and `appConfig` (`CoreConfig`).

### `PlatformUtils` (`common/src/main/scala/org/calinburloiu/music/microtonalist/common/PlatformUtils.scala`)

`PlatformUtils` (`PlatformUtils.scala:20`) exposes `isMac` / `isWindows` / `isLinux` for OS-conditional behavior (e.g.
directory-slash handling in `parseUrlOrPath`, config paths in `appConfig`). It is currently **hardcoded to macOS**
(`isMac == true`, the others `false`) — see notes below.

## Dependencies

Verified against `build.sbt` (the `common` project block at `build.sbt:122`).

**Upstream (what `common` depends on):**

- No other Microtonalist modules — `common` has no `dependsOn` except `commonTestUtils % Test` (shared test helpers,
  test-only).
- External libraries: **Guava** (`guava`) plus the project-wide `commonDependencies` (Logback, scala-logging, and
  ScalaTest/ScalaMock in `Test` scope).

**Downstream (what depends on `common`):**

- Direct `dependsOn(common)`: `appConfig`, `tuner`, `format`, `scMidi`, and `app`.
- Transitively, `composition` (via `tuner`) and `ui` (via `tuner`) also reach `common`. (`cli` and `experiments` do not
  depend on it.)

## Notes / subject to change

- `PlatformUtils` is a stub hardcoded to macOS. A `// TODO Add support for Windows and maybe GNU/Linux` marks the intent
  to detect the OS at runtime; until then non-macOS branches of callers are effectively dead. (Note: per project
  convention TODOs carry an issue number; this one does not yet.)

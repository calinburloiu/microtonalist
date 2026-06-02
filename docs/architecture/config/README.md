# `appConfig` module architecture

## Responsibility

The `appConfig` module (SBT project `appConfig`, directory `config/`) owns Microtonalist's **application
configuration**: loading, exposing, mutating, and persisting the user's settings. Settings are stored in
[HOCON](https://github.com/lightbend/config) format and, on macOS, live at `~/.microtonalist/microtonalist.conf`.

The module's job is to:

- Parse a HOCON config file into typed, immutable Scala config values (or fall back to defaults when no file is given).
- Expose those values to the rest of the application as domain-friendly case classes (e.g. `CoreConfig`).
- Accept in-memory updates, mark the config dirty, and periodically (and on exit) write it back to disk.

It deliberately knows nothing about MIDI, tunings, compositions, or the GUI — it only deals with reading/writing
configuration. The single consumer of note is `MicrotonalistApp` (`app` module), which resolves the config path,
constructs a `MainConfigManager`, and reads `coreConfig.libraryBaseUrl` to wire up the `FormatModule`
(`app/src/main/scala/org/calinburloiu/music/microtonalist/MicrotonalistApp.scala:77`).

Package: `org.calinburloiu.music.microtonalist.config`.

### Underlying library

Configuration is parsed and rendered with the **Typesafe/Lightbend Config** library (`com.typesafe.config`, the HOCON
implementation), aliased throughout the module as `HoconConfig`. Typed reading uses **Ficus** (`com.iheart %% ficus`),
a thin Scala wrapper over Typesafe Config that adds `getAs[T]` and `ValueReader[T]` typeclasses. Ficus is the only
extra `libraryDependency` declared for this module in `build.sbt`.

## Key types

### `MainConfigManager` (`config/src/main/scala/org/calinburloiu/music/microtonalist/config/ConfigManagement.scala:30`)

The central, mutable, thread-safe holder of the whole HOCON document. It is `AutoCloseable` and mixes in `Locking`
(from `common`) to guard its mutable `_mainHoconConfig` with a `ReentrantReadWriteLock`.

- Construction is private; use the companion `apply` overloads
  (`ConfigManagement.scala:119`): `MainConfigManager(configFile: Path)` loads from a file with an empty fallback, while
  `MainConfigManager(mainHoconConfig: HoconConfig)` runs file-less from an in-memory document (handy for tests).
- `load()` (`:53`) parses and `resolve()`s the file via `ConfigFactory.parseFile`, or logs a warning and uses the
  fallback when no file path was supplied.
- `hoconConfig(configPath)` (`:85`) returns the sub-document at a path; `notifyConfigChanged(configPath, hoconConfig)`
  (`:87`) splices an updated sub-document back in and marks the config dirty.
- Persistence: `save()` (`:64`) writes the rendered document (only when `isDirty`) using `render()` (`:83`) with
  `ConfigRenderOptions` that drop origin comments and emit HOCON rather than JSON. A `ScheduledExecutorService` calls
  `save()` every `metaConfig.saveIntervalMillis` (when > 0), and `close()` (`:91`) does a final save when
  `metaConfig.saveOnExit` and shuts the scheduler down.
- It eagerly creates a `CoreConfigManager` and exposes shortcuts `coreConfig` and `metaConfig`.
- `MainConfigManager.defaultConfigFile` (`:114`) resolves `~/.microtonalist/microtonalist.conf` on macOS and throws on
  other platforms (see [Future / planned changes](#future--planned-changes)).

### `SubConfigManager[C <: Configured]` and `Configured` (`ConfigManagement.scala:126`)

`Configured` (`:126`) is a marker trait for a typed config section. `SubConfigManager` (`:128`) is the abstract base
for managing one HOCON sub-tree rooted at `configPath`:

- `config: C` deserializes the current sub-document; `notifyConfigChanged(configured: C)` serializes a value and pushes
  it back into the owning `MainConfigManager`.
- Subclasses implement the pure `serialize(C): HoconConfig` and `deserialize(HoconConfig): C` pair.

This pattern keeps each settings section (its typed shape plus its HOCON (de)serialization) self-contained, so new
config sections are added by writing a new `Configured` case class plus a `SubConfigManager` for it.

### `CoreConfig`, `MetaConfig`, `CoreConfigManager` (`config/.../CoreConfig.scala`)

The one concrete config section, rooted at HOCON path `core` (`CoreConfig.scala:74`).

- `CoreConfig` (`:27`) — `case class` extending `Configured`, holding `libraryBaseUrl: URI` (where the scale library
  lives; defaults via `CoreConfig.defaultLibraryBaseUrl` to `~/Music/microtonalist/lib/` on macOS) and a nested
  `metaConfig`.
- `MetaConfig` (`:39`) — `case class` with `saveIntervalMillis: Int = 5000` and `saveOnExit: Boolean = true`, the
  knobs that drive `MainConfigManager`'s autosave behavior.
- `CoreConfigManager` (`:46`) — `SubConfigManager[CoreConfig]`. `deserialize` (`:64`) reads `libraryBaseUrl` via Ficus'
  `getAs[String]`, parses it with `common`'s `parseUrlOrPath`, and throws a `ConfigPropertyException` on a bad value;
  it falls back to defaults for any missing key. A private Ficus `ValueReader[MetaConfig]` (`:76`) handles the nested
  section. `serialize` (`:52`) rebuilds the sub-document with the `withAnyRefValue` helper.

### `ConfigSerDe` (`config/src/main/scala/org/calinburloiu/music/microtonalist/config/ConfigSerDe.scala:23`)

Serialization helpers bridging plain Scala values and Typesafe Config values:

- `HoconConfigExtension.withAnyRefValue(path, value)` (`:27`) — an implicit extension that sets a value, recursing into
  `Seq`/`Map`, and skips the write when a scalar is unchanged (avoiding needless dirtying).
- `createHoconValue(value)` (`:39`) — recursively converts Scala `Seq`/`Map`/scalars into `ConfigValue`s via
  `ConfigValueFactory`.

### Exceptions

- `ConfigException` (`config/.../ConfigException.scala:22`) — base `RuntimeException` for HOCON configuration errors.
- `ConfigPropertyException` (`config/.../ConfigPropertyException.scala:26`) — subclass for a single bad property,
  carrying the HOCON `propertyPath` and a human-readable requirement message.

## Dependencies

Per `build.sbt`, `appConfig` depends only on the `common` module (for `PlatformUtils`, `parseUrlOrPath`, and the
`Locking` concurrency trait) plus the external `ficus` library (over Typesafe Config). It has no other
application-module dependencies, keeping it near the bottom of the layered architecture.

It is depended on by the `app` module, which constructs and queries the `MainConfigManager` during startup.

```
app
└── appConfig (config/)
    └── common
```

## Future / planned changes

- **macOS-only platform support.** `defaultConfigFile` and `CoreConfig.defaultLibraryBaseUrl` both throw a
  `RuntimeException("Only Mac platform is currently supported")` on non-Mac platforms, and `PlatformUtils.isMac` is
  hardcoded to `true`. Cross-platform config-path resolution is an open area.
- **Single config section.** Only the `core` section exists today. The `SubConfigManager` / `Configured` machinery is
  built to grow: additional settings sections are expected to be added as new `Configured` case classes with their own
  `SubConfigManager`s.

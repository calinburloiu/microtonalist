# `config` module architecture

## Responsibility

The `config` module (SBT project `config`, directory `config/`, `build.sbt` `lazy val` `appConfig`) owns Microtonalist's **application
configuration**: loading, exposing, mutating, and persisting the user's settings. Settings are stored in
[HOCON](https://github.com/lightbend/config) and, on macOS, live at `~/.microtonalist/microtonalist.conf`. The module:

- parses a HOCON file into typed, immutable Scala config values (or falls back to defaults when no file is given);
- exposes those values as domain-friendly case classes (e.g. `CoreConfig`);
- accepts in-memory updates, marks the config dirty, and periodically (and on exit) writes it back to disk.

It knows nothing about MIDI, tunings, compositions, or the GUI. Its one consumer of note is `MicrotonalistApp`, which
resolves the config path, constructs a `MainConfigManager`, and reads `coreConfig.libraryBaseUrl` to wire up
`FormatModule`.

Package: `org.calinburloiu.music.microtonalist.config`. Configuration is parsed and rendered with the
**Typesafe/Lightbend Config** library (aliased `HoconConfig` throughout); typed reading uses **Ficus**, a thin Scala
wrapper that adds `getAs[T]`/`ValueReader[T]`. Ficus is the only extra library dependency declared for this module.

## Key types

**`MainConfigManager`** is the central, mutable, thread-safe holder of the whole HOCON document. It is `AutoCloseable`
and mixes in `Locking` (from `common`) to guard the document with a read/write lock. Construction is private — the
companion `apply` overloads load from a `Path` (with an empty fallback) or run file-less from an in-memory document
(handy for tests). It parses and `resolve()`s the file, exposes the **sub-config** at a path — the
HOCON sub-tree of the full document rooted at that path, itself a `HoconConfig` — and splices updated sub-configs back in
(marking the config dirty), and persists via `save()` — which renders HOCON (not JSON, no origin
comments) only when dirty. A scheduler calls `save()` on the configured interval and `close()` does a final save on
exit. `MainConfigManager.defaultConfigFile` resolves `~/.microtonalist/microtonalist.conf` on macOS and throws on other
platforms (see [Future / planned changes](#future--planned-changes)).

**`SubConfigManager[C <: Configured]` / `Configured`** are the extension pattern for typed config sections. `Configured`
marks a typed section; `SubConfigManager` manages one such sub-config rooted at a `configPath`, exposing a typed `config`
getter and a `notifyConfigChanged` that pushes a value back to the owning `MainConfigManager`. Subclasses implement just
the pure `serialize`/`deserialize` pair. This keeps each settings section self-contained, so a new section is added by
writing a new `Configured` case class plus a `SubConfigManager` for it.

**`CoreConfig` / `MetaConfig` / `CoreConfigManager`** are the one concrete section today, rooted at HOCON path `core`.
`CoreConfig` holds `libraryBaseUrl` (the scale/track library root, default `~/Music/microtonalist/lib/` on macOS) and a
nested `MetaConfig` (`saveIntervalMillis`, `saveOnExit`) that drives `MainConfigManager`'s autosave. `CoreConfigManager`
reads `libraryBaseUrl` via Ficus and `common`'s `parseUrlOrPath`, throwing a `ConfigPropertyException` on a bad value
and falling back to defaults for missing keys.

Supporting pieces: `ConfigSerDe` provides the serialization helpers bridging plain Scala values and Typesafe Config
values (notably a `withAnyRefValue` extension that recurses into `Seq`/`Map` and skips unchanged scalars), and the
exception hierarchy is `ConfigException` (base) with `ConfigPropertyException` for a single bad property (carrying its
HOCON `propertyPath`).

## Dependencies

`config` depends only on `common` (for `PlatformUtils`, `parseUrlOrPath`, and the `Locking` trait) plus the external
`ficus` library, keeping it near the bottom of the layered architecture. It is depended on by `app`, which constructs
and queries the `MainConfigManager` during startup.

```
app
└── config
    └── common
```

## Future / planned changes

- **macOS-only platform support.** `defaultConfigFile` and `CoreConfig.defaultLibraryBaseUrl` both throw on non-Mac
  platforms, and `PlatformUtils.isMac` is hardcoded to `true`. Cross-platform config-path resolution is an open area.
- **Single config section.** Only the `core` section exists today; the `SubConfigManager`/`Configured` machinery is
  built to grow with additional `Configured` case classes.

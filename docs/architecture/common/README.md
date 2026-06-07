# `common` module architecture

## Responsibility

The `common` module is a small collection of cross-cutting utilities and abstractions shared by the rest of
Microtonalist. It is not a domain module — it holds no musical concepts. Instead it provides the few building blocks
that many higher modules need but that belong to no single one: the `Plugin` extension point, a lock-scoping helper, a
session-lifecycle trait, URL/path parsing, and platform detection.

It sits near the bottom of the dependency graph and is **widely depended upon** (`tuner`, `format`, `sc-midi`,
`config`, `app`), so it deliberately keeps almost no dependencies of its own. Each utility here is independent of the
others.

Package: `org.calinburloiu.music.microtonalist.common` (sub-package `.concurrency`).

## Key types

**`Plugin`** is the base trait for all of Microtonalist's pluggable, user-selectable components. It defines a two-level
naming contract used both at runtime and for JSON (de)serialization: `familyName` (the family/context, e.g.
`tuningMapper`) and `typeName` (the chosen type within it, e.g. `auto`). Within one family the user can swap between
types. The families across the project live in `composition` (`TuningMapper`, `TuningReducer`, `TuningReference`,
`SoftChromaticGenusMapping`) and `tuner` (`Tuner`, `TuningChanger`, and the track I/O specs `TrackInputSpec` /
`TrackOutputSpec`). The serialization side lives in `format` (`JsonPluginFormat[P]` keys off these
names); the `Plugin` trait itself carries only the two `val`s and no serialization logic.

**`Locking`** is a mix-in trait providing `@inline` helpers (`withLock` / `withReadLock` / `withWriteLock`) that run a
block while holding an implicitly-passed lock and always release it in a `finally`, so call sites read like
`withReadLock { _x }`. Used for thread-safe getters/setters in `config`, `format` (`DeferrableRead`), and `sc-midi`
(`MidiDeviceHandle`, `MidiProcessor`).

**`OpenableSession`** is a small lifecycle trait (extending `java.io.Closeable`) for a resource opened asynchronously
against a `URI`: `open(uri): Future[Unit]`, `close()`, `isOpened`, `uri`. Implemented by `TrackSession` in `tuner`.

**Package-object functions** — chiefly `parseUrlOrPath(urlString): Option[URI]`, which parses a string as either an
absolute URL or a local file path, enforcing the project convention that a directory URI ends in `/`. Used by `app` and
`config`.

**`PlatformUtils`** exposes `isMac` / `isWindows` / `isLinux` for OS-conditional behavior. It is currently **hardcoded
to macOS** — see notes below.

## Dependencies

`common` has no application `dependsOn` (only `commonTestUtils % Test`). Externally it uses Guava plus the inherited
common logging/test stack. It is depended on directly by `config`, `tuner`, `format`, `sc-midi`, and `app`, and
transitively by `composition` and `ui` (via `tuner`); `cli` and `experiments` do not depend on it.

## Notes / subject to change

- `PlatformUtils` is a stub hardcoded to macOS, so non-macOS branches of its callers are effectively dead until OS
  detection is implemented. (Its `// TODO` does not yet carry an issue number, contrary to project convention.)

# `format` module architecture

## Responsibility

The `format` module is responsible for **all file I/O of persistable domain objects** in Microtonalist. It reads (and,
where implemented, writes) compositions, scales, and tracks from local files, HTTP(S) endpoints, and the user's
Microtonalist library, translating between on-disk/network byte streams and the in-memory domain model.

Everything lives in package `org.calinburloiu.music.microtonalist.format`. The encoding is almost always JSON via [Play
JSON](https://github.com/playframework/play-json); the one non-JSON encoder is the Huygens-Fokker `.scl` reader. The
module is currently mostly **read-oriented** — read paths are fully implemented while most write paths are stubbed (see
[Future / planned changes](#future--planned-changes)).

## The `*Format` / `*Repo` pattern

Each kind of persistable domain object is handled by a pair of collaborating abstractions that separate _how_ a value is
encoded from _where_ it is stored:

- A **`*Format`** does pure serialization/deserialization between a byte stream and a domain object. It knows the
  encoding but nothing about the data source.
- A **`*Repo`** follows the repository pattern: it retrieves and persists a domain object identified by a `URI`,
  abstracting the underlying source (local file, HTTP, library) and delegating the actual encoding to the matching
  `*Format`.

This keeps encoding logic source-agnostic and storage logic encoding-agnostic: a new data source needs only a new
`*Repo`, and a new encoding needs only a new `*Format`. Both traits expose a synchronous variant and a `…Async` variant
returning a `Future`; the async form is primary (scales are loaded concurrently — see [Deferred
reads](#deferred-reads)), and the synchronous methods are typically thin `Await.result` wrappers over it.

Three families follow the pattern, one per persistable object:

| Extension         | Domain object  | Format                          | Encoding |
| ----------------- | -------------- | ------------------------------- | -------- |
| `.mtlist`         | `Composition`  | `JsonCompositionFormat`         | Microtonalist JSON |
| `.jscl` / `.json` | `Scale`        | `JsonScaleFormat`               | Microtonalist JSON scale |
| `.scl`            | `Scale`        | `HuygensFokkerScalaScaleFormat` | Huygens-Fokker [Scala](https://www.huygens-fokker.org/scala/) text (read-only) |
| `.mtlist.tracks`  | `TrackSpecs`   | `JsonTrackFormat`               | Microtonalist JSON tracks file |

Each has a `Default*Repo` that dispatches by URI scheme to source-specific `File*Repo` / `Http*Repo` / `Library*Repo`
variants. Two wrinkles are worth knowing:

- `JsonCompositionFormat` parses an intermediate representation (`CompositionRepr` and friends) that mirrors the JSON
  shape, resolves deferred scales, then maps it to the domain `Composition`.
- The scale read path runs results through `ScaleContextConverter`, which applies a `ScaleFormatContext` (renaming
  and/or converting intervals to the composition's intonation standard) and publishes a `ScaleLossyConversionEvent` via
  `Businessync` when the conversion loses precision. `ScaleFormatRegistry` picks the right `ScaleFormat` for an external
  scale by media type, falling back to file extension.
- `TrackRepo` is the one repository trait that **lives in the `tuner` module** (because `Track`/`TrackSpecs` are tuner
  domain types); only its formats and concrete repos live here.

## URI-scheme dispatch

Every `Default*Repo` resolves a `URI` to a concrete source-specific repo via `DefaultRepoSelector`, keyed purely on the
scheme:

| URI scheme                | Repo selected  | Notes |
| ------------------------- | -------------- | ----- |
| none (relative) or `file` | `File*Repo`    | Repos have no base URI, so relative URIs are treated as files. |
| `http`, `https`           | `Http*Repo`    | Loaded over a shared `java.net.http.HttpClient`. |
| `microtonalist`           | `Library*Repo` | Resolved against the user-configured library base URL. |
| anything else             | throws         | `IllegalArgumentException` by default. |

Callers should resolve relative URIs against a base URI _before_ calling a repo, so a relative reference inside, say, an
HTTP-loaded composition still resolves over HTTP. Base-URI resolution (including the optional `baseUrl` override
property in a composition file) and `microtonalist:` → library-path mapping are handled by package-object helpers
(`resolveBaseUriWithOverride`, `resolveLibraryUrl`).

## `$ref` preprocessing

Before any JSON is validated into domain types, `JsonPreprocessor` walks the parsed tree and replaces every object
carrying a `$ref` string with the JSON loaded from that URI (properties present alongside the `$ref` override the loaded
ones). References resolve relative to the current base URI, are loaded by an ordered list of ref loaders (file then
HTTP), and are guarded against cycles and excessive nesting. A no-op `NoJsonPreprocessor` exists for contexts that
should not resolve references.

## Plugin (de)serialization

`TuningMapper`, `TuningReducer`, `TuningReference`, `Tuner`, `TuningChanger`, the track I/O specs, and the
soft-chromatic-genus mapping are all **plugin families** — each extends `Plugin` from `common` with a `familyName` and
`typeName`. `IntonationStandard` is not a `Plugin` (it lives in `intonation`, which has no `common` dependency) but is
handled by the same machinery, its `JsonPluginFormat` supplying the `familyName` and keying concrete standards by
`typeName`. Each family has a matching `JsonPluginFormat[P]` that handles the whole family:

- The concrete _type_ is identified in JSON by a `"type"` property; a family may declare a `defaultTypeName`, letting
  users omit `"type"`, and represent a no-settings plugin as a bare type-name string.
- Each type is described by a `TypeSpec` carrying the `typeName`, the Java `Class` (to pick the writer), and either a
  Play `Format` for the type's _settings_ or a singleton value for settings-less types.
- A composition file may carry a global `settings` block; on read, effective settings are merged as `defaultSettings ++
  globalSettings ++ localSettings` (local wins), so a user can set family/type defaults once per file and override them
  per usage.

This is why each plugin lives in its domain module but its wire format lives here: `format` owns the JSON contract and
the settings-merging policy, keeping serialization concerns out of the domain modules.

## Deferred reads

Scales referenced from a composition are not necessarily loaded eagerly. `DeferrableRead[V, P]` models a value that is
either `AlreadyRead` (inlined in the JSON, immediately available) or `DeferredRead` (a placeholder — typically a `URI` —
resolved later by an async `load(loader)` call; it is thread-safe, caches its `Future`, and tracks a load status).
`JsonCompositionFormat.readAsync` parses the composition, loads all deferred scales concurrently (with a per-composition
cache so each distinct scale URI is fetched once), and only then maps to the domain `Composition`. This is the main
reason the format/repo APIs are async-first.

## `FormatModule` (wiring)

`FormatModule` is the module's composition root. Construct it with a `Businessync`, the library base URL, and an
optional synchronous-await timeout; it then exposes lazily-initialised, fully-wired repos and formats. Consumers should
depend on `FormatModule` and read its `defaultCompositionRepo` / `defaultScaleRepo` / `defaultTrackRepo` rather than
constructing individual components. `MicrotonalistApp` does exactly this at startup.

Smaller building-block JSON formats (`JsonIntervalFormat`, `KeyboardMappingFormat`, `MidiNoteFormat`,
`PitchClassFormat`, …) and the cross-cutting `RepoSelector` / `UriScheme` helpers round out the module but are reused
internals rather than its public surface.

## Dependencies

**Depends on** `composition` (the `Composition` model and its plugins this module (de)serializes), `tuner`
(`TrackSpecs`, the `TrackRepo` trait, and the tuner/tuning-changer plugin families), `common` (the `Plugin` type,
`Locking`, and shared utilities), and — transitively — `intonation` (`Scale`/`Interval`/`IntonationStandard`) and
`businessync` (`ScaleContextConverter` publishes `ScaleLossyConversionEvent`). Third-party: Play JSON, Guava, and
`java.net.http.HttpClient`.

**Depended on by** `app` only — `MicrotonalistApp` constructs a `FormatModule` and uses its default repos to load the
composition and tracks at startup. `format` sits in the I/O layer between the domain modules and the application entry
point.

## Future / planned changes

Signalled directly by the code: **writing is largely unimplemented.** Many `write`/`writeAsync` methods on the
composition/scale/track repos and on `HuygensFokkerScalaScaleFormat` are `???` stubs. The read paths and the JSON
_writers_ exposed by `JsonScaleFormat`/`JsonTrackFormat` are implemented; the repo-level persistence plumbing is not yet
wired up. Treat write APIs as unstable.

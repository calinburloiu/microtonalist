# `format` module architecture

## Responsibility

The `format` module is responsible for **all file I/O of persistable domain objects** in Microtonalist. It reads (and,
where implemented, writes) compositions, scales, and tracks files from local files, HTTP(S) endpoints, and the user's
Microtonalist scale/track library, translating between on-disk byte streams and the in-memory domain model.

Everything lives in the package `org.calinburloiu.music.microtonalist.format`. The encoding is almost always JSON via
[Play JSON](https://github.com/playframework/play-json); the one non-JSON encoder is the Huygens-Fokker `.scl` reader.

The module is currently **read-oriented**: read paths are fully implemented, while most write paths are stubbed with
`???` (see [Future / planned changes](#future--planned-changes)).

## The `*Format` / `*Repo` pattern

Each kind of persistable domain object is handled by a pair of collaborating abstractions that separate _how_ a value is
encoded from _where_ it is stored:

- A **`*Format`** does pure serialization/deserialization between a byte stream (`InputStream`/`OutputStream`) and a
  domain object. It knows the encoding but nothing about the data source.
- A **`*Repo`** follows the repository pattern: it retrieves and persists a domain object identified by a `URI`,
  abstracting the underlying data source (local file, HTTP, library). Each repo exposes synchronous and asynchronous
  `read`/`write` methods and delegates the actual encoding/decoding to the matching `*Format`.

This split keeps encoding logic source-agnostic and storage logic encoding-agnostic, so a new data source only requires
a new `*Repo` (not a new `*Format`), and a new on-disk encoding only requires a new `*Format`.

### Why both sync and async

Repo and format traits each expose a synchronous variant and a `…Async` variant returning a `Future`. The async form
is primary: scales referenced from a composition are loaded concurrently (see [Deferred reads](#deferred-reads)), the
synchronous methods are typically thin `Await.result(…, synchronousAwaitTimeout)` wrappers over the async ones
(e.g. `JsonCompositionFormat.read` at
`format/src/main/scala/org/calinburloiu/music/microtonalist/format/JsonCompositionFormat.scala:47`). The timeout
defaults to one minute and is threaded through `FormatModule`.

## Key types

### Composition (`.mtlist`)

- `CompositionFormat` — trait; (de)serializes a `Composition` to/from a stream.
  `format/.../CompositionFormat.scala:28`
- `JsonCompositionFormat` — the JSON implementation; parses an intermediate `CompositionRepr`, resolves deferred
  scales, then maps the representation to the domain `Composition`. `format/.../JsonCompositionFormat.scala:39`
- `CompositionRepr` (+ `TuningSpecRepr`, `FillSpecRepr`, `LocalFillSpecRepr`, `CompositionFormatContext`) — DTO-like
  representation types that mirror the JSON shape before conversion to domain objects.
  `format/.../CompositionRepr.scala`
- `CompositionRepo` — trait; `read`/`readAsync`/`write`/`writeAsync` by `URI`. `format/.../CompositionRepo.scala:29`
- `DefaultCompositionRepo` — dispatches by URI scheme to `FileCompositionRepo` / `HttpCompositionRepo`.
  `format/.../DefaultCompositionRepo.scala`
- `FileCompositionRepo`, `HttpCompositionRepo` — source-specific repos delegating to the `CompositionFormat`.

### Scale (`.jscl`, `.json`, `.scl`)

- `ScaleFormat` — trait; (de)serializes a `Scale[Interval]`, taking an optional `ScaleFormatContext` (name +
  intonation standard) that may supply values omitted from the file. `format/.../ScaleFormat.scala:27`
- `JsonScaleFormat` — Microtonalist's own JSON scale format (`.jscl` / `.json`). `format/.../JsonScaleFormat.scala:32`
- `HuygensFokkerScalaScaleFormat` — reader for [Scala application](https://www.huygens-fokker.org/scala/) `.scl` files
  (read-only; `write` is `???`). `format/.../HuygensFokkerScalaScaleFormat.scala:33`
- `ScaleFormatRegistry` — selects the right `ScaleFormat` by media type (MIME) or, failing that, by the URI's file
  extension. `format/.../ScaleFormatRegistry.scala:30`
- `ScaleFormatMetadata` — declares a format's name, file extensions, and media types for the registry.
  `format/.../ScaleFormatMetadata.scala:28`
- `ScaleContextConverter` — applies the `ScaleFormatContext` after a scale is read: renames it and/or converts its
  intervals to the composition's intonation standard, publishing a `ScaleLossyConversionEvent` via `Businessync` when
  the conversion loses precision. `format/.../ScaleContextConverter.scala:31`
- `ScaleRepo` — trait; `read`/`write` (sync + async) by `URI`, with a `ScaleFormatContext` and optional media type.
  `format/.../ScaleRepo.scala:29`
- `DefaultScaleRepo` — dispatches by URI scheme and runs the read result through `ScaleContextConverter`.
  `format/.../DefaultScaleRepo.scala:51`
- `FileScaleRepo`, `HttpScaleRepo`, `LibraryScaleRepo` — source-specific repos; `LibraryScaleRepo` resolves
  `microtonalist:` URIs against the configured library base URL, then reuses the file/HTTP repos.
  `format/.../LibraryScaleRepo.scala:40`

### Tracks (`.mtlist.tracks`)

- `TrackFormat` — trait; (de)serializes `TrackSpecs` (a collection of `TrackSpec`s). `format/.../TrackFormat.scala:32`
- `JsonTrackFormat` — the JSON implementation. `format/.../JsonTrackFormat.scala:34`
- `TrackRepo` — the repository trait, which **lives in the `tuner` module**
  (`tuner/.../tuner/TrackRepo.scala`) because `Track`/`TrackSpecs` are tuner domain types; its formats and concrete
  repos live here in `format`.
- `DefaultTrackRepo`, `FileTrackRepo`, `HttpTrackRepo`, `LibraryTrackRepo` — scheme dispatch + source-specific repos,
  mirroring the scale repos.

### Cross-cutting infrastructure

- `RepoSelector[R]` / `DefaultRepoSelector[R]` — generic URI-scheme-to-repo selector reused by every `Default*Repo`
  and by `LibraryScaleRepo`. `format/.../RepoSelector.scala:26`, `format/.../DefaultRepoSelector.scala:31`
- `UriScheme` — the recognised scheme constants: `file`, `http`, `https`, `microtonalist`.
  `format/.../UriScheme.scala:22`
- `JsonPreprocessor` (+ `JsonPreprocessorFileRefLoader`, `JsonPreprocessorHttpRefLoader`,
  `JsonPreprocessorRefLoader`) — resolves `$ref` URIs before parsing. `format/.../JsonPreprocessor.scala:34`
- `JsonPluginFormat[P]` (+ `JsonPluginFormat.TypeSpec`) — generic Play-JSON (de)serialization of plugin _families_;
  see [Plugin (de)serialization](#plugin-deserialization). `format/.../JsonPluginFormat.scala:36`
- `DeferrableRead[V, P]` (`AlreadyRead` / `DeferredRead`) — lazy, async-loadable JSON values; see
  [Deferred reads](#deferred-reads). `format/.../DeferrableRead.scala:75`
- `FormatModule` — the composition root that lazily wires every format and repo; inject this rather than constructing
  components by hand. `format/.../FormatModule.scala:26`
- Smaller building-block JSON formats reused by the above: `JsonIntervalFormat`, `JsonIntonationStandardPluginFormat`,
  `KeyboardMappingFormat`, `MidiNoteFormat`, `PitchClassFormat`, `JsonConstraints`, `JsonCommonMidiFormat`, and the
  `package.scala` helpers (`uint7Format`, `filePathOf`, `resolveBaseUriWithOverride`, `resolveLibraryUrl`).

## URI-scheme dispatch

Every `Default*Repo` resolves a `URI` to a concrete source-specific repo via `DefaultRepoSelector`, keyed purely on the
URI scheme (`format/.../DefaultRepoSelector.scala:37`):

| URI scheme            | Repo selected        | Notes                                                          |
| --------------------- | -------------------- | -------------------------------------------------------------- |
| none (relative) or `file` | `File*Repo`      | Repos have no base URI, so relative URIs are treated as files. |
| `http`, `https`       | `Http*Repo`          | Loaded over HTTP via a shared `java.net.http.HttpClient`.      |
| `microtonalist`       | `Library*Repo`       | Resolved against the user-configured library base URL.         |
| anything else         | none → throws        | `IllegalArgumentException` by default.                         |

Callers are advised to resolve relative URIs against a base URI _before_ calling a repo, so that a relative reference
inside, say, an HTTP-loaded composition still resolves over HTTP. Base-URI resolution (including the optional
`baseUrl` override property in a composition file) is handled by `resolveBaseUriWithOverride`
(`format/.../package.scala:55`).

`microtonalist:///<path>` URIs are mapped to the configured library base URL by `resolveLibraryUrl`
(`format/.../package.scala:71`); e.g. with base `file:///Users/john/Music/lib/`, `microtonalist:///scales/lydian.scl`
resolves to `/Users/john/Music/lib/scales/lydian.scl`. `LibraryScaleRepo`/`LibraryTrackRepo` then delegate the resolved
URL to their file/HTTP repos.

## File formats

| Extension         | Domain object  | Format type                    | Encoding                                              |
| ----------------- | -------------- | ------------------------------ | ----------------------------------------------------- |
| `.mtlist`         | `Composition`  | `JsonCompositionFormat`        | Microtonalist JSON.                                   |
| `.jscl` / `.json` | `Scale`        | `JsonScaleFormat`              | Microtonalist JSON scale (`application/vnd.microtonalist.scale+json`). |
| `.scl`            | `Scale`        | `HuygensFokkerScalaScaleFormat`| Huygens-Fokker Scala text format (ISO-8859-1).        |
| `.mtlist.tracks`  | `TrackSpecs`   | `JsonTrackFormat`              | Microtonalist JSON tracks file.                       |

A scale referenced from a composition can be **inlined** in the `.mtlist` file or **referenced by URI** to an external
`.jscl` / `.json` / `.scl` file. The right `ScaleFormat` for an external scale is chosen by `ScaleFormatRegistry`,
preferring an explicit media type and falling back to the file extension (`format/.../ScaleFormatRegistry.scala:49`).

## `JsonPreprocessor` and `$ref`

Before any JSON is validated into domain types, `JsonPreprocessor.preprocess` walks the parsed tree and replaces every
object containing a `$ref` string property with the JSON loaded from that URI; properties already present alongside the
`$ref` override the loaded ones. References are resolved relative to the current base URI, loaded by an ordered list of
`JsonPreprocessorRefLoader`s (file then HTTP), and guarded against cycles and excessive nesting (max depth 100). The
no-op `NoJsonPreprocessor` is available for contexts that should not resolve references.
`format/.../JsonPreprocessor.scala`

## Plugin (de)serialization

`TuningMapper`, `TuningReducer`, `TuningReference`, `Tuner`, `TuningChanger`, `IntonationStandard`, the track I/O specs,
and the soft-chromatic-genus mapping are all **plugin families**. Each extends `Plugin` (from `common`) with a
`familyName` and a `typeName`, and each has a matching `JsonPluginFormat[P]` (e.g. `JsonTuningMapperPluginFormat`,
`JsonTuningReducerPluginFormat`, `JsonTunerPluginFormat`, `JsonTuningChangerPluginFormat`,
`JsonTuningReferencePluginFormat`, `JsonIntonationStandardPluginFormat`, `JsonTrackIOPluginFormat`).

How `JsonPluginFormat` works (`format/.../JsonPluginFormat.scala`):

- The plugin's concrete _type_ is identified in JSON by a `"type"` property. A family may declare a `defaultTypeName`,
  letting users omit `"type"` (and even represent a no-settings plugin as a bare type-name string).
- Each type is described by a `TypeSpec`, which carries the `typeName`, the Java `Class` (used to pick the writer when
  serializing), and either a Play `Format` for the type's _settings_ or a singleton plugin value for types that have no
  settings.
- A composition file may carry a global `settings` block. On read, effective settings are merged as
  `defaultSettings ++ globalSettings ++ localSettings` (local wins), where the global slice is looked up by
  `settings → familyName → typeName`. This lets a user set family/type defaults once per file and override them
  per usage.

This is why each plugin lives in its domain module but its wire format lives here: `format` owns the JSON contract and
the settings-merging policy, keeping serialization concerns out of the domain modules.

## Deferred reads

Scales referenced from a composition are not necessarily loaded eagerly. `DeferrableRead[V, P]`
(`format/.../DeferrableRead.scala`) models a value that is either:

- `AlreadyRead` — inlined in the JSON and immediately available; or
- `DeferredRead` — represented by a _placeholder_ (typically a `URI`) resolved later by an async `load(loader)` call.
  `DeferredRead` is thread-safe (read/write-locked), caches its `Future`, and tracks a load `status`.

`JsonCompositionFormat.readAsync` parses the composition into a `CompositionRepr`, calls
`CompositionRepr.loadDeferredData(scaleRepo)` to load all deferred scales concurrently (with a per-composition cache so
each distinct scale URI is fetched once), and only then maps the representation to the domain `Composition`. This is the
main reason the format/repo APIs are async-first.

## `FormatModule` (wiring)

`FormatModule` is the module's composition root. Construct it with a `Businessync`, the library base URL, and an
optional synchronous-await timeout; it then exposes lazily-initialised, fully-wired repos and formats. Consumers should
depend on `FormatModule` and read its `defaultCompositionRepo` / `defaultScaleRepo` / `defaultTrackRepo` rather than
constructing individual components (`format/.../FormatModule.scala:26`). `MicrotonalistApp` does exactly this:

```scala
val formatModule = new FormatModule(businessync, mainConfigManager.coreConfig.libraryBaseUrl)
val composition  = formatModule.defaultCompositionRepo.read(inputUrl)
val tunerModule  = new TunerModule(businessync, formatModule.defaultTrackRepo)
```

## Dependencies

Per `build.sbt`, the `format` module declares:

```scala
lazy val format = (project in file("format"))
  .dependsOn(
    common,
    commonTestUtils % Test,
    composition,
    tuner,
  )
  // …
  .settings(libraryDependencies ++= Seq(playJson))
```

**Depends on:**

- `composition` — the `Composition` domain model and its plugins (`TuningMapper`, `TuningReducer`, `TuningReference`,
  `TuningSpec`, fill specs) that this module (de)serializes.
- `tuner` — `TrackSpecs`/`TrackSpec`, the `TrackRepo` trait, and the tuner/tuning-changer plugin families.
- `intonation` (transitively, via `composition`) — `Scale`, `Interval`, `IntonationStandard`.
- `common` — the `Plugin` base type, concurrency helpers (`Locking`), and shared utilities.
- `businessync` (transitively) — `ScaleContextConverter` publishes `ScaleLossyConversionEvent`s.
- Third-party: Play JSON (encoding), Google Guava (`MediaType`, `Files`), `java.net.http.HttpClient` (HTTP loading).

**Depended on by:**

- `app` — `MicrotonalistApp` constructs a `FormatModule` and uses its default repos to load the composition and tracks
  at startup.

No module other than `app` depends on `format`; `format` sits in the I/O layer between the domain modules and the
application entry point.

## Future / planned changes

These are signalled directly by the code, not by a specific roadmap issue:

- **Writing is largely unimplemented.** Many `write`/`writeAsync` methods are `???` stubs
  (`JsonCompositionFormat`, `FileCompositionRepo`, `HttpCompositionRepo`, `FileScaleRepo`, `HttpScaleRepo`,
  `HuygensFokkerScalaScaleFormat`, `FileTrackRepo`, `HttpTrackRepo`). The read paths and the JSON _writers_ exposed by
  `JsonScaleFormat`/`JsonTrackFormat` (e.g. `writeAsJsValue`) are implemented; the repo-level persistence plumbing is
  not yet wired up. Treat write APIs as unstable.

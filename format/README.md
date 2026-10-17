# `format` module group

Persistence of the domain objects, mostly as JSON, in local files or over HTTP(S): what the format modules share, and
their wiring for the application. Each other format module sits in the group of the types it (de)serializes, e.g.
`composition-format` in `theory`.

| Module | Description | Root package |
| ------ | ----------- | ------------ |
| [`format`](format/docs/ARCHITECTURE.md) | Wires the format modules into the repositories that `app` reads compositions, scales and rigs from | `org.calinburloiu.music.microtonalist.format` |
| [`format-core`](format-core/docs/ARCHITECTURE.md) | What the format modules share: picking a repository by URI scheme, resolving `$ref` references, (de)serializing plugins and the JSON formats of MIDI values | `org.calinburloiu.music.microtonalist.format` |
| `format-test-utils` | Test helpers shared by the tests of the format modules, such as `JsonFormatTestUtils` | `org.calinburloiu.music.microtonalist.format` |

The three modules share their package: `format` holds `FormatModule`, `format-test-utils` holds `JsonFormatTestUtils`,
and `format-core` holds the rest.

## Formats and repositories

A *format* (de)serializes a persisted object from a stream, whatever the stream's source, and a *repository* reads the
object from a `URI` through a format: for a scale, a `ScaleFormat` and a `ScaleRepo`. An object's `Default*Repo` picks
a repository by the URI's scheme, with `format-core`'s `RepoSelector`:

| Scheme | Repository | Reads from |
| ------ | ---------- | ---------- |
| none or `file` | `File*Repo` | a local file |
| `http` or `https` | `Http*Repo` | a web server |
| `microtonalist` | `Library*Repo`, for scales and rigs only | the user's library |

A library URI, `microtonalist:///<path>`, resolves `<path>` against the `libraryBaseUrl` of the
[configuration](../app/config/docs/ARCHITECTURE.md), a local directory or an HTTP URL. E.g. with the base URL
`file:///Users/john/Music/microtonalist/lib/`, `microtonalist:///scales/lydian.scl` reads
`/Users/john/Music/microtonalist/lib/scales/lydian.scl`.

## Naming

A format type's name ends in `Format`, and an implementation's starts with its serialization: `Json`, or
`HuygensFokkerScala` for the `.scl` files of the Huygens-Fokker Foundation's
[Scala](https://www.huygens-fokker.org/scala/) program, unrelated to the Scala language. E.g. `JsonScaleFormat` and
`HuygensFokkerScalaScaleFormat` implement `ScaleFormat`, and `JsonIntervalFormat` holds the Play JSON `Format`s of
intervals. The plugin formats follow the same pattern:

- `Json<Family>PluginFormat`, e.g. `JsonTunerPluginFormat`, is the `JsonPluginFormat` of a plugin family;
- `Json<Plugin>PluginFormatting`, e.g. `JsonMpeTunerPluginFormatting`, holds the `TypeSpec` of a plugin, or those of
  its variants (`JsonMtsTunerPluginFormatting`), which the format of its family gathers.

## JSON Schemas

[`json-schemas/v1/`](../json-schemas/v1) holds the JSON Schemas of the JSON formats. A file names its schema in
`$schema`, by the schema's URL on GitHub's `main` branch, so that an editor can validate it. The application doesn't
validate against them and no test checks them, so a change to a format must update its schema by hand.

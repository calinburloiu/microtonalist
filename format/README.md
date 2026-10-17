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

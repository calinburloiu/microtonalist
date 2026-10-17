# `app` module group

The executables, and the configuration of the application.

| Module | Description | Root package |
| ------ | ----------- | ------------ |
| [`app`](app/docs/ARCHITECTURE.md) | The desktop application: wires the modules together and runs the application's lifecycle | `org.calinburloiu.music.microtonalist` |
| [`config`](config/docs/ARCHITECTURE.md) | The HOCON configuration of the application: loads, updates and saves it | `org.calinburloiu.music.microtonalist.config` |
| [`cli`](cli/docs/ARCHITECTURE.md) | A command-line utility tool, e.g. for listing the MIDI devices | `org.calinburloiu.music.microtonalist.cli` |
| [`experiments`](experiments/docs/ARCHITECTURE.md) | Ad-hoc intonation studies, for research | `org.calinburloiu.music.microtonalist.experiments` |

See the [module overview](../docs/architecture/module-overview.md) for the other groups and how their modules depend
on each other.

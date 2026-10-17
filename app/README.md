# `app` module group

The application layer, on top of the infrastructure and domain groups: the executables, and the modules that turn the
domain modules into an application, such as its configuration.

| Module | Description | Root package |
| ------ | ----------- | ------------ |
| [`app`](app/docs/ARCHITECTURE.md) | The desktop application: wires the modules together and runs the application's lifecycle | `org.calinburloiu.music.microtonalist` |
| [`config`](config/docs/ARCHITECTURE.md) | The HOCON configuration of the application: loads, updates and saves it | `org.calinburloiu.music.microtonalist.config` |
| [`cli`](cli/docs/ARCHITECTURE.md) | A command-line utility tool, e.g. for listing the MIDI devices | `org.calinburloiu.music.microtonalist.cli` |
| [`experiments`](experiments/docs/ARCHITECTURE.md) | Ad-hoc intonation studies, for research | `org.calinburloiu.music.microtonalist.experiments` |

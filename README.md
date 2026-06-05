# Microtonalist

[![Build](https://github.com/calinburloiu/microtonalist/actions/workflows/scala.yml/badge.svg)](https://github.com/calinburloiu/microtonalist/actions/workflows/scala.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE.txt)

> **⚠️ Work in progress.** Microtonalist is under active development and not yet feature-complete.

Microtonalist is a microtuner application that tunes musical keyboards and synthesizers in real time for playing music
with microtones. It is a stand-alone, multi-platform desktop application that runs on the JVM.

You work with a sequence of **scales**, a high-level concept, and the application maps them to octave-based **tunings**,
a low-level concept that assigns a tuning value to each pitch class of the keyboard. Tunings are sent to output
instruments over several protocols:

- **MIDI Tuning Standard (MTS)**
- **Monophonic Pitch Bend**
- **MIDI Polyphonic Expression (MPE)**

## Documentation

- [Development Setup Guide](docs/development/README.md) — prerequisites (JDK 23, Scala 3, sbt 1), building, testing, and
  AI-assisted development with Claude Code (Metals MCP, GitHub plugin).
- [Architecture](docs/architecture/README.md) — module overview, domain concepts, data flow, and per-module deep dives.
- [Contributing](CONTRIBUTING.md) — GitHub conventions (labels, branches, issues, pull requests) and coding standards.

## Building and running

Microtonalist is built with **sbt 1**, **Scala 3**, and **JDK 23**. Compile all modules with `sbt compile`, run the
tests with `sbt test`, and build the fat JAR with `sbt assembly`. See the
[Development Setup Guide](docs/development/README.md) for the full reference.

## Contributing

Contributions are welcome! See [`CONTRIBUTING.md`](CONTRIBUTING.md) for the GitHub conventions (labels, branches,
issues, pull requests) and links to the development setup and coding standards.

## Engineering

The application is split into layered SBT modules (`app`, `composition`, `tuner`, `format`, `intonation`, `sc-midi`,
and more). For the module structure, dependency graph, and the root package of each module, see the
[module overview](docs/architecture/module-overview.md); for the domain types and how a composition becomes tuning MIDI
messages, see the [architecture docs](docs/architecture/README.md).

The application is configured via a [HOCON](https://github.com/lightbend/config/blob/master/HOCON.md) file (on macOS at
`~/.microtonalist/microtonalist.conf`); see the [`appConfig` module architecture](docs/architecture/config/README.md)
for details.

## License

Microtonalist is licensed under the [Apache License 2.0](LICENSE.txt).

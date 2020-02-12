Microtonalist is a microtuner application that allows tuning musical keyboards and synthesizers in real-time for playing music with microtones. It is build as a stand-alone multi-platform desktop application that runs on JVM.

# Engineering #

## Configuration ##

The application is configured via [HOCON](https://github.com/lightbend/config/blob/master/HOCON.md) configuration file stored in a standard location based on the operation system. On Mac the location is `~/.microtonalist/microtonalist.conf`.

Each module or application component holds a fragment of the whole configuration that corresponds to one or more subtrees of the HOCON configuration. Each such fragment is conventionally organized in the following classes (replace the `*` with the actual name of the fragment):

* `*Configured` (e.g. `MidiIOConfigured`): read-only interface defined as `trait`
* `*Configurable` (e.g. `MidiIOConfigurable`): interface defined as a trait, which extends the corresponding `*Configured` trait, used for reading and writing in the configuration
* `*Config` (e.g. `MidiIOConfig`): concrete class that implements either the corresponding `*Configured` or `*Configurable` trait, depending on whether read-only or read-write is required, and reads and/or writes the HOCON configuration subtrees

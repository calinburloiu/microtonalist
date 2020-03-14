**WORK IN PROGRESS!**

Microtonalist is a microtuner application that allows tuning musical keyboards and synthesizers in real-time for playing music with microtones. It is build as a stand-alone multi-platform desktop application that runs on JVM.

# Engineering #

## Configuration ##

The application is configured via [HOCON](https://github.com/lightbend/config/blob/master/HOCON.md) configuration file stored in a standard location based on the operation system. On Mac the location is `~/.microtonalist/microtonalist.conf`.

Each module or application component holds a fragment of the whole configuration that corresponds to a subtrees of the HOCON configuration.

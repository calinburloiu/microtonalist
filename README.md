**WORK IN PROGRESS!**

Microtonalist is a microtuner application that allows tuning musical keyboards and synthesizers in real-time for playing
music with microtones. It is build as a stand-alone multi-platform desktop application that runs on JVM.

# Engineering #

## Modules ##

The application is composed of the following modules:

* **`app`** with package `org.calinburloiu.music.microtonalist`: assembles all modules into an application with GUI.
* **`cli`** with package `org.calinburloiu.music.microtonalist.cli`: command line tool application with various
  utilities.
* **`ui`** with package `org.calinburloiu.music.microtonalist.ui`: application UI, mainly GUI, but can also contain some
  TUI assets.
* **`common`** with package `org.calinburloiu.music.microtonalist.common`: common assets, utilities and helpers to be
  shared between application modules.
* **`composition`** with package `org.calinburloiu.music.microtonalist.composition`: domain model of the application
  around which other modules revolve.
* **`config`** with package `org.calinburloiu.music.microtonalist.config`: configuration infrastructure based on [HOCON](https://github.com/lightbend/config/blob/master/HOCON.md).
* **`tuner`** with package `org.calinburloiu.music.microtonalist.tuner`: module responsible to tune output instruments
  by using standard Java MIDI library.
* **`format`** with package `org.calinburloiu.music.microtonalist.format`: module responsible for reading / writing
  compositions and other things related to a musical composition to persistence storage. Right now, only files are
  supported by using JSON and Scala application formats, but in the future we might support cloud storage. Application
  configuration is handled in `app` module via HOCON files.

Other reusable libraries, not specifically related to the application, are also defined as modules:

* **`businessync`** with package `org.calinburloiu.businessync`: library responsible for providing communication and
  synchronization between application modules by providing an easy-to-use threading model based on a business logic
  thread and a UI thread.
* **`intonation`** with package `org.calinburloiu.music.intonation`: Assets for handling microtonal intervals.
* **`sc-midi`** with package `org.calinburloiu.music.scmidi`: Scala utility / wrappers over standard Java MIDI library
  to provide a more idiomatic usage in Scala.

## Configuration ##

The application is configured via [HOCON](https://github.com/lightbend/config/blob/master/HOCON.md) configuration file
stored in a standard location based on the operation system. On Mac the location is
`~/.microtonalist/microtonalist.conf`.

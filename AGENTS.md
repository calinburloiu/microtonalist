Microtonalist is a microtuner application that allows tuning musical keyboards and synthesizers in real-time for playing
music with microtones. It supports various protocols for tuning output instruments like MIDI Tuning Standard (MTS),
Monophonic Pitch Bend and MIDI Polyphonic Expression (MPE). It is built as a stand-alone multi-platform desktop
application that runs on JVM. The code is written in Scala 3.

# Build

The repository is built using SBT 1, Scala 3 and Java 23. It is split in multiple SBT projects that act as modules,
libraries or separate executable applications. Each project is in the repository root. Check `build.sbt` for details.
The `root` SBT project aggregates all the other projects. The executable application is in `app` SBT project.

Compiling the whole `root` project:

```bash
sbt compile
```

Building the fat JAR for the executable application:

```bash
sbt assembly
```

It is recommended to compile, build or test the whole project before committing changes.

For small changes, it is recommended to only compile individual SBT projects.

Compiling a single SBT project `${PROJECT}`:

```bash
sbt "${PROJECT}/compile"
```

# Test

Tests and production code follow the default Scala and SBT directory structure. For each SBT project, production code is
in `src/main/scala` and tests are in `src/test/scala`. Tests are written using ScalaTest 3. Use `src/test/resources` for
test data if necessary.

Conventionally, the tests for a given production class use the same package and class name is suffixed with `Test`. For
example, the test class for `org.calinburloiu.music.intonation.RatioInterval` is
`org.calinburloiu.music.intonation.RatioIntervalTest`.

Running all tests:

```bash
sbt test
```

For small changes, it is recommended to only test individual files or SBT projects.

Testing a single SBT project `${PROJECT}`:

```bash
sbt "${PROJECT}/test"
```

Testing a single test class `${CLASS}` (declared with fully qualified name) in an SBT project `${PROJECT}`:

```bash
sbt "${PROJECT}/testOnly ${CLASS}"
```

For example:

```bash
sbt "intonation/testOnly org.calinburloiu.music.intonation.RatioIntervalTest"
```

# Coding Conventions

* Indentation is done with 2 spaces.
* Lines have a maximum length of 120 characters.
* Currently, we use IntelliJ IDEA for formatting code with the default settings.
* All public identifiers (classes, methods, fields, etc.) are properly documented via ScalaDocs.

## Use brace syntax

Use the old classic brace Scala syntax, not the new indentation Scala 3 syntax.

Wrong:

```scala
case class Person(name: String, age: Int):
  def greet: String = s"Hi, I'm $name"
```

Correct:

```scala
case class Person(name: String, age: Int) {
  def greet: String = s"Hi, I'm $name"
}
```

## Use `enum`

Use Scala 3 `enum` instead of `sealed trait` for simple enumerations.

Wrong:

```scala
sealed trait MpeInputMode

case object NonMpe extends MpeInputMode

case object Mpe extends MpeInputMode
```

Correct:

```scala
enum MpeInputMode {
  case NonMpe
  case Mpe
}
```

## Avoid `case class` for mutable data structures

Avoid using case classes for data structures that expose mutable fields.

Wrong:

```scala
case class ActiveNote(midiNote: MidiNote,
                      var expressivePitchBend: Int = 0)
```

Correct:

```scala
class ActiveNote(val midiNote: MidiNote,
                 var expressivePitchBend: Int = 0)
```

## TODOs have issue numbers

All TODOs in the code use `// TODO #<issue_number>`, where `<issue_number>` is the issue number on the project's GitHub
repository: https://github.com/calinburloiu/microtonalist

Wrong:

```scala
// TODO Add support for Windows
```

Correct:

```scala
// TODO #149 Add support for Windows
```

## Prefer for-comprehensions for nested monads

Using a deep chain of `flatMap`, `map`, ..., `map` for nested monads can be hard to follow. Prefer for-comprehensions
for them.

Wrong:

```scala
val optionsList: List[Option[Int]] = List(Some(1), None, Some(2))
optionsList.flatMap(list => list.map(item => item * 2))
```

Correct:

```scala
val optionsList: List[Option[Int]] = List(Some(1), None, Some(2))
for {
  itemOption <- optionsList
  item <- itemOption
} yield item * 2
```

## Don't use `new` when instantiating a class

Wrong:

```scala
val c = new MyClass(x, y)
```

Correct:

```scala
val c = MyClass(x, y)
```

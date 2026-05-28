# Scala Coding Conventions

General (mainly production code) coding conventions for this repository. Test-specific conventions live in
[`test-conventions.md`](test-conventions.md).

## General formatting

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

## Avoid `new` when instantiating a class

Wrong:

```scala
val c = new MyClass(x, y)
```

Correct:

```scala
val c = MyClass(x, y)
```

## No `return`

The `return` statement is deprecated in Scala 3. When a Scala idiomatic approach is cumbersome, use a `boundary.break`
instead. A typical case is an early return, an initial condition in a method, where a Scala idiomatic `if` would cause
most of the code to be overindented.

Wrong:

```scala
class C {
  def method(): Int = {
    if (notValid) {
      return -1
    }

    // Large block of code
    // ...
  }
}
```

Correct:

```scala
class C {
  def method(): Int = boundary {
    if (notValid) {
      boundary.break(-1)
    }

    // Large block of code
    // ...
  }
}
```

## Class private internal backing variables for public getter / setter

If a class has a public getter or setter that is backed by an internal `private` or `protected` variable, use the same
name for the interval variable with the getter / setter but prefixed by an underscore.

```scala
class C {
  private var _value: Int

  def value: Int = _value

  def value_=(newValue: Int): Unit = {
    _value = newValue
  }
}
```

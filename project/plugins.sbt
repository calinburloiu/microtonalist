addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "1.1.0")
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.13.1")
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.4.4")
// TODO #336 Upgrade to sbt-scalafmt 2.6.x, which refuses to run on sbt older than 1.12.9, after upgrading sbt
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.6")
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.9")

import sbt._
import Keys._

object Dependencies {
  // # Versions
  val coreMidi4jVersion = "1.4"
  val enumeratumVersion = "1.6.1"
  val ficusVersion = "1.5.0"
  val guavaVersion = "29.0-jre"
  val logbackVersion = "1.2.3"
  val playJsonVersion = "2.9.1"
  val scalaLoggingVersion = "3.9.2"
  val scalaMockVersion = "5.0.0"
  val scalaTestVersion = "3.2.2"

  // # Dependency definitions
  val coreMidi4j = "uk.co.xfactory-librarians" % "coremidi4j" % coreMidi4jVersion
  val enumeratum = "com.beachape" %% "enumeratum" % enumeratumVersion
  val ficus = "com.iheart" %% "ficus" % ficusVersion
  val guava = "com.google.guava" % "guava" % guavaVersion
  val logback = "ch.qos.logback" % "logback-classic" % logbackVersion
  val playJson = "com.typesafe.play" %% "play-json" % playJsonVersion
  val scalaLogging = "com.typesafe.scala-logging" %% "scala-logging" % scalaLoggingVersion
  val scalaMock = "org.scalamock" %% "scalamock" % scalaMockVersion
  val scalaTest = "org.scalatest" %% "scalatest" % scalaTestVersion
}


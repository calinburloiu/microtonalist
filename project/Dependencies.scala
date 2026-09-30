/*
 * Copyright 2026 Calin-Andrei Burloiu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

import sbt.*

object Dependencies {
  // # Versions
  val coreMidi4jVersion = "1.6"
  val ficusVersion = "1.5.2"
  val guavaVersion = "33.7.2-jre"
  val jsr305Version = "3.0.2"
  val logbackVersion = "1.6.4"
  val playJsonVersion = "3.0.6"
  val scalaLoggingVersion = "3.9.6"
  val scalaMockVersion = "7.6.0"
  val scalaTestVersion = "3.2.20"

  // # Dependency definitions
  val coreMidi4j = "uk.co.xfactory-librarians" % "coremidi4j" % coreMidi4jVersion
  val ficus = "com.iheart" %% "ficus" % ficusVersion
  val guava = "com.google.guava" % "guava" % guavaVersion
  // The `javax.annotation.concurrent` annotations (`@ThreadSafe`, `@NotThreadSafe`). Guava stopped depending on it in
  // 33.4.3.
  val jsr305 = "com.google.code.findbugs" % "jsr305" % jsr305Version
  val logback = "ch.qos.logback" % "logback-classic" % logbackVersion
  val playJson = "org.playframework" %% "play-json" % playJsonVersion
  val scalaLogging = "com.typesafe.scala-logging" %% "scala-logging" % scalaLoggingVersion
  // The ScalaTest integration (`org.scalamock.scalatest.MockFactory`), which depends on scalamock itself. Since 7.6.0 it
  // is no longer part of the scalamock artifact.
  val scalaMock = "org.scalamock" %% "scalamock-scalatest" % scalaMockVersion
  val scalaTest = "org.scalatest" %% "scalatest" % scalaTestVersion
}

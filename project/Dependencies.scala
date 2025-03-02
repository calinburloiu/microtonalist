/*
 * Copyright 2021 Calin-Andrei Burloiu
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
  val guavaVersion = "33.4.0-jre"
  val logbackVersion = "1.5.17"
  val playJsonVersion = "3.0.4"
  val scalaLoggingVersion = "3.9.5"
  val scalaMockVersion = "7.2.0"
  val scalaTestVersion = "3.2.19"

  // # Dependency definitions
  val coreMidi4j = "uk.co.xfactory-librarians" % "coremidi4j" % coreMidi4jVersion
  val ficus = "com.iheart" %% "ficus" % ficusVersion
  val guava = "com.google.guava" % "guava" % guavaVersion
  val logback = "ch.qos.logback" % "logback-classic" % logbackVersion
  val playJson = "org.playframework" %% "play-json" % playJsonVersion
  val scalaLogging = "com.typesafe.scala-logging" %% "scala-logging" % scalaLoggingVersion
  val scalaMock = "org.scalamock" %% "scalamock" % scalaMockVersion
  val scalaTest = "org.scalatest" %% "scalatest" % scalaTestVersion
}


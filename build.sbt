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

import Dependencies.*

ThisBuild / scalaVersion := "3.6.3"
ThisBuild / version := "1.3.0-SNAPSHOT"
ThisBuild / organization := "org.calinburloiu.music"

// Register the coverage-related commands
commands ++= Coverage.commands

// # Projects

lazy val root = (project in file("."))
  .aggregate(
    app,
    appConfig,
    businessync,
    cli,
    common,
    commonTestUtils,
    ui,
    composition,
    tuner,
    format,
    intonation,
    scMidi,
  )
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-root",
    commonSettings,
    // Aggregate thresholds — enforced by `coverageAggregate` on the combined report from all modules.
    // TODO #183 Raise toward 80% statement and branch coverage once the per-module floors do.
    coverageSettings(stmt = 71, branch = 65),
  )

lazy val app = (project in file("app"))
  .dependsOn(
    appConfig,
    businessync,
    common,
    commonTestUtils % Test,
    composition,
    intonation,
    format,
    scMidi,
    tuner,
    ui,
  )
  .enablePlugins(BuildInfoPlugin)
  .settings(
    name := "microtonalist-app",
    commonSettings,
    assemblySettings,
    assembly / mainClass := Some("org.calinburloiu.music.microtonalist.MicrotonalistApp"),
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "org.calinburloiu.music.microtonalist",
    libraryDependencies ++= Seq(
      coreMidi4j,
      guava,
      playJson,
    ),
    // TODO #180 Raise toward 80% statement and branch coverage.
    coverageSettings(stmt = 0, branch = 0),
  )

lazy val appConfig = (project in file("config"))
  .dependsOn(
    common
  )
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-config",
    commonSettings,
    libraryDependencies ++= Seq(
      ficus,
    ),
    // TODO #176 Raise toward 80% statement and branch coverage.
    coverageSettings(stmt = 59, branch = 39),
  )

lazy val cli = (project in file("cli"))
  .dependsOn(
    scMidi,
  )
  .settings(
    name := "microtonalist-cli",
    commonSettings,
    assemblySettings,
    assembly / mainClass := Some("org.calinburloiu.music.microtonalist.cli.MicrotonalistToolApp"),
    // TODO #181 Raise toward 80% statement and branch coverage.
    coverageSettings(stmt = 0, branch = 0),
  )

lazy val ui = (project in file("ui"))
  .dependsOn(
    tuner,
  )
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-ui",
    commonSettings,
    // TODO #182 Raise toward 80% statement and branch coverage.
    coverageSettings(stmt = 0, branch = 0),
  )

lazy val common = (project in file("common"))
  .dependsOn(
    commonTestUtils % Test,
  )
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-common",
    commonSettings,
    libraryDependencies ++= Seq(
      guava,
    ),
    // TODO #175 Raise toward 80% statement and branch coverage.
    coverageSettings(stmt = 50, branch = 22),
  )

lazy val commonTestUtils = (project in file("common-test-utils"))
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-common-test-utils",
    commonSettings,
    // Test infrastructure — exclude from coverage measurement.
    coverageEnabled := false,
  )

lazy val businessync = (project in file("businessync"))
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-businessync",
    commonSettings,
    libraryDependencies ++= Seq(
      guava,
    ),
    // TODO #174 Raise toward 80% statement and branch coverage.
    coverageSettings(stmt = 0, branch = 0),
  )

lazy val composition = (project in file("composition"))
  .dependsOn(
    intonation,
    tuner,
  )
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-composition",
    commonSettings,
    libraryDependencies ++= Seq(),
    coverageSettings(stmt = 80, branch = 80),
  )

lazy val tuner = (project in file("tuner"))
  .dependsOn(
    businessync,
    common,
    scMidi,
  )
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-tuner",
    commonSettings,
    libraryDependencies ++= Seq(),
    // TODO #178 Raise toward 80% statement and branch coverage.
    coverageSettings(stmt = 71, branch = 69),
  )

lazy val format = (project in file("format"))
  .dependsOn(
    common,
    commonTestUtils % Test,
    composition,
    tuner,
  )
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-format",
    commonSettings,
    libraryDependencies ++= Seq(
      playJson,
    ),
    // TODO #179 Raise toward 80% statement and branch coverage.
    coverageSettings(stmt = 66, branch = 59),
  )

lazy val intonation = (project in file("intonation"))
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "intonation",
    commonSettings,
    libraryDependencies ++= Seq(
      guava,
    ),
    // TODO #185 Raise toward 80% statement coverage.
    coverageSettings(stmt = 72, branch = 80),
  )

lazy val scMidi = (project in file("sc-midi"))
  .dependsOn(
    businessync,
    common
  )
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "sc-midi",
    commonSettings,
    libraryDependencies ++= Seq(
      coreMidi4j,
    ),
    // TODO #177 Raise toward 80% statement and branch coverage.
    coverageSettings(stmt = 62, branch = 44),
  )

lazy val experiments = (project in file("experiments"))
  .dependsOn(
    intonation,
  )
  .settings(
    name := "microtonalist-app",
    commonSettings,
    assemblySettings,
    assembly / mainClass := Some("org.calinburloiu.music.microtonalist.experiments.SoftChromaticGenusStudy"),
    libraryDependencies ++= Seq(),
  )

// # Dependencies

lazy val commonDependencies = Seq(
  logback,
  scalaLogging,
  scalaTest % Test,
  scalaMock % Test,
)

// # Settings

// Code coverage targets — see AGENTS.md "Coverage" section. The project-wide target is 80% for both
// statement and branch. Modules that have not yet reached 80% are configured with their current
// coverage minus a 3% buffer and an open issue to track improvement work. The threshold must never
// be lowered: it can only stay flat or rise toward 80%.
def coverageSettings(stmt: Double, branch: Double): Seq[Setting[?]] = Seq(
  coverageMinimumStmtTotal := stmt,
  coverageMinimumBranchTotal := branch,
  coverageFailOnMinimum := true,
  // Write scoverage data and reports under `<repo-root>/coverage-reports/<project-id>/` so they
  // survive `sbt clean` (which only wipes `target/`). Use `coverageClean` to delete them.
  coverageDataDir := (LocalRootProject / baseDirectory).value / "coverage-reports" / thisProject.value.id,
)

lazy val compilerOptions = Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-encoding", "utf8",
  "-language:implicitConversions",
  "-language:postfixOps",
  // Used for scalamock: trait Mock is marked as experimental
  "-experimental"
)

// When `-Dmicrotonalist.build.targetSuffix=<suffix>` is passed to sbt, every project's `target` directory
// becomes `<project>/target<suffix>` instead of the default `<project>/target`. Used by
// `scripts/development/start-sbt-metals.sh` (with suffix `-bsp`) so the sbt server backing Metals
// writes to a different tree than ad-hoc CLI sbt invocations and the two never collide on the same
// `classes/` directory. `sbt clean` follows the active `target` setting, so each tree is cleaned
// independently. See https://github.com/calinburloiu/microtonalist/issues/186.
lazy val targetSuffixOverride: Seq[Setting[?]] =
  sys.props.get("microtonalist.build.targetSuffix").filter(_.nonEmpty) match {
    case Some(suffix) => Seq(target := baseDirectory.value / s"target$suffix")
    case None         => Seq.empty
  }

lazy val commonSettings = Seq(
  javacOptions ++= Seq(
    "-source", "23", "-target", "23",
  ),
  scalacOptions ++= compilerOptions,
  resolvers += "Local Maven Repository" at "file://"+Path.userHome.absolutePath+"/.m2/repository",
  libraryDependencies ++= commonDependencies,
  Test / unmanagedResourceDirectories += (ThisBuild / baseDirectory).value / "project" / "test-resources",
) ++ targetSuffixOverride

lazy val assemblySettings = Seq(
  assembly / assemblyJarName := name.value + ".jar",
  assembly / assemblyMergeStrategy := {
    case x if Assembly.isConfigFile(x) =>
      MergeStrategy.concat
    case PathList(ps @ _*) if Assembly.isReadme(ps.last) || Assembly.isLicenseFile(ps.last) =>
      MergeStrategy.rename
    case PathList(ps@_*) if ps.last == "module-info.class" => MergeStrategy.discard
    case PathList("META-INF", xs @ _*) =>
      xs.map(_.toLowerCase) match {
        case "manifest.mf" :: Nil | "index.list" :: Nil | "dependencies" :: Nil =>
          MergeStrategy.discard
        case ps @ _ :: _ if ps.last.endsWith(".sf") || ps.last.endsWith(".dsa") =>
          MergeStrategy.discard
        case "plexus" :: _ =>
          MergeStrategy.discard
        case "services" :: _ =>
          MergeStrategy.filterDistinctLines
        case "spring.schemas" :: Nil | "spring.handlers" :: Nil =>
          MergeStrategy.filterDistinctLines
        case _ => MergeStrategy.deduplicate
      }
    case "module-info.class" => MergeStrategy.discard
    case _ => MergeStrategy.deduplicate
  }
)


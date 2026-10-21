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

// The Scala version lives in `project/scala-version`, so that the scripts in `bin/` can read it too: it is part of the
// path of the fat JARs they run.
ThisBuild / scalaVersion := IO.read((ThisBuild / baseDirectory).value / "project" / "scala-version").trim
ThisBuild / version := "1.6.0-SNAPSHOT"
ThisBuild / organization := "org.calinburloiu.music"

// SemanticDB, which scalafix's semantic rules read. Metals enables it too, but plain sbt (CI) needs it set.
ThisBuild / semanticdbEnabled := true

// Register the coverage-related commands
commands ++= Coverage.commands

// Code formatting and linting: `fix` applies the scalafix autofixes, then formats the sources with scalafmt; `lint`
// compiles all the code and fails on a warning other than a deprecation (`warningsCheck`), then checks the scalafix
// rules and the formatting without changing anything; `fixLint` runs both, as the agents' Lint step does. See
// docs/development/build.md#formatting and docs/development/linting.md.
addCommandAlias("fix", "scalafixAll; scalafmtAll; scalafmtSbt")
addCommandAlias("lint", "warningsCheck; scalafixAll --check; scalafmtCheckAll; scalafmtSbtCheck")
addCommandAlias("fixLint", "fix; lint")

// # Projects
//
// CONVENTION: every module lives at `<group>/<id>/`, in the folder of its module group, which holds the modules of one
// area (see docs/architecture/module-overview.md), and sets `.withId(<id>)`, so its sbt project ID always
// equals the name of its own folder — even where the `lazy val` name already matches (it is added redundantly on
// purpose, to make the rule uniform and self-documenting). This consistency is relied upon by tooling that maps a
// source directory to its sbt module ID without parsing this file — notably the `scoverage-inspector` MCP server
// (`.claude/mcp/scoverage_inspector/`), whose reports live at `coverage-reports/<id>/` while sources live at
// `<group>/<id>/src/`. The `lazy val` name follows the convention `<camelCaseId>Module` (e.g. `scMidiModule` for
// `midi/sc-midi`). The ID — used in `sbt "<id>/test"`, in `thisProject.value.id`, and thus in `coverageDataDir` — must
// equal the folder's name. The sole special case is `root` (base dir ".", ID "root"), which keeps its plain `root` val
// name. When adding a module, put it in its module group's folder and set its `.withId` to its folder's name.
//
// CONVENTION: `root` comes first, then the modules, one module group after another in the order of the group table in
// docs/architecture/module-overview.md, sorted by folder name within a group; `root`'s `.aggregate` lists them in the
// same order. sbt needs no order, since the projects are `lazy val`s: this one only gives each module an obvious place.

lazy val root = (project in file("."))
  .withId("root")
  .aggregate(
    businessyncModule,
    commonModule,
    compositionModule,
    compositionFormatModule,
    intonationModule,
    intonationFormatModule,
    scMidiModule,
    scMidiJavamidiModule,
    tunerModule,
    tunerFormatModule,
    tunerMpbModule,
    tunerMpeModule,
    tunerMtsModule,
    tuningModule,
    tuningChangerModule,
    tuningChangerFormatModule,
    tuningChangerPedalModule,
    rigModule,
    rigFormatModule,
    formatModule,
    formatCoreModule,
    uiModule,
    appModule,
    cliModule,
    configModule,
    experimentsModule,
  )
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-root",
    commonSettings,
    // Aggregate thresholds — enforced by `coverageAggregate` on the combined report from all modules.
    coverageSettings(stmt = 80, branch = 80),
  )

lazy val businessyncModule = (project in file("base/businessync"))
  .withId("businessync")
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

lazy val commonModule = (project in file("base/common"))
  .withId("common")
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-common",
    commonSettings,
    // TODO #175 Raise toward 80% statement and branch coverage.
    coverageSettings(stmt = 50, branch = 22),
  )

lazy val compositionModule = (project in file("theory/composition"))
  .withId("composition")
  .dependsOn(
    commonModule,
    intonationModule,
    scMidiModule,
    tuningModule % "compile->compile;test->test",
  )
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-composition",
    commonSettings,
    libraryDependencies ++= Seq(
      guava,
    ),
    coverageSettings(stmt = 80, branch = 80),
  )

lazy val compositionFormatModule = (project in file("theory/composition-format"))
  .withId("composition-format")
  .dependsOn(
    businessyncModule % Test,
    commonModule % "compile->compile;test->test",
    compositionModule,
    formatCoreModule % "compile->compile;test->test",
    intonationFormatModule,
    intonationModule,
    scMidiModule,
    tuningModule,
  )
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-composition-format",
    commonSettings,
    libraryDependencies ++= Seq(
      playJson,
    ),
    // TODO #179 Raise toward 80% statement and branch coverage.
    coverageSettings(stmt = 70, branch = 70),
  )

lazy val intonationModule = (project in file("theory/intonation"))
  .withId("intonation")
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "intonation",
    commonSettings,
    libraryDependencies ++= Seq(
      guava,
    ),
    // * SagittalInterval is a registry of interval constants with no logic to test
    coverageExcludedFiles := ".*/org/calinburloiu/music/intonation/intervals/SagittalInterval",
    coverageSettings(stmt = 80, branch = 80),
  )

lazy val intonationFormatModule = (project in file("theory/intonation-format"))
  .withId("intonation-format")
  .dependsOn(
    businessyncModule,
    commonModule % "compile->compile;test->test",
    formatCoreModule % "compile->compile;test->test",
    intonationModule,
  )
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-intonation-format",
    commonSettings,
    libraryDependencies ++= Seq(
      guava,
      playJson,
    ),
    // TODO #179 Raise toward 80% statement and branch coverage.
    coverageSettings(stmt = 67, branch = 75),
  )

lazy val scMidiModule = (project in file("midi/sc-midi"))
  .withId("sc-midi")
  .dependsOn(
    commonModule,
  )
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "sc-midi",
    commonSettings,
    libraryDependencies ++= Seq(
      jsr305,
    ),
    coverageSettings(stmt = 80, branch = 80),
  )

lazy val scMidiJavamidiModule = (project in file("midi/sc-midi-javamidi"))
  .withId("sc-midi-javamidi")
  .dependsOn(
    commonModule % "compile->compile;test->test",
    scMidiModule,
  )
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "sc-midi-javamidi",
    commonSettings,
    libraryDependencies ++= Seq(
      coreMidi4j,
      jsr305,
    ),
    // * CoreMidi4JEnvironment only delegates to the CoreMIDI4J and MidiSystem statics, which need MIDI hardware; the
    //   JavaMidiEnvironment seam it implements lets JavaMidiManager be tested over a fake instead
    coverageExcludedFiles := ".*/org/calinburloiu/music/scmidi/javamidi/CoreMidi4JEnvironment",
    coverageSettings(stmt = 80, branch = 80),
  )

lazy val tunerModule = (project in file("tuner/tuner"))
  .withId("tuner")
  .dependsOn(
    businessyncModule,
    commonModule % "compile->compile;test->test",
    scMidiModule,
    tuningModule % "compile->compile;test->test",
  )
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-tuner",
    commonSettings,
    libraryDependencies ++= Seq(
      guava,
      jsr305,
    ),
    coverageSettings(stmt = 80, branch = 80),
  )

lazy val tunerFormatModule = (project in file("tuner/tuner-format"))
  .withId("tuner-format")
  .dependsOn(
    formatCoreModule % "compile->compile;test->test",
    scMidiModule,
    tunerModule,
    tunerMpbModule,
    tunerMpeModule,
    tunerMtsModule,
  )
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-tuner-format",
    commonSettings,
    libraryDependencies ++= Seq(
      playJson,
    ),
    coverageSettings(stmt = 80, branch = 80),
  )

lazy val tunerMpbModule = (project in file("tuner/tuner-mpb"))
  .withId("tuner-mpb")
  .dependsOn(
    commonModule % "compile->compile;test->test",
    scMidiModule,
    tunerModule,
    tuningModule,
  )
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-tuner-mpb",
    commonSettings,
    coverageSettings(stmt = 80, branch = 80),
  )

lazy val tunerMpeModule = (project in file("tuner/tuner-mpe"))
  .withId("tuner-mpe")
  .dependsOn(
    commonModule % "compile->compile;test->test",
    scMidiModule,
    tunerModule,
    tuningModule,
  )
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-tuner-mpe",
    commonSettings,
    coverageSettings(stmt = 80, branch = 80),
  )

lazy val tunerMtsModule = (project in file("tuner/tuner-mts"))
  .withId("tuner-mts")
  .dependsOn(
    scMidiModule,
    tunerModule,
    tuningModule % "compile->compile;test->test",
  )
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-tuner-mts",
    commonSettings,
    coverageSettings(stmt = 80, branch = 80),
  )

lazy val tuningModule = (project in file("tuner/tuning"))
  .withId("tuning")
  .dependsOn(
    scMidiModule,
  )
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-tuning",
    commonSettings,
    libraryDependencies ++= Seq(
      guava,
    ),
    // TODO #377 Raise toward 80% statement and branch coverage.
    coverageSettings(stmt = 55, branch = 49),
  )

lazy val tuningChangerModule = (project in file("tuner/tuning-changer"))
  .withId("tuning-changer")
  .dependsOn(
    commonModule,
    scMidiModule,
    tuningModule,
  )
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-tuning-changer",
    commonSettings,
    libraryDependencies ++= Seq(
      jsr305,
    ),
    coverageSettings(stmt = 80, branch = 80),
  )

lazy val tuningChangerFormatModule = (project in file("tuner/tuning-changer-format"))
  .withId("tuning-changer-format")
  .dependsOn(
    formatCoreModule % "compile->compile;test->test",
    scMidiModule,
    tuningChangerModule,
    tuningChangerPedalModule,
  )
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-tuning-changer-format",
    commonSettings,
    libraryDependencies ++= Seq(
      playJson,
    ),
    // TODO #179 Raise toward 80% statement and branch coverage.
    coverageSettings(stmt = 80, branch = 63),
  )

lazy val tuningChangerPedalModule = (project in file("tuner/tuning-changer-pedal"))
  .withId("tuning-changer-pedal")
  .dependsOn(
    scMidiModule,
    tunerMtsModule % Test,
    tuningChangerModule,
    tuningModule,
  )
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-tuning-changer-pedal",
    commonSettings,
    coverageSettings(stmt = 80, branch = 80),
  )

lazy val rigModule = (project in file("rig/rig"))
  .withId("rig")
  .dependsOn(
    businessyncModule,
    commonModule,
    scMidiModule,
    tunerModule % "compile->compile;test->test",
    tunerMtsModule % Test,
    tuningModule % "compile->compile;test->test",
    tuningChangerModule,
    tuningChangerPedalModule % Test,
  )
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-rig",
    commonSettings,
    libraryDependencies ++= Seq(
      guava,
      jsr305,
    ),
    coverageSettings(stmt = 80, branch = 80),
  )

lazy val rigFormatModule = (project in file("rig/rig-format"))
  .withId("rig-format")
  .dependsOn(
    commonModule % "compile->compile;test->test",
    formatCoreModule % "compile->compile;test->test",
    rigModule,
    scMidiModule,
    tunerFormatModule % Test,
    tunerModule,
    tunerMpbModule % Test,
    tunerMtsModule % Test,
    tuningChangerFormatModule % Test,
    tuningChangerModule,
    tuningChangerPedalModule % Test,
  )
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-rig-format",
    commonSettings,
    libraryDependencies ++= Seq(
      jacksonCore % Test,
      playJson,
    ),
    // TODO #179 Raise toward 80% statement and branch coverage.
    coverageSettings(stmt = 48, branch = 39),
  )

lazy val formatModule = (project in file("format/format"))
  .withId("format")
  .dependsOn(
    businessyncModule,
    commonModule % "compile->compile;test->test",
    compositionFormatModule,
    formatCoreModule,
    intonationFormatModule,
    intonationModule % Test,
    rigFormatModule,
    rigModule,
    tunerFormatModule,
    tunerMtsModule % Test,
    tuningChangerFormatModule,
    tuningChangerPedalModule % Test,
  )
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-format",
    commonSettings,
    libraryDependencies ++= Seq(
      guava % Test,
    ),
    coverageSettings(stmt = 80, branch = 80),
  )

lazy val formatCoreModule = (project in file("format/format-core"))
  .withId("format-core")
  .dependsOn(
    commonModule,
    scMidiModule,
  )
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-format-core",
    commonSettings,
    libraryDependencies ++= Seq(
      playJson,
    ),
    // TODO #179 Raise toward 80% statement and branch coverage.
    coverageSettings(stmt = 65, branch = 63),
  )

lazy val uiModule = (project in file("ui/ui"))
  .withId("ui")
  .dependsOn(
    tunerModule,
    tuningModule,
  )
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-ui",
    commonSettings,
    libraryDependencies ++= Seq(
      guava,
    ),
    // TODO #182 Raise toward 80% statement and branch coverage.
    coverageSettings(stmt = 0, branch = 0),
  )

lazy val appModule = (project in file("app/app"))
  .withId("app")
  .dependsOn(
    configModule,
    businessyncModule,
    commonModule % "compile->compile;test->test",
    compositionModule,
    intonationModule % Test,
    formatModule,
    rigModule,
    scMidiJavamidiModule,
    tunerModule,
    uiModule,
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
      guava,
    ),
    // * LevelHighlightingConverter only maps log levels to the console colors of logback.xml
    coverageExcludedFiles := ".*/org/calinburloiu/music/microtonalist/LevelHighlightingConverter",
    // TODO #180 Raise toward 80% statement and branch coverage.
    coverageSettings(stmt = 0, branch = 0),
  )

lazy val cliModule = (project in file("app/cli"))
  .withId("cli")
  .dependsOn(
    scMidiModule,
    scMidiJavamidiModule,
  )
  .settings(
    name := "microtonalist-cli",
    commonSettings,
    assemblySettings,
    assembly / mainClass := Some("org.calinburloiu.music.microtonalist.cli.MicrotonalistToolApp"),
    // TODO #181 Raise toward 80% statement and branch coverage.
    coverageSettings(stmt = 0, branch = 0),
  )

lazy val configModule = (project in file("app/config"))
  .withId("config")
  .dependsOn(
    commonModule
  )
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-config",
    commonSettings,
    libraryDependencies ++= Seq(
      ficus,
      typesafeConfig,
    ),
    // TODO #176 Raise toward 80% statement and branch coverage.
    coverageSettings(stmt = 59, branch = 39),
  )

lazy val experimentsModule = (project in file("app/experiments"))
  .withId("experiments")
  .dependsOn(
    intonationModule,
  )
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-experiments",
    commonSettings,
    libraryDependencies ++= Seq(),
    // Throwaway research studies without tests — exclude from coverage measurement.
    coverageEnabled := false,
  )

// # Dependencies

lazy val commonDependencies = Seq(
  logback,
  scalaLogging,
  scalaTest % Test,
  scalactic % Test,
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
  "-experimental",
  // Unused imports, private members, local definitions, parameters, and `@nowarn` annotations that suppress nothing.
  // Not used, for now: `-Wsafe-init` (slower compiles, and no convention needs it), `-Wshadow`, and `-Wvalue-discard`
  // with `-Wnonunit-statement` (every intentionally discarded result would need a noisy `val _ = …`).
  "-Wunused:all",
  // Brace syntax only: indentation syntax and `if … then` are compile errors. See
  // docs/development/coding-conventions.md#use-brace-syntax.
  "-no-indent",
  "-old-syntax",
  // Every warning is a compile error, except deprecations and two warnings that `lint` fails on instead: an unused
  // explicit parameter and an unused import. See project/Warnings.scala and docs/development/linting.md.
  Warnings.policy,
)

// When `-Dmicrotonalist.build.targetSuffix=<suffix>` is passed to sbt, every project's `target` directory
// becomes `<project>/target<suffix>` instead of the default `<project>/target`. Used by
// `bin/mtlist-dev-stack start` (with suffix `-bsp`) so the sbt server backing Metals
// writes to a different tree than ad-hoc CLI sbt invocations and the two never collide on the same
// `classes/` directory. `sbt clean` follows the active `target` setting, so each tree is cleaned
// independently. See https://github.com/calinburloiu/microtonalist/issues/186.
lazy val targetSuffixOverride: Seq[Setting[?]] =
  sys.props.get("microtonalist.build.targetSuffix").filter(_.nonEmpty) match {
    case Some(suffix) => Seq(target := baseDirectory.value / s"target$suffix")
    case None => Seq.empty
  }

lazy val commonSettings = Seq(
  javacOptions ++= Seq(
    "-source", "25", "-target", "25",
  ),
  scalacOptions ++= compilerOptions,
  resolvers += "Local Maven Repository" at "file://" + Path.userHome.absolutePath + "/.m2/repository",
  libraryDependencies ++= commonDependencies,
  Test / unmanagedResourceDirectories += (ThisBuild / baseDirectory).value / "project" / "test-resources",
) ++ Warnings.settings ++ targetSuffixOverride

lazy val assemblySettings = Seq(
  assembly / assemblyJarName := name.value + ".jar",
  // CoreMIDI4J loads its native library with `System.load`, a restricted method that the JDK warns about, and will
  // block in a future release, unless native access is enabled (JEP 472).
  assembly / packageOptions += Package.ManifestAttributes("Enable-Native-Access" -> "ALL-UNNAMED"),
  assembly / assemblyMergeStrategy := {
    case x if Assembly.isConfigFile(x) =>
      MergeStrategy.concat
    case PathList(ps @ _*) if Assembly.isReadme(ps.last) || Assembly.isLicenseFile(ps.last) =>
      MergeStrategy.rename
    case PathList(ps @ _*) if ps.last == "module-info.class" => MergeStrategy.discard
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

import Dependencies.*

ThisBuild / scalaVersion := "3.6.3"
ThisBuild / version := "1.3.0-SNAPSHOT"
ThisBuild / organization := "org.calinburloiu.music"

// Register the `coverageAll`, `coverageModule`, and `coverageAllRestore` commands defined in project/Coverage.scala.
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
    coverageSettings(stmt = 85, branch = 84),
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
    // TODO #? Raise toward 80% statement coverage.
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

// Code coverage targets — see CLAUDE.md "Coverage" section. The project-wide target is 80% for both
// statement and branch. Modules that have not yet reached 80% are configured with their current
// coverage minus a 3% buffer and an open issue to track improvement work. The threshold must never
// be lowered: it can only stay flat or rise toward 80%.
def coverageSettings(stmt: Double, branch: Double): Seq[Setting[?]] = Seq(
  coverageMinimumStmtTotal := stmt,
  coverageMinimumBranchTotal := branch,
  coverageFailOnMinimum := true,
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

lazy val commonSettings = Seq(
  javacOptions ++= Seq(
    "-source", "23", "-target", "23",
  ),
  scalacOptions ++= compilerOptions,
  resolvers += "Local Maven Repository" at "file://"+Path.userHome.absolutePath+"/.m2/repository",
  libraryDependencies ++= commonDependencies,
  Test / unmanagedResourceDirectories += (ThisBuild / baseDirectory).value / "project" / "test-resources",
)

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


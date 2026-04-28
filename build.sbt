import Dependencies.*

ThisBuild / scalaVersion := "3.6.3"
ThisBuild / version := "1.3.0-SNAPSHOT"
ThisBuild / organization := "org.calinburloiu.music"

// # Projects

lazy val root = (project in file("."))
  .aggregate(
    app,
    appConfig,
    businessync,
    cli,
    common,
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
  )

lazy val app = (project in file("app"))
  .dependsOn(
    appConfig % "compile->compile;test->test",
    businessync,
    common % "compile->compile;test->test",
    composition,
    intonation,
    format % "compile->compile;test->test",
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
    coverageSettings(stmt = 63, branch = 50),
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
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-common",
    commonSettings,
    libraryDependencies ++= Seq(
      guava,
    ),
    // TODO #175 Raise branch coverage to 80%.
    coverageSettings(stmt = 80, branch = 72),
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
    coverageSettings(stmt = 30, branch = 0),
  )

lazy val composition = (project in file("composition"))
  .dependsOn(
    intonation,
    tuner % "compile->compile;test->test",
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
    // TODO #178 Raise branch coverage to 80%.
    coverageSettings(stmt = 80, branch = 75),
  )

lazy val format = (project in file("format"))
  .dependsOn(
    common % "compile->compile;test->test",
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
    coverageSettings(stmt = 68, branch = 61),
  )

lazy val intonation = (project in file("intonation"))
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "intonation",
    commonSettings,
    libraryDependencies ++= Seq(
      guava,
    ),
    coverageSettings(stmt = 80, branch = 80),
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
    coverageSettings(stmt = 66, branch = 48),
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
  // Required when running tests with scoverage instrumentation: the default ScalaLibrary layering can
  // fail to load test classes shared via "test->test" inter-module dependencies.
  Test / classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.Flat,
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


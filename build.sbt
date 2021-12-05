import Dependencies._

ThisBuild / scalaVersion := "2.13.7"
ThisBuild / version := "0.3.0-SNAPSHOT"
ThisBuild / organization := "org.calinburloiu.music"

// # Projects

lazy val root = (project in file("."))
  .aggregate(
    app,
    cli,
    core,
    format,
    intonation,
    scMidi
  )
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-root",
  )

lazy val app = (project in file("app"))
  .dependsOn(
    core,
    intonation,
    format,
    format % "test->test",
    scMidi,
    scMidi % "test->test"
  )
  .settings(
    name := "microtonalist-app",
    assemblySettings,
    libraryDependencies ++= commonDependencies ++ Seq(
      coreMidi4j,
      enumeratum,
      ficus,
      guava,
      playJson,
      scalaMock % Test
    )
  )

lazy val cli = (project in file("cli"))
  .dependsOn(
    scMidi
  )
  .settings(
    name := "microtonalist-cli",
    assemblySettings,
    libraryDependencies ++= commonDependencies
  )

lazy val intonation = (project in file("intonation"))
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "intonation",
    libraryDependencies ++= commonDependencies ++ Seq(
      guava
    )
  )

lazy val scMidi = (project in file("sc-midi"))
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "sc-midi",
    libraryDependencies ++= commonDependencies ++ Seq(
      coreMidi4j
    )
  )

lazy val core = (project in file("core"))
  .dependsOn(
    intonation,
    scMidi
  )
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-core",
    libraryDependencies ++= commonDependencies
  )

lazy val format = (project in file("format"))
  .dependsOn(
    core
  )
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-format",
    libraryDependencies ++= commonDependencies ++ Seq(
      playJson,
      scalaMock % Test
    )
  )

// # Dependencies

// TODO #26 Can we inject this in a commonSettings?
lazy val commonDependencies = Seq(
  logback,
  scalaLogging,
  scalaTest % Test,
)

// # Settings

lazy val assemblySettings = Seq(
  assembly / assemblyJarName := name.value + ".jar",
  assembly / assemblyMergeStrategy := {
    case x if Assembly.isConfigFile(x) =>
      MergeStrategy.concat
    case PathList(ps @ _*) if Assembly.isReadme(ps.last) || Assembly.isLicenseFile(ps.last) =>
      MergeStrategy.rename
    case PathList("META-INF", xs @ _*) =>
      (xs map {_.toLowerCase}) match {
        case ("manifest.mf" :: Nil) | ("index.list" :: Nil) | ("dependencies" :: Nil) =>
          MergeStrategy.discard
        case ps @ (x :: xs) if ps.last.endsWith(".sf") || ps.last.endsWith(".dsa") =>
          MergeStrategy.discard
        case "plexus" :: xs =>
          MergeStrategy.discard
        case "services" :: xs =>
          MergeStrategy.filterDistinctLines
        case ("spring.schemas" :: Nil) | ("spring.handlers" :: Nil) =>
          MergeStrategy.filterDistinctLines
        case _ => MergeStrategy.deduplicate
      }
    case "module-info.class" => MergeStrategy.discard
    case _ => MergeStrategy.deduplicate
  }
)


import Dependencies._

ThisBuild / scalaVersion := "2.13.14"
ThisBuild / version := "1.1.0-SNAPSHOT"
ThisBuild / organization := "org.calinburloiu.music"

// # Projects

lazy val root = (project in file("."))
  .aggregate(
    app,
    cli,
    ui,
    sync,
    core,
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
    core,
    intonation,
    format,
    scMidi,
    sync,
    tuner,
    ui,
  )
  .settings(
    name := "microtonalist-app",
    commonSettings,
    assemblySettings,
    assembly / mainClass := Some("org.calinburloiu.music.microtonalist.MicrotonalistApp"),
    libraryDependencies ++= Seq(
      coreMidi4j,
      enumeratum,
      ficus,
      guava,
      playJson,
      scalaMock % Test,
    ),
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
  )

lazy val ui = (project in file("ui"))
  .dependsOn(
    tuner,
  )
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-ui",
    commonSettings,
  )

lazy val sync = (project in file("sync"))
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-sync",
    commonSettings,
    libraryDependencies ++= Seq(
      guava,
    ),
  )

lazy val core = (project in file("core"))
  .dependsOn(
    intonation,
    scMidi,
    sync
  )
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-core",
    commonSettings,
    libraryDependencies ++= Seq(
      enumeratum,
    ),
  )

lazy val tuner = (project in file("tuner"))
  .dependsOn(
    core,
    scMidi,
    sync,
  )
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-tuner",
    commonSettings,
    libraryDependencies ++= Seq(
      enumeratum,
    ),
  )

lazy val format = (project in file("format"))
  .dependsOn(
    core,
  )
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-format",
    commonSettings,
    libraryDependencies ++= Seq(
      playJson,
      scalaMock % Test,
    ),
  )

lazy val intonation = (project in file("intonation"))
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "intonation",
    commonSettings,
    libraryDependencies ++= Seq(
      guava,
    ),
  )

lazy val scMidi = (project in file("sc-midi"))
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "sc-midi",
    commonSettings,
    libraryDependencies ++= Seq(
      coreMidi4j,
    ),
  )

lazy val experiments = (project in file("experiments"))
  .dependsOn(
    core,
    intonation,
    format,
    scMidi,
    sync,
    tuner,
  )
  .settings(
    name := "microtonalist-app",
    commonSettings,
    assemblySettings,
    assembly / mainClass := Some("org.calinburloiu.music.microtonalist.MicrotonalistApp"),
    libraryDependencies ++= Seq(
      coreMidi4j,
      enumeratum,
      ficus,
      guava,
      playJson,
      scalaMock % Test,
    ),
  )

// # Dependencies

lazy val commonDependencies = Seq(
  logback,
  scalaLogging,
  scalaTest % Test,
)

// # Settings

lazy val compilerOptions = Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-encoding", "utf8",
  "-language:implicitConversions",
  "-language:postfixOps",
)

lazy val commonSettings = Seq(
  javacOptions ++= Seq(
    "-source", "17", "-target", "17",
  ),
  scalacOptions ++= compilerOptions,
  resolvers += "Local Maven Repository" at "file://"+Path.userHome.absolutePath+"/.m2/repository",
  libraryDependencies ++= commonDependencies,
)

lazy val assemblySettings = Seq(
  assembly / assemblyJarName := name.value + ".jar",
  assembly / assemblyMergeStrategy := {
    case x if Assembly.isConfigFile(x) =>
      MergeStrategy.concat
    case PathList(ps @ _*) if Assembly.isReadme(ps.last) || Assembly.isLicenseFile(ps.last) =>
      MergeStrategy.rename
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


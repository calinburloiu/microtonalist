import Dependencies.*

ThisBuild / scalaVersion := "3.6.3"
ThisBuild / version := "1.3.0-SNAPSHOT"
ThisBuild / organization := "org.calinburloiu.music"

// # Projects

lazy val root = (project in file("."))
  .aggregate(
    app,
    businessync,
    cli,
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

lazy val common = (project in file("common"))
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-common",
    commonSettings,
    libraryDependencies ++= Seq(
      guava,
    ),
  )

lazy val businessync = (project in file("businessync"))
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-businessync",
    commonSettings,
    libraryDependencies ++= Seq(
      guava,
    ),
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


import Dependencies._

ThisBuild / scalaVersion := "2.13.7"
ThisBuild / version := "0.3.0-SNAPSHOT"
ThisBuild / organization := "org.calinburloiu.music"

// # Projects

lazy val root = (project in file("."))
  .aggregate(app)
  .disablePlugins(AssemblyPlugin)
  .settings(
    name := "microtonalist-root",
  )

lazy val app = (project in file("app"))
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

// # Dependencies

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


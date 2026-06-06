// Fixture for module_dir_map parsing. Mirrors the real build.sbt module declarations,
// focusing on the cases where the sbt module ID differs from the file() directory.

lazy val root = (project in file("."))
  .aggregate(app, scMidi)

lazy val app = (project in file("app"))
  .dependsOn(scMidi)

lazy val scMidi = (project in file("sc-midi"))
  .dependsOn(common)

lazy val appConfig = (project in file("config"))
  .dependsOn(common)

lazy val commonTestUtils = (project in file("common-test-utils"))

lazy val intonation = (project in file("intonation"))

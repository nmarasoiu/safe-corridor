ThisBuild / scalaVersion := "3.9.0"
ThisBuild / organization := "io.github.nmarasoiu"
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:all", "-release", "21")

val catsEffectVersion = "3.7.1"
val munitVersion = "1.3.6"

lazy val root = project
  .in(file("."))
  .aggregate(core, sim)
  .settings(name := "safe-corridor", publish / skip := true)

// The guarantee layer: pure domain and state machines, no effect library.
lazy val core = project
  .settings(
    name := "safe-corridor-core",
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % munitVersion % Test,
      "org.scalameta" %% "munit-scalacheck" % "1.3.1" % Test
    )
  )

// Deterministic simulation. TestControl (from the testkit) also drives Main, so the testkit is a compile dependency.
lazy val sim = project
  .dependsOn(core)
  .settings(
    name := "safe-corridor-sim",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % catsEffectVersion,
      "org.typelevel" %% "cats-effect-testkit" % catsEffectVersion,
      "org.scalameta" %% "munit" % munitVersion % Test,
      "org.typelevel" %% "munit-cats-effect" % "2.2.1" % Test
    ),
    run / fork := true,
    Test / fork := true,
    Test / javaOptions ++= sys.props.get("sim.seeds").map(n => s"-Dsim.seeds=$n").toList
  )

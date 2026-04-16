name := "CS553_2026"

version := "0.2.0"

// Course spec expects Scala 3.x for the core system.
scalaVersion := "3.3.3"

lazy val akkaVersion = "2.8.5"

libraryDependencies ++= Seq(
  // Akka classic runtime (required by CourseProject.MD mapping: node -> classic actor)
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
  "com.typesafe.akka" %% "akka-serialization-jackson" % akkaVersion,

  // We keep typed deps for existing examples/tests; the simulator core will use classic.
  "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-actor-testkit-typed" % akkaVersion % Test,

  // Config and JSON for graph artifacts + experiment reproducibility
  "com.typesafe" % "config" % "1.4.3",
  "com.lihaoyi" %% "upickle" % "4.0.2",

  // Logging
  "ch.qos.logback" % "logback-classic" % "1.5.6",

  // Tests
  "org.scalatest" %% "scalatest" % "3.2.19" % Test
)

scalacOptions ++= Seq(
  "-encoding",
  "UTF-8",
  "-deprecation",
  "-feature",
  "-unchecked"
)

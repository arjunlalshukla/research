name := "id-server-akka"

version := "0.1"

scalaVersion := "2.13.2"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor-typed" % "2.6.5",
  "com.typesafe.akka" %% "akka-remote" % "2.6.5",
  "com.typesafe.akka" %% "akka-http"   % "10.1.12",
  "com.typesafe.akka" %% "akka-stream" % "2.6.5",
  "com.typesafe.akka" %% "akka-actor-testkit-typed" % "2.6.5" % Test,
  "org.scalatest" %% "scalatest" % "3.1.1" % Test
)
name := "akka-basic-http"

version := "0.1"

scalaVersion := "2.13.2"

libraryDependencies ++= Seq(
  "org.apache.commons" % "commons-lang3" % "3.1",
  "com.google.guava" % "guava" % "29.0-jre",
  "com.typesafe.akka" %% "akka-actor-typed" % "2.6.5",
  "com.typesafe.akka" %% "akka-remote" % "2.6.5",
  "com.typesafe.akka" %% "akka-http"   % "10.1.12",
  "com.typesafe.akka" %% "akka-stream" % "2.6.5",
  "com.typesafe.akka" %% "akka-actor-testkit-typed" % "2.6.5" % Test,
  "io.spray" %%  "spray-json" % "1.3.5",
  "org.scalatest" %% "scalatest" % "3.1.1" % Test,
  "org.slf4j" % "slf4j-api" % "1.7.30"
)
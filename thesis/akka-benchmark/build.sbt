name := "akka-benchmark"

scalaVersion := "2.13.3"

name := "akka-benchmark"
version := "0.0.1"
val AkkaVersion = "2.6.11"
libraryDependencies += "com.typesafe.akka" %% "akka-actor" % AkkaVersion
libraryDependencies += "com.typesafe.akka" %% "akka-remote" % AkkaVersion
libraryDependencies += "com.typesafe.akka" %% "akka-cluster" % AkkaVersion
libraryDependencies += "com.typesafe.akka" %% "akka-distributed-data" % AkkaVersion
//libraryDependencies += "com.typesafe.akka" %% "akka-serialization-jackson" % AkkaVersion
libraryDependencies += "io.aeron" % "aeron-driver" % "1.32.0"
libraryDependencies += "io.aeron" % "aeron-client" % "1.32.0"
assemblyJarName in assembly := "akka-benchmark.jar"

//assemblyMergeStrategy in assembly := {
//  case PathList("module-info.class", xs @ _*) => MergeStrategy.discard
//  case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
//  case x => MergeStrategy.first
//}
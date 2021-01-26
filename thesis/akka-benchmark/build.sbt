name := "akka-benchmark"

scalaVersion := "2.13.3"

name := "akka-benchmark"
version := "0.0.1"
val AkkaVersion = "2.6.11"
libraryDependencies += "com.typesafe.akka" %% "akka-actor" % AkkaVersion
libraryDependencies += "com.typesafe.akka" %% "akka-remote" % AkkaVersion
libraryDependencies += "com.typesafe.akka" %% "akka-cluster" % AkkaVersion
libraryDependencies += "com.typesafe.akka" %% "akka-distributed-data" % AkkaVersion

assemblyJarName in assembly := "akka-benchmark.jar"
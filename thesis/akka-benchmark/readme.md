# Overview
This is the benchmark for Akka IoT. The project has 1 driver: Main. Run all the commands discussed here from the project root.

# Building
Execute this command to build the code:
```
$ sbt assembly
```

# Running Main
Main has 4 modes to run in, but you should only use 1: the "collector" mode.
```
$ java -cp target/scala-2.13/akka-benchmark.jar Main <host> <port> collector target/scala-2.13/akka-benchmark.jar <config>
```

Parameters:
- `host`: The hostname of this machine. This is how other nodes will contact it.
- `port`: The port you want the collector to use.
- `config`: The path to the file containing the list of clients and servers to start.


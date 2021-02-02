# Overview
This is the benchmark for Akka IoT. The project has 4 drivers: IoTMain, DataCenterMain, CollectorMain and PeriodicKiller. Run all the commands discussed here from the project root.

# Building
Execute this command to build the code:
```
$ sbt assembly
```

# Runtime Configuration
For messages sent between JVM processes, we can artificially drop and delay messages to simulate an unreliable network. This behavior along with logging is controlled using JVM properties. You can set these JVM properties for every driver, and they affect each the same way (but only that process):
- ```MIN_DELAY```: the lower bound on the delay we will send a message with in milliseconds, default is 0
- ```MAX_DELAY```: the upper bound on the delay we will send a message with in milliseconds, default is 0
- ```FAIL_PROB```: the probability a message will fail to send, default is 0.0, should be between 0.0 and 1.0
- ```LOGGING_ON```: turn logging on or off. "true" or "false", default is "true"
- ```BENCH_LOG_FILE```: the name of the file to output log messages to, leave unset for stdout
- ```APPEND_LOG```: "true" if you want to append to the log file, "false" if you want to truncate


Set any of the properties like shown below. Don't forget the ```-D``` in front of each property name.
```
java -cp target/scala-2.13/akka-benchmark.jar -DMIN_DELAY="50" [other properties] <main_class> <args...>
```

# Running IoTMain
To run IoTMain, use the following command from the project root:
```
$ java -cp target/scala-2.13/akka-benchmark.jar [props] IoTMain <iot_port> <interval> <change_interval_interval> <host_1> <port_1> [... <host_n> <port_n>
```

Parameters:
- ```iot_port```: The device will operate on the port you specify.
- ```interval```: The initial heartbeat interval in milliseconds.
- ```change_interval_interval```: The interval with which to increase the heartbeat interval by 1 millisecond.
- ```host_1```, ```port_1```: The host-port pairs are seeds for contacting the cluster. If you don't include at least one host-port pair, your device won't be able to contact the cluster. At this point in development, only you should provide one seed. Every server the device contacts will send heartbeats to the device: the device will contact every seed at once to establish a connection. This will result in multiple simultaneous connections, but the device actor is only meant to handle one at a time. In the event where the server a device is connected to becomes unresponsive, the device will send initiation messages (notifying the server of its preferred heartbeat interval) until it receives a heartbeat request.


# Running DataCenterMain
To run DataCenterMain, use the following command from the project root:
```
$ java -cp target/scala-2.13/akka-benchmark.jar [props] DataCenterMain <dc_port> <request_interval> [<host_1> <port_1> ... <host_n> <port_n>]
```

Parameters:
- ```dc_port```: The server will operate on the port you specify.
- ```request_interval```: The rate in milliseconds the server will send requests to each device in its charge.
- ```host_1```, ```port_1```: The host-port pairs are seeds for joining the cluster in the data center. If this is the first server you're defining in the cluster, don't include any host-port pairs. Servers will automatically detect the failure of devices via phi-accrual, and stop sending heartbeats when it does.


# Running CollectorMain
To run CollectorMain, use the following command from the project root:
```
$ java -cp target/scala-2.13/akka-benchmark.jar [props] CollectorMain <collector_port> <request_interval> <display_interval> <log_non_total> <host_1> <port_1> [... <host_n> <port_n>]
```

Parameters:
- ```collector_port```: The collector will operate on the port you specify.
- ```request_interval```: The rate in milliseconds the server will send requests to each server in the cluster.
- ```display_interval```: The rate at which the collector will display its total.
- ```log_non_total```: Log other stuff besides the total.
- ```host_1```, ```port_1```: The host-port pairs are the servers in the cluster the collector will send data requests to.


# Running PeriodicKiller
To run PeriodicKiller, use the following command from the project root:
```
$ java -cp target/scala-2.13/akka-benchmark.jar [props] PeriodicKiller <false | true <collector_port> <request_interval> <display_interval> <log_non_total>> <false | true <min_kill_delay> <max_kill_delay>> <host_1> <port_1> [... <host_n> <port_n>] "~dc" [<port_1> ... <port_n>] "~iot" <interval> [<port_1> ... <port_n>]
```

Parameters:
- If false, PeriodicKiller will not spawn a Collector. If true, pass use the first 4 arguments to CollectorMain.
- If false, PeriodicKiller will not kill processes. If true, pass the minimum delay and maximum delay the spawned DataCenterMain and IoTMain processes will be killed and restarted again.
- ```host_1```, ```port_1```: This list is passed as the seed nodes for IoTMain and DataCenterMain processes, and the list of seeds to contact for CollectorMain.
- ```~dc``` port list: The list of ports spawned DataCenterMain processes will use.
- ```interval```: The heartbeat interval for spawned IoTMain processes. Meaningless if the ```~iot``` port list is empty.
- ```~iot``` port list: The list of ports spawned IoTMain processes will use.


Because the command is so long, it may be advisable to put in a file. For each process PeriodicKiller spawns, its ```MIN_DELAY```, ```MAX_DELAY``` and ```FAIL_PROB``` properties are passed onto its child processes. Its child processes are automatically killed when it is.

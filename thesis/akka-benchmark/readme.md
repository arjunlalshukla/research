# Overview
This is the benchmark for Akka IoT. The project has 2 drivers: IoTMain and DataCenterMain. 

# IoTMain
To run IoTMain, use the following command from the project root:
```
$ sbt <props> "runMain IoTMain <iot_port> <interval> <second_interval> <change_interval_delay> <new_intervals_fail> <host_1> <port_1> [... <host_n> <port_n>"
```

Don't forget to include the quote marks.
Parameters:
- <iot_port>: The main will operate on the port you specify with <iot_port>.
- <interval>: The heartbeat interval in milliseconds
- <second_interval>: The main will change the interval to this after the time specified with <change_interval_delay>
- <new_intervals_fail>: A test parameter. Use "false"
- <host_1>, <port_1>: The host-port pairs are seeds for contacting the cluster. If you don't include at least one host-port pair, your device won't be able to contact the cluster. At this point in development, only you should provide one seed. Every server the device contacts will send heartbeats to the device: the device will contact every seed at once to establish a connection. This will result in multiple simultaneous connections, but the device actor is only meant to handle one at a time. In the event where the server a device is connected to becomes unresponsive, the device will send initiation messages (notifying the server of its preferred heartbeat interval) until it receives a heartbeat request.

For messages sent between JVM processes, we can artificially drop and delay messages to simulate an unreliable network.

In addition to the parameters, we use several Java system properties. All are optional:
- MIN_DELAY: the lower bound on the delay we will send a message with in milliseconds, default is 0
- MAX_DELAY: the upper bound on the delay we will send a message with in milliseconds, default is 0
- FAIL_PROB: the probability a message will fail to send, default is 0.0, should be between 0.0 and 1.0

To set a property, do so like you would normal Java system properties:
```
sbt -DMIN_DELAY="50" [other properties] "runMain IoTMain ..."
```

Don't forget to include the "-D" in front of the property name.


# DataCenterMain
To run IoTMain, use the following command from the project root:
```
$ sbt "runMain DataCenterMain <dc_port> <host_1> <port_1> ... <host_n> <port_n>"
```

Don't forget to include the quote marks. The main will operate on the port you specify with <dc_port>. The host-port pairs are seeds for joining the cluster in the data center. If this is the first server you're defining in the cluster, don't include any host-port pairs. Servers will automatically detect the failure of devices via phi-accrual, and stop sending heartbeats when it does.

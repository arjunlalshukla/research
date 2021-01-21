# Overview
This is the benchmark for Akka IoT. The project has 2 drivers: IoTMain and DataCenterMain. 

# IoTMain
To run IoTMain, use the following command from the project root:
```
$ sbt "runMain IoTMain <iot_port> <host_1> <port_1> ... <host_n> <port_n>"
```

Don't forget to include the quote marks. The main will operate on the port you specify with <iot_port>. The host-port pairs are seeds for contacting the cluster. If you don't include at least one host-port pair, your device won't be able to contact the cluster. At this point in development, only you should provide one seed. Every server the device contacts will send heartbeats to the device: the device will contact every seed at once to establish a connection. This will result in multiple simultaneous connections, but the device actor is only meant to handle one at a time. In the event where the server a device is connected to becomes unresponsive, the device will send initiation messages (notifying the server of its preferred heartbeat interval) until it receives a heartbeat request.

# DataCenterMain
To run IoTMain, use the following command from the project root:
```
$ sbt "runMain DataCenterMain <dc_port> <host_1> <port_1> ... <host_n> <port_n>"
```

Don't forget to include the quote marks. The main will operate on the port you specify with <dc_port>. The host-port pairs are seeds for joining the cluster in the data center. If this is the first server you're defining in the cluster, don't include any host-port pairs. Servers will automatically detect the failure of devices via phi-accrual, and stop sending heartbeats when it does.

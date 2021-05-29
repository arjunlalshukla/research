The benchmark for both Aurum and Akka runs with a configuration file of the following format (all time-related parameters are in milliseconds):
```
<clients per node> <print interval> <request interval> <drop probability> <minimum message delay> <maximum message delay> <request timeout>
<server minimum kill delay> <server maximum kill delay> <server minimum restart delay> <server maximum restart delay>
<client minimum kill delay> <client maximum kill delay> <client minimum restart delay> <client maximum restart delay>
<client | server> <host1> <port>
<client | server> <host2> <port>
...
```

Hosts must be unique in the configuration file.

Before running, make sure none of the hosts are currently running any processes.

```
$./kill <config file>
```

The `kill` script will go through every host in your config file and kill all related processes.

To compile Aurum's benchmark:
```
cd aurum; cargo build --release; cd ..
```

To run Aurum's benchmark: 
```
$./run <config file>
```

To compile Aurum's benchmark:
```
cd akka-benchmark; sbt assembly; cd ..
```

To run Akka's benchmark: 
```
$ java -cp <path to jar> Main <this machine's hostname> <port> collector <path to jar> <config file>
```

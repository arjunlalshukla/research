import Utils.{addressString, arjun, clusterName}
import akka.actor.{ActorSystem, Props}
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration.DurationInt

object IoTMain extends App {
  implicit  val loginContext = ArjunContext("DataCenterMain")
  val requiredArgs = 5
  if (args.length % 2 != requiredArgs % 2 || args.length < requiredArgs) {
    println(s"Your arguments are: ${args.map('"'+_+'"').mkString(",")}")
    println(
      "usage: IoTMain <iot_port> <interval> <change_interval_interval> " +
      "<host_1> <port_1> [... <host_n> <port_n>]"
    )
    sys.exit(0)
  }

  private[this] object IntervalHolder {
    var interval = Int.MaxValue
  }

  val host = "127.0.0.1"
  val port = args(0).toInt
  val interval = args(1).toInt
  IntervalHolder.interval = interval
  val changeIntervalInterval = args(2).toInt.millis
  val seeds = args.drop(requiredArgs-2).sliding(2,2).toSeq
    .map(seq => Node(seq(0), seq(1).toInt))

  val node = Node(host, port)
  val config = ConfigFactory.load(ConfigFactory.parseString(s"""
    akka {
      actor {
        provider = cluster
        allow-java-serialization = on
        warn-about-java-serializer-usage = off
      }
      remote {
        artery {
          canonical.hostname = $host
          canonical.port = $port
        }
      }
    }
  """))

  val system = ActorSystem(clusterName, config)
  val actor = system.actorOf(Props(
    new IoTDevice(seeds.toSet, node, interval)), "IoT-device")
  system.actorOf(Props(new IoTBusiness(node, actor)), "IoT-business")
  import system.dispatcher
  system.scheduler.scheduleWithFixedDelay(changeIntervalInterval, changeIntervalInterval) (() => {
    IntervalHolder.interval += 1
    actor ! NewInterval(IntervalHolder.interval)
  })
}

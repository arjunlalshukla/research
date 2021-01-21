import Utils.{addressString, arjun, clusterName}
import akka.actor.{ActorSystem, Props}
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration.DurationInt

object IoTMain extends App {
  sys.env.toSeq.sortBy(_._1).foreach(println)
  implicit  val loginContext = ArjunContext("DataCenterMain")
  val requiredArgs = 7
  if (args.length % 2 != requiredArgs % 2 || args.length < requiredArgs) {
    println(s"Your arguments are: ${args.map('"'+_+'"').mkString(",")}")
    println(
      "usage: IoTMain <iot_port> <interval> <second_interval> " +
      "<change_interval_delay> <new_intervals_fail> <host_1> <port_1> " +
      "[... <host_n> <port_n>"
    )
    sys.exit(0)
  }

  val host = "127.0.0.1"
  val port = args(0).toInt
  val interval = args(1).toInt
  val secondInterval = args(2).toInt
  val changeIntervalDelay = args(3).toInt.millis
  val newIntervalsFailToSend = args(4).toBoolean
  val seeds = args.drop(requiredArgs-2).sliding(2,2).toSeq
    .map(seq => Node(seq(0), seq(1).toInt))

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
    new IoTDevice(seeds.toSet, Node(host, port), interval, newIntervalsFailToSend)),
  "IoT-device")
  import system.dispatcher
  system.scheduler.scheduleOnce(changeIntervalDelay, actor, NewInterval(secondInterval))
}

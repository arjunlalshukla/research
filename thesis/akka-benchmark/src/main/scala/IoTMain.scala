import Utils.{arjun, clusterName, addressString}
import akka.actor.{ActorSystem, Props}
import com.typesafe.config.ConfigFactory

object IoTMain extends App {
  implicit  val loginContext = "DataCenterMain"
  val requiredArgs = 1
  if (args.length % 2 != requiredArgs % 2 || args.length < requiredArgs) {
    arjun(args.map('"'+_+'"').mkString(","))
    throw new IllegalArgumentException
  }

  val seeds = args.drop(requiredArgs).sliding(2,2).toSeq
    .map(seq => Node(seq(0), seq(1).toInt))

  val host = "127.0.0.1"
  val port = args(0).toInt

  val config = ConfigFactory.load(ConfigFactory.parseString(s"""
    akka {
      actor {
        provider = cluster
        allow-java-serialization = on
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
  system.actorOf(Props(
    new IoTDevice(seeds.toSet, Node(host, port), 3000)),
  "IoT-device")
}

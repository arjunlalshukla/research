import Utils.{arjun, clusterName}
import akka.actor.{ActorSystem, Props}
import com.typesafe.config.ConfigFactory

object IoTMain extends App {
  val requiredArgs = 1
  if (args.length % 2 != requiredArgs % 2 || args.length < requiredArgs) {
    arjun(args.map('"'+_+'"').mkString(","))
    throw new IllegalArgumentException
  }

  val seeds = args.drop(requiredArgs).sliding(2,2).toSeq
    .map(seq => s"akka://$clusterName@${seq(0)}:${seq(1)}/user/bench-member")

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
    new IoTDevice(seeds.toSet.map((s: String) => system.actorSelection(s)), Node(host, port), 5000)),
  "IoT-device")
}

import Utils.{addressString, arjun, clusterName}
import akka.actor.{ActorSystem, Props}
import com.typesafe.config.ConfigFactory

object CollectorMain extends App {
  implicit  val loginContext = ArjunContext("CollectorMain")
  val requiredArgs = 3
  if (args.length % 2 != requiredArgs % 2 || args.length < requiredArgs) {
    arjun(args.map('"'+_+'"').mkString(","))
    throw new IllegalArgumentException
  }

  val host = "127.0.0.1"
  val akkaPort = args(0).toInt
  val reqInt = args(1).toInt
  val dispInt = args(2).toInt

  val node = Node(host, akkaPort)

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
          canonical.port = $akkaPort
        }
      }
    }
  """))

  val system = ActorSystem("Collector", config)
  val nodes = args.drop(requiredArgs).sliding(2,2).toSet
    .map { (seq: Array[String]) =>
      system.actorSelection(
        addressString(Node(seq(0), seq(1).toInt), "/user/bench-business")
      )
    }
  system.actorOf(Props(new Collector(nodes, node, dispInt, reqInt)), "collector")
}

import Utils.{arjun, clusterName}
import akka.actor.{ActorSystem, Props}
import com.typesafe.config.ConfigFactory

object DataCenterMain extends App {
  implicit  val loginContext = ArjunContext("DataCenterMain")
  val requiredArgs = 2
  if (args.length % 2 != requiredArgs % 2 || args.length < requiredArgs) {
    arjun(args.map('"'+_+'"').mkString(","))
    throw new IllegalArgumentException
  }

  val host = "127.0.0.1"
  val akkaPort = args(0).toInt
  val reqInt = args(1).toInt

  val node = Node(host, akkaPort)
  val seeds = args.drop(requiredArgs).sliding(2,2).toSeq match {
    case Seq() => Seq(s"akka://$clusterName@127.0.0.1:$akkaPort")
    case a => a.map(seq => s"akka://$clusterName@${seq(0)}:${seq(1)}")
  }

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
      cluster {
        seed-nodes = [${seeds.map('"'+_+'"').mkString(",")}]
        downing-provider-class = "akka.cluster.sbr.SplitBrainResolverProvider"
      }
    }
  """))

  val system = ActorSystem(clusterName, config)
  val dcmember = system
    .actorOf(Props(new DataCenterMember(Node(host, akkaPort))), "bench-member")
  system.actorOf(Props(new DataCenterBusiness(dcmember, node, reqInt)), "bench-business")
}

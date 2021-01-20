import Utils.{arjun, clusterName}
import akka.actor.{ActorSystem, Props}
import com.typesafe.config.ConfigFactory

object DataCenterMain extends App {
  val requiredArgs = 1
  if (args.length % 2 != requiredArgs % 2 || args.length < requiredArgs) {
    arjun(args.map('"'+_+'"').mkString(","))
    throw new IllegalArgumentException
  }

  val host = "127.0.0.1"
  val akkaPort = args(0).toInt

  val seeds = args.drop(requiredArgs).sliding(2,2).toSeq match {
    case Seq() => Seq(s"akka://$clusterName@127.0.0.1:$akkaPort")
    case a => a.map(seq => s"akka://$clusterName@${seq(0)}:${seq(1)}")
  }

  val config = ConfigFactory.load(ConfigFactory.parseString(s"""
    akka {
      actor {
        provider = cluster
        allow-java-serialization = on
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

  ActorSystem(clusterName, config)
    .actorOf(Props[DataCenterMember](), "bench-member")
}

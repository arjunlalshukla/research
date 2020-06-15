import akka.actor.{Address, AddressFromURIString}
import akka.actor.typed.ActorSystem
import akka.cluster.typed._
import com.typesafe.config.ConfigFactory

object RunCluster extends App {

  val requiredArgs = 2

  if (args.length % 2 != requiredArgs % 2 || args.length < requiredArgs) {
    throw new IllegalArgumentException
  }

  val clusterName = "IdCluster"
  val httpPort = args(0).toInt
  val akkaPort = args(1).toInt
  val seeds = args.drop(requiredArgs).sliding(2,2).toSeq match {
    case Seq() => Seq(s"akka://$clusterName@127.0.0.1:$akkaPort")
    case a => a.map(seq => s"akka://$clusterName@${seq(0)}:${seq(1)}")
  }

  val config = ConfigFactory.load(ConfigFactory.parseString(s"""
    akka {
      actor {
        provider = cluster
      }
      remote {
        artery {
          canonical.hostname = "127.0.0.1"
          canonical.port = $akkaPort
        }
      }
      cluster {
        seed-nodes = [${seeds.map('"'+_+'"').mkString(",")}]
        downing-provider-class = "akka.cluster.sbr.SplitBrainResolverProvider"
      }
    }
  """))

  val system = ActorSystem(IdServer(clusterName+"-actor", httpPort), clusterName, config)
}

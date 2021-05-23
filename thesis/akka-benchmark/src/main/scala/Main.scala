import akka.actor.{ActorSystem, Props}
import Utils.clusterName
import com.typesafe.config.ConfigFactory

import java.io.File
import scala.collection.mutable.ArrayBuffer
import scala.io.Source

object Main {
  def main(args: Array[String]): Unit = {
    val iter = args.iterator
    val node = Node(iter.next(), iter.next().toInt)
    val mode = iter.next()

    mode match {
      case "server" => server(iter, node)
      case "client" => client(iter, node)
      case "killer" => killer(iter, node)
      case "collector" => collector(iter, node)
    }
  }

  def server(args: Iterator[String], node: Node) = {
    val reqInt = args.next().toInt
    val seeds = args.sliding(2,2).toSeq match {
      case Seq() => Seq(s"akka://$clusterName@${node.host}:${node.port}")
      case a => a.map(seq => s"akka://$clusterName@${seq(0)}:${seq(1)}")
    }

    val config = ConfigFactory.load(ConfigFactory.parseString(s"""
      akka {
        actor {
          provider = cluster
          allow-java-serialization = on
          warn-about-java-serializer-usage = off
          #serializers {
          #  jackson-cbor = "akka.serialization.jackson.JacksonCborSerializer"
          #}
          #serialization-bindings {
          #  "MyCbor" = jackson-cbor
          #}
        }
        remote {
          artery {
            transport = aeron-udp
            canonical.hostname = ${node.host}
            canonical.port = ${node.port}
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
      .actorOf(Props(new DataCenterMember(node)), "bench-member")
    system.actorOf(Props(new DataCenterBusiness(dcmember, node, reqInt)), "bench-business")
  }

  def client(args: Iterator[String], node: Node) = {
    val interval = args.next().toInt
    val seeds = args.sliding(2,2).toSeq
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
            transport = aeron-udp
            canonical.hostname = ${node.host}
            canonical.port = ${node.port}
          }
        }
      }
    """))

    val system = ActorSystem(clusterName, config)
    val actor = system.actorOf(Props(
      new IoTDevice(seeds.toSet, node, interval)), "IoT-device")
    system.actorOf(Props(new IoTBusiness(node, actor)), "IoT-business")
  }

  def killer(args: Iterator[String], node: Node) = {
    val jar = args.next()
    val cmd = Array("java", "-cp", jar, "Main").appendedAll(args)
    val pb = new ProcessBuilder(cmd :_*)
    val logFile = new File(s"./log/${node.host}-${node.port}.log")
    pb.redirectError(logFile)
    pb.redirectOutput(logFile)
    pb.start().waitFor()
  }

  def collector(args: Iterator[String], node: Node) = {
    val config = ConfigFactory.load(ConfigFactory.parseString(s"""
      akka {
        actor {
          provider = cluster
          allow-java-serialization = on
          warn-about-java-serializer-usage = off
        }
        remote {
          artery {
            canonical.hostname = ${node.host}
            canonical.port = ${node.port}
          }
        }
      }
    """))
    val jar = args.next()
    val file = Source.fromFile(args.next())
    val lines = file.getLines()
    val first = lines.next().split("\\s+")
    val dispInt = first(0).toInt
    val reqInt = first(1).toInt
    var servers = ArrayBuffer[Node]()
    var clients = ArrayBuffer[Node]()
    lines.map(_.split("\\s+")).foreach { line =>
      line(0) match {
        case "client" =>
          clients += Node(line(1), line(2).toInt)
        case "server" =>
          servers += Node(line(1), line(2).toInt)
      }
    }

    val system = ActorSystem("Collector", config)
    system.actorOf(Props(
      new Collector(servers.toSeq, clients.toSeq, jar, node, dispInt, reqInt, true)), "collector")
  }
}

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
    Utils.exceptions = args.sliding(2,2).toSeq match {
      case Seq() => Set(node)
      case a => a.map(seq => Node(seq(0), seq(1).toInt)).toSet
    }
    val seeds = Utils.exceptions.map(n => s"akka://$clusterName@${n.host}:${n.port}")

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
            #transport = aeron-udp
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
            #transport = aeron-udp
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
    val num_procs = args.next().toInt
    val min_kill = args.next().toInt
    val max_kill = args.next().toInt
    val min_restart = args.next().toInt
    val max_restart = args.next().toInt
    val argsArray = args.toArray
    val cmds = (node.port + 1).to(node.port + num_procs).map { port =>
      val cmd = Array("java", "-cp", jar, s"-DFAIL_PROB=${Utils.fail_prob}", "Main", node.host, s"$port").appendedAll(argsArray)
      (port, cmd)
    }
    .toMap
    val config = ConfigFactory.load(ConfigFactory.parseString(s"""
      akka {
        actor {
          provider = cluster
          allow-java-serialization = on
          warn-about-java-serializer-usage = off
        }
        remote {
          artery {
            #transport = aeron-udp
            canonical.hostname = ${node.host}
            canonical.port = ${node.port}
          }
        }
      }
    """))

    val system = ActorSystem(clusterName, config)
    system.actorOf(Props(new PeriodicKiller(
      min_kill,
      max_kill,
      min_restart,
      max_restart,
      cmds,
      node
    )), "killer")
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
            #transport = aeron-udp
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
    val clientsPerNode = first(0).toInt
    val dispInt = first(1).toInt
    val reqInt = first(2).toInt
    val fail_prob = first(3).toDouble
    val report_timeout = first(6).toInt
    val second = lines.next().split("\\s+")
    val server_min_kill = second(0).toInt
    val server_max_kill = second(1).toInt
    val server_min_restart = second(2).toInt
    val server_max_restart = second(3).toInt
    val third = lines.next().split("\\s+")
    val client_min_kill = third(0).toInt
    val client_max_kill = third(1).toInt
    val client_min_restart = third(2).toInt
    val client_max_restart = third(3).toInt
    var servers = ArrayBuffer[Node]()
    var clients = ArrayBuffer[Node]()
    lines.map(_.split("\\s+")).foreach { line =>
      line(0) match {
        case "client" =>
          clients += Node(line(1), line(2).toInt)
        case "server" =>
          servers += Node(line(1), line(2).toInt)
        case _ if line(0).charAt(0) == '#' =>
          println(s"Skipping $line")
      }
    }

    val system = ActorSystem(clusterName, config)
    system.actorOf(Props(new Collector(
      servers.toSeq, 
      clients.toSeq, 
      jar, 
      node, 
      dispInt, 
      reqInt, 
      true, 
      clientsPerNode,
      fail_prob,
      report_timeout,
      server_min_kill,
      server_max_kill,
      server_min_restart,
      server_max_restart,
      client_min_kill,
      client_max_kill,
      client_min_restart,
      client_max_restart,
    )), "collector")
  }
}

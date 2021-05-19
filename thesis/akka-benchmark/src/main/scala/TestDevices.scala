import Utils.{arjun, clusterName}
import akka.actor.{Actor, ActorRef, ActorSelection, ActorSystem, Props}
import akka.cluster.Member
import com.typesafe.config.ConfigFactory

import scala.collection.mutable.{HashSet, TreeMap}
import scala.collection.immutable.TreeSet
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.Await

case class TestServer(
  node: ActorSystem,
  var charges: TreeSet[Int],
  actor: ActorRef,
  var view: TreeSet[Int],
  var devices: Map[ActorSelection, HeartbeatInterval]
)
case class TestClient(node: ActorSystem, var manager: Option[Int], actor: ActorRef)

class ConvergenceType
case object ClusterType extends ConvergenceType
case object DevicesType extends ConvergenceType

case class ClientData()
case class KillServer(port: Int)
case class KillClient(port: Int)
case class SpawnServer(port: Int, seeds: Seq[Int])
case class SpawnClient(port: Int, interval: Int, seeds: Seq[Int])
case class WaitForConvergence(ct: ConvergenceType)
case object Done

class TestDevices extends Actor {
  implicit val logContext = ArjunContext("TestDevices")
  val self_as = context.actorSelection(self.path)

  var servers = TreeMap[Int, TestServer]()
  var clients = TreeMap[Int, TestClient]()

  var waiting: Option[ConvergenceType] = None
  var queue = ArrayBuffer[Any]()
  var convergence = TreeSet[Int]()

  def receive: Receive = {
    case Devices(port, charges, cluster_members, devices) => {
      val server = servers(port)
      server.charges = TreeSet.from(charges.map(_.anchorPath.address.port.get))
      server.view = TreeSet.from(cluster_members.map(_.address.port.get))
      server.devices = devices
      println(s"$port CHARGES - ${server.charges}")
      println(s"$port MEMBERS - ${server.view}")
      check_convergence()
    }
    case Manager(port, manager) => {
      val client = clients(port)
      client.manager = manager.map(_.port)
      println(s"$port MANAGER - ${client.manager}")
      check_convergence()
    }
    case KillServer(port) =>{
      if (waiting.isDefined) {
        queue.addOne(KillServer(port))
      } else {
        kill_server(port)
      }
    }
    case KillClient(port) => {
      if (waiting.isDefined) {
        queue.addOne(KillClient(port))
      } else {
        kill_client(port)
      }
    }
    case SpawnServer(port, seeds) => {
      if (waiting.isDefined) {
        queue.addOne(SpawnServer(port, seeds))
      } else {
        spawn_server(port, seeds)
      }
    }
    case SpawnClient(port, interval, seeds) => {
      if (waiting.isDefined) {
        queue.addOne(SpawnClient(port, interval, seeds))
      } else {
        spawn_client(port, interval, seeds)
      }
    }
    case WaitForConvergence(t) => {
      if (waiting.isDefined) {
        queue.addOne(WaitForConvergence(t))
      } else {
        wait_for_convergence(t)
      }
    }
    case Done => {
      if (waiting.isDefined) {
        queue.addOne(Done)
      } else {
        println("Done!")
      }
    }
    case a => arjun(s"Unhandled message $a")
  }

  def wait_for_convergence(t: ConvergenceType) = {
    t match {
      case ClusterType => {
        if (!check_cluster_convergence()) {
          println("Waiting for CLUSTER CONVERGENCE");
          waiting = Some(ClusterType);
          queue.clear();
        } else {
          println("CLUSTER CONVERGENCE already reached")
        }
      }
      case DevicesType => {
        if (!check_device_convergence()) {
          println("Waiting for DEVICES CONVERGENCE");
          waiting = Some(DevicesType);
          queue.clear();
        } else {
          println("DEVICES CONVERGENCE already reached")
        }
      }
    }
  }

  def spawn_client(port: Int, interval: Int, seeds: Seq[Int]) = {
    val host = "127.0.0.1"
    val sockets = seeds.map(Node(host, _)).toSet

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
    val actor = system.actorOf(Props(new IoTDevice(sockets, node, interval)), "IoT-device")
    actor ! SubscribeDevices(self_as)
    clients(port) = TestClient(system, None, actor)
  }

  def spawn_server(port: Int, seed_ports: Seq[Int]) = {
    val host = "127.0.0.1"
    val node = Node(host, port)
    val seeds = if (seed_ports.isEmpty) {
      Seq(s"akka://$clusterName@$host:$port")
    } else {
      seed_ports.map(p => s"akka://$clusterName@$host:$p")
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
            canonical.port = $port
          }
        }
        cluster {
          seed-nodes = [${seeds.map('"'+_+'"').mkString(",")}]
          downing-provider-class = "akka.cluster.sbr.SplitBrainResolverProvider"
        }
      }
    """))

    val system = ActorSystem(clusterName, config)
    val actor = system.actorOf(Props(new DataCenterMember(node)), "bench-member")
    val svr = TestServer(system, TreeSet.empty, actor, TreeSet.empty, Map.empty)
    actor ! SubscribeDevices(self_as)
    servers(port) = svr
    convergence += port
  }

  def kill_client(port: Int) = {
    println(s"KILLING client on port $port");
    val client = clients.remove(port).get
    Await.ready(client.node.terminate(), Duration.Inf)
  }

  def kill_server(port: Int) = {
    println(s"KILLING server on port $port")
    val svr = servers.remove(port).get
    Await.ready(svr.node.terminate(), Duration.Inf)
    convergence -= port
  }

  def check_cluster_convergence(): Boolean = {
    servers.forall(tst => tst._2.view == convergence)
  }

  def check_device_convergence(): Boolean = {
    val clients_encountered = HashSet[Int]()
    servers.foreach { case (port, svr) =>
      svr.charges.foreach { c =>
        val exclusive = clients_encountered.add(c)
        val is_manager = clients(c).manager.contains(port)
        if (!exclusive || !is_manager) {
          return false
        }
      }
    }
    val clients_valid = clients.forall { case (port, client) =>
      clients_encountered.contains(port) && client.manager.exists(servers.contains(_))
    }
    val crdt_converged = servers.values.headOption.forall(first => servers.values.drop(1).forall(_.devices == first.devices))
    clients_valid && crdt_converged
  }

  def check_convergence() = {
    waiting.foreach { mode =>
      val converged = mode match {
        case ClusterType => {
          if (check_cluster_convergence()) {
            println("CLUSTER CONVERGENCE reached")
            true
          } else {
            false
          }
        }
        case DevicesType => {
          if (check_device_convergence()) {
            var s = "DEVICE CONVERGENCE reached\n"
            servers.foreach { case (port, svr) =>
              s += s"$port CHARGES - [${svr.charges.mkString(", ")}]\n"
            }
            clients.foreach { case (port, client) =>
              s += s"$port MANAGER - ${client.manager}\n"
            }
            println(s)
            true
          } else {
            false
          }
        }
      }
      if (converged) {
        waiting = None
        queue.foreach(context.self ! _)
        queue.clear()
      }
    }
  }
}

object RunDevicesTest extends App {
  val config = ConfigFactory.load(ConfigFactory.parseString(s"""
      akka {
        actor {
          provider = cluster
          allow-java-serialization = on
          warn-about-java-serializer-usage = off
        }
        remote {
          artery {
            canonical.hostname = 127.0.0.1
            canonical.port = 3000
          }
        }
      }
    """))
  val events = Seq(
    SpawnServer(3001, Seq()),
    SpawnServer(3002, Seq(3001)),
    SpawnServer(3003, Seq(3001)),
    WaitForConvergence(ClusterType),
    SpawnClient(4001, 300, Seq(3001)),
//    SpawnClient(4002, 300, Seq(3001)),
//    SpawnClient(4003, 300, Seq(3001)),
//    SpawnClient(4004, 300, Seq(3001)),
//    SpawnClient(4005, 300, Seq(3001)),
//    SpawnClient(4006, 300, Seq(3001)),
//    SpawnClient(4007, 300, Seq(3001)),
//    SpawnClient(4008, 300, Seq(3001)),
//    SpawnClient(4009, 300, Seq(3001)),
//    SpawnClient(4010, 300, Seq(3001)),
//    SpawnClient(4011, 300, Seq(3001)),
//    SpawnClient(4012, 300, Seq(3001)),
    WaitForConvergence(DevicesType),
//    KillClient(4003),
//    KillClient(4004),
//    WaitForConvergence(DevicesType),
//    KillServer(3002),
//    WaitForConvergence(ClusterType),
//    WaitForConvergence(DevicesType),
    Done,
  )
  val system = ActorSystem("Coordinator", config)
  val actor = system.actorOf(Props(new TestDevices()), "test-devices-actor")
  events.foreach(actor ! _)
}
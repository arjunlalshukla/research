import Utils.{addressString, arjun, toNode, unreliableSelection}
import akka.actor.{Actor, ActorRef, ActorSelection}

import java.io.File
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import scala.collection.mutable
import scala.concurrent.duration.DurationInt

final class Collector(
  val svrs: Seq[Node],
  val clis: Seq[Node],
  val jar: String,
  val id: Node,
  val displayInterval: Int,
  val reqReportInterval: Int,
  val logNonTotal: Boolean,
  val clientsPerNode: Int
) extends Actor {
  implicit val logContext = ArjunContext("Collector")
  arjun(s"My path is ${context.self.path.toString}")
  import context.dispatcher
  // Tracks requests received per instance. We need to use actor references
  // because the totals are not monotonic. They are kept in memory and are reset
  // to 0 if the node crashes. The failure transparency of actor selections
  // works against us here.
  val servers = svrs.map { node =>
    val n = Node(node.host, node.port + 1)
    val s = context.actorSelection(addressString(n, "/user/bench-business"))
    println(s"Has server: $s")
    s
  }
  val clients = clis.map { node =>
    val n = Node(node.host, node.port + 1)
    val s = context.actorSelection(addressString(n, "/user/IoT-business"))
    println(s"Has client: $s")
    s
  }
  svrs.foreach(ssh_cmd(true, _))
  clis.foreach(ssh_cmd(false, _))
  var totals = mutable.Map.empty[ActorSelection, Map[ActorSelection, Long]].withDefaultValue(Map.empty)
  val started = LocalDateTime.now()
  var lastPrint = 0L

  private[this] case object Display

  self ! Tick
  self ! Display

  def ssh_cmd(is_svr: Boolean, node: Node) = {
    val dir = System.getProperty("user.dir")
    val f = if (is_svr) "1 server 200" else s"$clientsPerNode client 1000"
    val server_list = svrs.map(node => s" ${node.host} ${node.port + 1} ").mkString(" ")
    val cmd = Array("ssh", node.host, s"""
      |cd $dir;
      |java -cp $jar Main ${node.host} ${node.port} killer $jar $f $server_list
      |""".stripMargin.replaceAll("\n", " "))
    println(s"running command ${cmd.map('"'+_+'"').mkString(" ")}")
    val pb = new ProcessBuilder(cmd :_*)
    pb.inheritIO()
    pb.start()
  }

  def receive: Receive = {
    case DCReport(from, toAdd) => {
      totals.put(from, toAdd)
    }
    case Tick =>  {
      servers.foreach(_ ! ReqReport(self))
      context.system.scheduler
        .scheduleOnce(displayInterval.millis, self, Tick)
    }
    case Display => {
      var total = 0L
      totals.foreach { member =>
        member._2.foreach(total += _._2)
      }
      val elapsed = ChronoUnit.MILLIS.between(started, LocalDateTime.now())
      val all = totals.map(_._2.values.sum).sum
      val since = all - lastPrint
      println(s"Elapsed: $elapsed; Since Last: $since")
      lastPrint = all
      context.system.scheduler
        .scheduleOnce(displayInterval.millis, self, Display)
    }
    case a => arjun(s"Unhandled message $a")
  }
}

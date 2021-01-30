import Utils.{addressString, arjun, fail_prob, max_delay, min_delay}
import akka.actor.{ActorSystem, Props}
import com.typesafe.config.ConfigFactory

import java.io.File
import scala.concurrent.duration.DurationInt

object PeriodicKiller extends App {
  implicit val logContext = ArjunContext("PeriodicKiller")
  var iter = args.iterator
  val host = "127.0.0.1"
  val collecting = iter.next.toBoolean
  val akkaPort = if (collecting) {
    iter.next().toIntOption.filter(_ > 0).get
  } else { 0 }
  val reqInt = if (collecting) {
    iter.next().toIntOption.filter(_ >= 0).get
  } else { 0 }
  val dispInt = if (collecting) {
    iter.next().toIntOption.filter(_ > 0).get
  } else { 0 }
  val logNonTotal = if (collecting) {
    iter.next().toBoolean
  } else { false }
  val killing = iter.next().toBoolean
  val min_kill_delay = if (killing) {
    iter.next().toIntOption.filter(_ > 0).get
  } else { 0 }
  val max_kill_delay = if (killing) {
    iter.next().toIntOption.filter(_ > min_kill_delay).get
  } else { 0 }
  val seeds = {
    val tup = iter.span(_ != "~dc")
    iter = tup._2
    tup._1.toSeq
  }
  arjun(s"Seeds: $seeds")
  assert(iter.next() == "~dc")
  val dc_ports = {
    val tup = iter.span(_ != "~iot")
    iter = tup._2
    tup._1.map(_.toIntOption.filter(_ >= 0).get).toSeq
  }
  assert(iter.next() == "~iot")
  val hb_int = iter.next().toInt // Option.filter(_ > 0).get
  val iot_ports = iter.map(_.toIntOption.filter(_ >= 0).get).toSeq

  val system = ActorSystem("PeriodicKiller")
  import system.dispatcher

  val collector = Array(
    "java", "-cp", "target/scala-2.13/akka-benchmark.jar",
    "-DFAIL_PROB="+fail_prob, "-DMIN_DELAY="+min_delay,
    "-DMAX_DELAY="+max_delay, "-DAPPEND_LOG=false",
    "CollectorMain", akkaPort.toString, reqInt.toString, dispInt.toString,
    logNonTotal.toString
  ).appendedAll(seeds)

  val dc_cmds = dc_ports.map(port => "log/dc-"+port -> Array(
    "java", "-cp", "target/scala-2.13/akka-benchmark.jar",
    "-DFAIL_PROB="+fail_prob, "-DMIN_DELAY="+min_delay,
    "-DMAX_DELAY="+max_delay, "-DAPPEND_LOG=false",
    "DataCenterMain", s"$port", "0"
  ).appendedAll(seeds)).toArray

  val iot_cmds = iot_ports.map(port => "log/iot-"+port -> Array(
    "java", "-cp", "target/scala-2.13/akka-benchmark.jar",
    "-DFAIL_PROB="+fail_prob, "-DMIN_DELAY="+min_delay,
    "-DMAX_DELAY="+max_delay, "-DAPPEND_LOG=false",
    "IoTMain", s"$port", s"$hb_int", (10*hb_int).toString
  ).appendedAll(seeds)).toArray

  if (collecting) {
    arjun(s"Starting collector: ${collector.mkString(" ")}")
    val pb = new ProcessBuilder(collector :_*)
    pb.inheritIO()
    pb.start()
  }
  dc_cmds.foreach(tup => start(tup._1, tup._2))
  iot_cmds.foreach(tup => start(tup._1, tup._2))

  def start(pid: String, cmd: Array[String]): Unit = {
    arjun(s"Starting $pid")
    val pb = new ProcessBuilder(cmd :_*)
    if (collecting) {
      pb.redirectError(new File(pid+".log"))
      pb.redirectOutput(new File(pid+".log"))
    } else {
      pb.inheritIO()
    }
    val proc = pb.start()
    if (killing) {
      val delay = Utils.rand_range(min_kill_delay, max_kill_delay)
      arjun(s"Killing $pid in $delay ms")
      system.scheduler.scheduleOnce(delay.millis) {
        arjun(s"SIGKILL-ing $pid")
        val killer = new ProcessBuilder("kill", "-SIGKILL", proc.pid().toString)
        killer.inheritIO()
        killer.start().waitFor()
        start(pid, cmd)
      }
    }
  }
}

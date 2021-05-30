import akka.actor.Actor
import scala.collection.mutable
import java.io.File
import scala.util.Random
import scala.concurrent.duration.DurationInt

case class Kill(port: Int)
case class Spawn(port: Int)

class PeriodicKiller(
  val min_kill: Int,
  val max_kill: Int,
  val min_restart: Int,
  val max_restart: Int,
  val cmds: Map[Int, Array[String]],
  val id: Node,
) extends Actor {
  implicit val ex = context.dispatcher
  val procs = mutable.Map.empty[Int, Process] 
  
  cmds.foreach { tup => 
    context.system.scheduler.scheduleOnce(
      Random.between(min_kill, max_kill+1).millis, self, Kill(tup._1)
    )
    procs.put(tup._1, start_proc(tup._2, tup._1))
  }

  def start_proc(cmd: Array[String], port: Int): Process = {
    println(s"$id starting child $port")
    val pb = new ProcessBuilder(cmd :_*)
    val logFile = new File(s"./log/${id.host}-$port.log")
    logFile.createNewFile()
    pb.redirectError(logFile)
    pb.redirectOutput(logFile)
    pb.start()
  }

  override def receive: Receive = {
    case Kill(port) => {
      assert(procs.contains(port))
      val proc = procs.remove(port).get
      new ProcessBuilder("kill", "-SIGKILL", proc.pid().toString)
        .inheritIO()
        .start()
        .waitFor()
      println(s"$id killed child $port")
      context.system.scheduler.scheduleOnce(
        Random.between(min_restart, max_restart+1).millis, self, Spawn(port)
      )
    }
    case Spawn(port) => {
      assert(!procs.contains(port))
      procs.put(port, start_proc(cmds(port), port))
      context.system.scheduler.scheduleOnce(
        Random.between(min_kill, max_kill+1).millis, self, Kill(port)
      )
    }
  }
}
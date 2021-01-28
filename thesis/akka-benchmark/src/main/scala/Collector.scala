import Utils.{arjun, toNode, unreliableSelection}
import akka.actor.{Actor, ActorRef, ActorSelection}

import scala.collection.mutable
import scala.concurrent.duration.DurationInt

final class Collector(
  nodes: Set[ActorSelection],
  val id: Node,
  val displayInterval: Int,
  val reqReportInterval: Int
) extends Actor {
  implicit val logContext = ArjunContext("Collector")
  arjun(s"My path is ${context.self.path.toString}")
  import context.dispatcher
  // Tracks requests received per instance. We need to use actor references
  // because the totals are not monotonic. They are kept in memory and are reset
  // to 0 if the node crashes. The failure transparency of actor selections
  // works against us here.
  var totals = Map.empty[(ActorRef, ActorSelection), Long].withDefaultValue(0L)
  val numReports = mutable.Map.empty.concat(nodes.map(_ -> 0L))
  val tickDelay = 2*Utils.max_delay
  val factor = 1.to(1000).sum

  private[this] case class Tick(dest: ActorSelection, seqNum: Long)
  private[this] case object Display

  nodes.foreach(self ! Tick(_, 0L))
  self ! Display

  def receive: Receive = {
    case DCReport(from, toAdd) => {
      val from_as = context.actorSelection(from.path)
      numReports(from_as) += 1L
      totals = totals ++ toAdd.map(tup => (from -> tup._1) -> tup._2)
      context.system.scheduler.scheduleOnce(reqReportInterval.millis) {
        arjun("Sending request for report number " +
          s"${numReports(from_as) + 1L} for $from_as")
        unreliableSelection(from_as, ReqReport(self))
      }
      context.system.scheduler.scheduleOnce(
        (tickDelay + reqReportInterval).millis,
        self, Tick(from_as, numReports(from_as))
      )
    }
    case Tick(dest, seqNum) =>  {
      if (seqNum == numReports(dest)) {
        arjun(s"Resending ReqReport ${numReports(dest)} to $dest")
        unreliableSelection(dest, ReqReport(self))
        context.system.scheduler.scheduleOnce(
          (tickDelay + reqReportInterval).millis,
          self, Tick(dest, seqNum)
        )
      }
    }
    case Display => {
      val serverDevice = totals
        .groupBy(tup => (context.actorSelection(tup._1._1.path), tup._1._2))
        .map { case (sd, total) => (sd, total.values.sum/factor)}
        .map(tup => (toNode(tup._1._1, id), toNode(tup._1._2, id), tup._2))
        .map { case (server, device, total) => s"$server <-> $device = $total" }
      arjun(s"Totals \n${serverDevice.mkString("\n")}")
      context.system.scheduler
        .scheduleOnce(displayInterval.millis, self, Display)
    }
    case a => arjun(s"Unhandled message $a")
  }
}

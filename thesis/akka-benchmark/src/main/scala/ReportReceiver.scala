import Utils.{arjun, unreliableSelection}
import akka.actor.{Actor, ActorRef, ActorSelection}

import scala.concurrent.duration.DurationInt

final class ReportReceiver(
  val dest: ActorSelection,
  val supervisor: ActorRef,
  val reqReportInterval: Int
) extends Actor {
  implicit val logContext = dest.anchorPath.address.host
    .zip(dest.anchorPath.address.port)
    .map(tup => ArjunContext(s"ReportReceiver ${tup._1}:${tup._2}"))
    .getOrElse(ArjunContext(s"ReportReceiver ${dest.anchorPath}"))
  arjun(s"My path is ${context.self.path.toString}")
  import context.dispatcher
  var numReports = 0L
  val tickDelay = 2*Utils.max_delay

  private[this] case class Tick(seqNum: Long)

  self ! Tick(0L)

  override def postStop(): Unit = {
    arjun("I'm dying!!!")
  }

  def receive: Receive = {
    case IoTReport(data) => {
      supervisor ! Increment(dest, data.sum)
      numReports += 1L
      arjun(s"Report number $numReports received")
      context.system.scheduler.scheduleOnce(reqReportInterval.millis) {
        arjun(s"Sending request for report number ${numReports + 1L}")
        unreliableSelection(dest, ReqReport(self))
      }
      context.system.scheduler.scheduleOnce(
        (tickDelay + reqReportInterval).millis, self, Tick(numReports)
      )
    }
    case Tick(seqNum) => {
      if (seqNum == numReports) {
        arjun(s"Resending ReqReport $seqNum")
        unreliableSelection(dest, ReqReport(self))
        context.system.scheduler.scheduleOnce(
          (tickDelay + reqReportInterval).millis,
          self, Tick(seqNum)
        )
      }
    }
    case a => arjun(s"Unhandled message $a")
  }
}

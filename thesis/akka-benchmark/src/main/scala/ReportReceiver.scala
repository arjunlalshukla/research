import Utils.{arjun, unreliableSelection}
import akka.actor.{Actor, ActorRef, ActorSelection}

import scala.concurrent.duration.DurationInt

final class ReportReceiver(
  val dest: ActorSelection,
  val supervisor: ActorRef,
  val req_timeout: Int,
  var recvd: Long,
) extends Actor {
  implicit val logContext = dest.anchorPath.address.host
    .zip(dest.anchorPath.address.port)
    .map(tup => ArjunContext(s"ReportReceiver ${tup._1}:${tup._2}"))
    .getOrElse(ArjunContext(s"ReportReceiver ${dest.anchorPath}"))
  arjun(s"My path is ${context.self.path.toString}")
  import context.dispatcher
  var clock: Long = recvd + 1L


  private[this] case class Tick(seqNum: Long)

  req(clock)

  override def postStop(): Unit = {
    arjun("I'm dying!!!")
  }

  def req(num: Long) = {
    unreliableSelection(dest, ReqDeviceReport(num, self))
    context.system.scheduler.scheduleOnce(
      req_timeout.millis, self, Tick(num)
    )
  }

  def receive: Receive = {
    case IoTReport(msg_clock, data) => {
      recvd += 1
      supervisor ! Increment(dest, data.sum, clock, recvd)
      if (msg_clock == clock) {
        clock += 1
        req(clock)
      }
    }
    case Tick(num) => {
      if (clock == num) {
        req(num)
      }
    }
    case a => arjun(s"Unhandled message $a")
  }
}

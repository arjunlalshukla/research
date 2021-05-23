import Utils.{arjun, unreliableSelection}
import akka.actor.{Actor, ActorRef, ActorSelection}

import scala.concurrent.duration.DurationInt

final class ReportReceiver(
  val dest: ActorSelection,
  val supervisor: ActorRef,
  val req_timeout: Int
) extends Actor {
  implicit val logContext = dest.anchorPath.address.host
    .zip(dest.anchorPath.address.port)
    .map(tup => ArjunContext(s"ReportReceiver ${tup._1}:${tup._2}"))
    .getOrElse(ArjunContext(s"ReportReceiver ${dest.anchorPath}"))
  arjun(s"My path is ${context.self.path.toString}")
  import context.dispatcher
  var sent = 1
  var recvd = 0L


  private[this] case class Tick(seqNum: Long)

  req(1)

  override def postStop(): Unit = {
    arjun("I'm dying!!!")
  }

  def req(num: Long) = {
    unreliableSelection(dest, ReqReport(self))
    context.system.scheduler.scheduleOnce(
      req_timeout.millis, self, Tick(num)
    )
  }

  def receive: Receive = {
    case IoTReport(data) => {
      recvd += 1
      supervisor ! Increment(dest, data.sum, sent, recvd)
      req(sent)
    }
    case Tick(num) => {
      if (recvd < num) {
        req(num)
      }
    }
    case a => arjun(s"Unhandled message $a")
  }
}

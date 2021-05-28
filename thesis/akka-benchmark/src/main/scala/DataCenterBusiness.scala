import Utils.{addressString, arjun, toNode}
import akka.actor.{Actor, ActorRef, ActorSelection, PoisonPill, Props}

import scala.concurrent.duration.DurationInt

final class DataCenterBusiness(
  val dcMember: ActorRef,
  val id: Node,
  val reqReportInterval: Int
) extends Actor {
  implicit val logContext = ArjunContext("DataCenterBusiness")
  import context.dispatcher
  arjun(s"My path is ${context.self.path.toString}")
  val self_as = context.actorSelection(self.path)

  var devices = Map.empty[ActorSelection, ActorRef]
  var totals = Map.empty[ActorSelection, Long].withDefaultValue(0L)
  var x = 0L
  dcMember ! SubscribeDevices(self_as)

  private case object Print

//  context.system.scheduler.scheduleOnce(
//    1000.millis, self, Print
//  )

  def receive: Receive = {
    case Devices(_, set, _, _) => {
      val newDevices = set.map { as =>
        val str = addressString(toNode(as, id), "/user/IoT-business")
        context.actorSelection(str)
      }
      val removed = devices.keySet.diff(newDevices)
      arjun(s"Devices removed: $removed")
      removed.map(devices(_)).foreach(_ ! PoisonPill)
      val toAdd = newDevices.diff(devices.keySet)
      arjun(s"Devices added: $toAdd")
      val added = toAdd.map { dest => dest ->
        context.actorOf(Props(new ReportReceiver(dest, self, reqReportInterval)))
      }
      devices = devices -- removed ++ added
    }
    case Increment(device, amount, sent, recvd) =>
      x += amount
      totals += device -> recvd
    case ReqReport(replyTo) => replyTo ! DCReport(self_as, totals, devices.size)
    case Print => {
      arjun(s"$totals, $x")
      context.system.scheduler.scheduleOnce(
        1000.millis, self, Print
      )
    }
    case a => arjun(s"Unhandled message $a")
  }
}

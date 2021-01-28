import Utils.{addressString, arjun, toNode}
import akka.actor.{Actor, ActorRef, ActorSelection, PoisonPill, Props}

final class DataCenterBusiness(
  val dcMember: ActorRef,
  val id: Node,
  val reqReportInterval: Int
) extends Actor {
  implicit val logContext = ArjunContext("DataCenterBusiness")
  arjun(s"My path is ${context.self.path.toString}")
  val self_as = context.actorSelection(self.path)

  var devices = Map.empty[ActorSelection, ActorRef]
  var totals = Map.empty[ActorSelection, Long].withDefaultValue(0L)

  dcMember ! SubscribeDevices(self)

  def update(ioTReport: IoTReport, from: ActorSelection): Increment = {
    Increment(from, ioTReport.data.sum)
  }

  def receive: Receive = {
    case Devices(set) => {
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
    case Increment(device, amount) =>
      totals += device -> (totals(device) + amount)
    case ReqReport(replyTo) => replyTo ! DCReport(self, totals)
    case a => arjun(s"Unhandled message $a")
  }
}

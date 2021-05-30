import Utils.{arjun, unreliableRef}
import akka.actor.{Actor, ActorRef}

final class IoTBusiness(val id: Node, val hbr: ActorRef) extends Actor {
  implicit val logContext = ArjunContext("IoTBusiness")
  arjun(s"My path is ${context.self.path.toString}")
  var reports = 0L
  def receive: Receive = {
    case ReqDeviceReport(clock, replyTo) => {
      reports += 1L
      //arjun(s"Reports processed: $reports")
      unreliableRef(replyTo, IoTReport(clock, 1L.to(1000).toArray.toSeq),
        toPrint = Option("one thousand longs"))
    }
    case a => arjun(s"Unhandled message $a")
  }
}

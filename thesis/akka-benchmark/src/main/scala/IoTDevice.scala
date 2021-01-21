import Utils.{addressString, arjun, unreliableRef}
import akka.actor.{Actor, ActorRef, ActorSelection}

import scala.concurrent.duration._

final class IoTDevice(
  var seeds: Set[Node],
  val id: Node,
  var interval: Int
) extends Actor {
  implicit val logContext = "IoTDevice"
  arjun(s"My path is ${context.self.path.toString}")
  import context.dispatcher
  val intervalStorageCapacity = 10
  val slowNetTolernce = 0.67
  val noHeartbeatTolerance = 2.0
  val tickInterval = 8000

  var clock = 0L
  var server: Option[Node] = None
  var heartbeatReqs = newStorage()

  val self_as = context.actorSelection(self.path)

  self ! NewInterval(interval)
  self ! Tick

  def receive: Receive = {
    case ReqHeartbeat(replyTo) => {
      val node = replyTo.path.address.host
        .zip(replyTo.path.address.port)
        .map(tup => Node(tup._1, tup._2))
        .getOrElse(id)
      if (!server.contains(node)) {
        newStorage()
        server = Option(node)
        seeds += node
      }
      arjun(s"Received ReqHeartbeat from $replyTo")
      unreliableRef(replyTo, Heartbeat(self), fail_prob = -1.0)
      val tooFast = heartbeatReqs.full && heartbeatReqs.mean < interval
      val tooSlow = heartbeatReqs.full && heartbeatReqs.mean*slowNetTolernce > interval
      if (tooFast) {
        arjun(s"Heartbeats are too fast! Slow down! mean = ${heartbeatReqs.mean}")
        changeInterval(selection(replyTo))
      } else if (tooSlow) {
        arjun(s"Heartbeats are too slow! Speed up! mean = ${heartbeatReqs.mean}")
        changeInterval(selection(replyTo))
      }
      heartbeatReqs.push()
    }
    case NewInterval(millis) => {
      arjun(s"Sending new interval $millis")
      server match {
        case None => {
          arjun(s"No server, contacting seeds $seeds")
          clock += 1
          seeds.map(fromNode)
            .foreach(_ ! SetHeartbeatInterval(self_as, HeartbeatInterval(clock, interval)))
        }
        case Some(ref) => {
          interval = millis
          changeInterval(fromNode(ref))
        }
      }
    }
    case Tick => {
      arjun("Received Tick")
      if (heartbeatReqs.millis_since_latest() > interval*noHeartbeatTolerance) {
        arjun(s"No ReqHeartbeats received for a while")
        server = None
        self ! NewInterval(interval)
      }
      context.system.scheduler.scheduleOnce(tickInterval.millis, self, Tick)
    }
    case AddSeed(seed) => {
      seeds += seed.anchorPath.address.host
        .zip(seed.anchorPath.address.port)
        .map(tup => Node(tup._1, tup._2))
        .getOrElse(id)
    }
    case a => arjun(s"Unhandled message $a")
  }

  def changeInterval(ref: ActorSelection): Unit = {
    clock += 1
    ref ! HeartbeatInterval(clock, interval)
    newStorage()
  }

  def newStorage(): IntervalStorage = {
    heartbeatReqs = new IntervalStorage((intervalStorageCapacity), interval)
    heartbeatReqs
  }

  def fromNode(node: Node): ActorSelection =
    context.actorSelection(addressString(node, "/user/bench-member"))

  def selection(ref: ActorRef): ActorSelection = context.actorSelection(ref.path)
}

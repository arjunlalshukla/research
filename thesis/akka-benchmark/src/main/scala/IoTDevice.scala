import Utils.arjun
import akka.actor.{Actor, ActorRef, ActorSelection}

import scala.concurrent.duration._

final class IoTDevice(
  var seeds: Set[ActorSelection],
  val id: Node,
  var interval: Int
) extends Actor {
  arjun(s"My path is ${context.self.path.toString}")
  import context.dispatcher
  val intervalStorageCapacity = 10
  val slowNetTolernce = 0.67
  val noHeartbeatTolerance = 2.0
  val tickInterval = 8000

  var clock = 0L
  var server: Option[ActorSelection] = None
  var heartbeatReqs = newStorage()

  self ! NewInterval(interval)
  self ! Tick

  def receive: Receive = {
    case ReqHeartbeat(replyTo) => {
      val as = selection(replyTo)
      if (!server.contains(as)) {
        newStorage()
        server = Option(as)
        seeds += as
      }
      arjun(s"Received ReqHeartbeat from $replyTo")
      replyTo ! Heartbeat(self)
      val tooFast = heartbeatReqs.full && heartbeatReqs.mean < interval
      val tooSlow = heartbeatReqs.full && heartbeatReqs.mean*slowNetTolernce > interval
      if (tooFast) {
        arjun(s"Heartbeats are too fast! Slow down! mean = ${heartbeatReqs.mean}")
        server.foreach(changeInterval)
      } else if (tooSlow) {
        arjun(s"Heartbeats are too slow! Speed up! mean = ${heartbeatReqs.mean}")
        server.foreach(changeInterval)
      }
      heartbeatReqs.push()
    }
    case NewInterval(millis) => {
      arjun(s"Sending new interval $millis")
      server match {
        case None => {
          arjun(s"No server, contacting seeds $seeds")
          clock += 1
          seeds.foreach(_ ! HeartbeatInterval(clock, interval))
        }
        case Some(ref) => {
          interval = millis
          changeInterval(ref)
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
    case AddSeed(seed) => seeds += seed
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

  def selection(ref: ActorRef): ActorSelection = context.actorSelection(ref.path)
}

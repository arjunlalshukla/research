import akka.actor.{Actor, ActorRef, ActorSelection, PoisonPill}
import akka.cluster.ddata.Replicator.{Subscribe, Update, WriteLocal}
import akka.cluster.ddata.{DistributedData, ORMap, ORMapKey, Replicator, SelfUniqueAddress}

import scala.concurrent.duration._
import Utils.arjun

final class ReqHeartbeatSender(
  var interval: Int,
  val destination: ActorSelection,
  val supervisor: ActorRef,
  val capacity: Int
) extends Actor {
  import context.dispatcher
  implicit val logContext = s"HeartbeatReqSender ${destination.anchorPath}"
  arjun(s"My path is ${context.self.path.toString}")
  val replicator = DistributedData(context.system).replicator
  implicit val node: SelfUniqueAddress =
    DistributedData(context.system).selfUniqueAddress
  val phi_threshold = 10

  val devicesKey = ORMapKey[ActorSelection, HeartbeatInterval]("devices")
  var storage = new IntervalStorage(capacity, interval)

  replicator ! Subscribe(devicesKey, self)
  self ! Tick

  def receive: Receive = {
    case Tick => {
      arjun(s"Sending ReqHeartbeat to $destination")
      arjun(s"phi is now ${storage.phi}")
      if (storage.phi < phi_threshold) {
        destination ! ReqHeartbeat(self)
        context.system.scheduler.scheduleOnce(interval.millis, self, Tick)
      } else {
        supervisor ! RemoveDevice(destination)
      }
    }
    case shi @ SetHeartbeatInterval(from, hi) => {
      arjun(s"Received HeartbeatInterval $hi from ${from.anchorPath} for $destination")
      supervisor ! shi
    }
    case Heartbeat(from) => {
      arjun(s"Received Heartbeat from ${from.path}")
      val as = selection(from)
      if (as == destination) {
        storage.push()
      } else {
        arjun(s"Does not match $destination")
      }
    }
    case c: Replicator.Changed[ORMap[ActorSelection, HeartbeatInterval]] => {
      c.dataValue.get(destination) match {
        case Some(HeartbeatInterval(_, millis)) => interval = millis
        case None =>
          self ! PoisonPill
      }
    }
    case a => arjun(s"Unhandled message $a")
  }

  override def postStop(): Unit = {
    arjun("I'm dying!!!")
    supervisor ! RemoveHeartbeatReqSender(destination, self)
  }

  def selection(ref: ActorRef): ActorSelection = context.actorSelection(ref.path)
}

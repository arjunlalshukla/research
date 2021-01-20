import akka.actor.{Actor, ActorRef, ActorSelection, PoisonPill}
import akka.cluster.ddata.Replicator.Subscribe
import akka.cluster.ddata.{DistributedData, ORMap, ORMapKey, Replicator, SelfUniqueAddress}

import scala.concurrent.duration._
import Utils.arjun

final class ReqHeartbeatSender(
  var devices: Map[ActorSelection, HeartbeatInterval],
  val destination: ActorSelection,
  val supervisor: ActorRef
) extends Actor {
  import context.dispatcher
  val replicator = DistributedData(context.system).replicator
  implicit val node: SelfUniqueAddress =
    DistributedData(context.system).selfUniqueAddress

  val devicesKey = ORMapKey[ActorSelection, HeartbeatInterval]("devices")

  replicator ! Subscribe(devicesKey, self)
  self ! Tick

  def receive: Receive = {
    case Tick => {
      arjun(s"Sending ReqHeartbeat to $destination")
      destination ! ReqHeartbeat(supervisor)
      devices.get(destination) match {
        case Some(HeartbeatInterval(_, interval)) =>
          context.system.scheduler.scheduleOnce(interval.millis, self, Tick)
        case None =>
          supervisor ! RemoveDevice(destination)
          self ! PoisonPill
      }
    }
    case c: Replicator.Changed[ORMap[ActorSelection, HeartbeatInterval]] => {
      devices = c.dataValue.entries
    }
    case a => arjun(s"Unhandled message $a")
  }

  def selection(ref: ActorRef): ActorSelection = context.actorSelection(ref.path)
}

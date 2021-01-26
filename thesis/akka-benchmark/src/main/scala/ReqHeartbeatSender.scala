import akka.actor.{Actor, ActorRef, ActorSelection, PoisonPill}
import akka.cluster.ddata.Replicator.{Subscribe, Update, WriteLocal}
import akka.cluster.ddata.{DistributedData, ORMap, ORMapKey, Replicator, SelfUniqueAddress}

import scala.concurrent.duration._
import Utils.{arjun, unreliableRef, unreliableSelection}
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.{CurrentClusterState, InitialStateAsEvents, MemberEvent, UnreachableMember}

final class ReqHeartbeatSender(
  val destination: ActorSelection,
  val supervisor: ActorRef,
  val capacity: Int,

  var interval: Int
) extends Actor {
  import context.dispatcher
  implicit val logContext = destination.anchorPath.address.host
    .zip(destination.anchorPath.address.port)
    .map(tup => ArjunContext(s"HeartbeatReqSender ${tup._1}:${tup._2}"))
    .getOrElse(ArjunContext(s"HeartbeatReqSender ${destination.anchorPath}"))
  arjun(s"My path is ${context.self.path.toString}")

  val phi_threshold = 10
  val self_as = context.actorSelection(self.path)

  var storage = new IntervalStorage(capacity, interval)

  self ! Tick

  def receive: Receive = {
    case Tick => tick()
    case hi: HeartbeatInterval => heartbeatInterval(hi)
    case Heartbeat(from) => heartbeat(from)
    case MultipleSenders(from) => supervisor ! AmISender(from, self)
    case a => arjun(s"Unhandled message $a")
  }

  override def postStop(): Unit = {
    arjun("I'm dying!!!")
    supervisor ! RemoveHeartbeatReqSender(destination, self)
  }

  def selection(ref: ActorRef): ActorSelection = context.actorSelection(ref.path)

  // Message Reactions
  def tick(): Unit = {
    arjun(s"Sending ReqHeartbeat with interval $interval ms to $destination")
    arjun(storage.summary)
    if (storage.phi < phi_threshold) {
      unreliableSelection(destination, ReqHeartbeat(self))
      context.system.scheduler.scheduleOnce(interval.millis, self, Tick)
    } else {
      supervisor ! RemoveDevice(destination)
    }
  }

  def heartbeatInterval(hi: HeartbeatInterval): Unit = {
    arjun(s"Received SetHeartbeatInterval $hi")
    if (hi.interval_millis != interval) {
      storage = new IntervalStorage(capacity, hi.interval_millis)
      interval = hi.interval_millis
    }
    destination ! SetHeartbeatInterval(self_as, hi)
  }

  def heartbeat(from: ActorRef): Unit = {
    arjun(s"Received Heartbeat from ${from.path}")
    if (selection(from) == destination) {
      storage.push()
    } else {
      arjun(s"Does not match $destination")
    }
  }
}

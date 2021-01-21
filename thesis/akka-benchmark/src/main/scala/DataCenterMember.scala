import akka.actor.{Actor, ActorRef, ActorSelection, PoisonPill, Props}
import akka.cluster.ddata.{DistributedData, ORMap, ORMapKey, Replicator, SelfUniqueAddress}
import akka.cluster.ddata.Replicator.{Changed, Delete, Subscribe, Update, WriteLocal}
import Utils.arjun

final class DataCenterMember extends Actor {
  implicit val logContext = ArjunContext("DataCenterMember")
  arjun(s"My path is ${context.self.path.toString}")
  val replicator = DistributedData(context.system).replicator
  implicit val node: SelfUniqueAddress =
    DistributedData(context.system).selfUniqueAddress

  val devicesKey = ORMapKey[ActorSelection, HeartbeatInterval]("devices")
  var devices = Map.empty[ActorSelection, HeartbeatInterval]

  var heartbeatReqSenders = Map.empty[ActorSelection, ActorRef]
  val capacity = 30

  replicator ! Subscribe(devicesKey, self)

  def receive: Receive = {
    case SetHeartbeatInterval(from, hi) => {
      arjun(s"Received SetHeartbeatInterval $hi from $from")
      if (canJoin(sender())) {
        arjun(s"Updating heartbeat interval for $from")
        replicator ! Update(
          devicesKey,
          ORMap.empty[ActorSelection, HeartbeatInterval],
          WriteLocal
        )(_ :+ from -> hi)
        devices += from -> hi
        if (!heartbeatReqSenders.contains(from)) {
          heartbeatReqSenders += from -> context.actorOf(Props(
            new ReqHeartbeatSender(hi.interval_millis, from, self, capacity)
          ))
        }
      } else {
        arjun(s"Rejected join request")
        from ! JoinRejected()
      }
    }
    case c: Replicator.Changed[ORMap[ActorSelection, HeartbeatInterval]] => {
      devices = c.dataValue.entries
    }
    case RemoveDevice(device) => {
      arjun(s"removing device $device")
      heartbeatReqSenders.get(device).foreach(_ ! PoisonPill)
      heartbeatReqSenders -= device
      devices -= device
      replicator ! Update(
        devicesKey,
        ORMap.empty[ActorSelection, HeartbeatInterval],
        WriteLocal
      )(_.remove(device))
    }
    case RemoveHeartbeatReqSender(device, toTerminate) => {
      heartbeatReqSenders.get(device).filter(_ == toTerminate) match {
        case Some(actor) =>
          arjun(s"removing heartbeat sender for $device")
          actor ! PoisonPill
          heartbeatReqSenders -= device
        case None =>
          arjun(s"Discarding removal of heartbeat sender for $device")
      }
    }
    case a => arjun(s"DataCenterMember: Unhandled message $a")
  }

  def canJoin(ref: ActorRef): Boolean = true

  def isManager(as: ActorSelection): Boolean = {
    devices.contains(as)
  }

  def selection(ref: ActorRef): ActorSelection = context.actorSelection(ref.path)
}

import akka.actor.{Actor, ActorRef, ActorSelection, PoisonPill, Props}
import akka.cluster.ddata.{DistributedData, ORMap, ORMapKey, Replicator, SelfUniqueAddress}
import akka.cluster.ddata.Replicator.{Changed, Subscribe, Update, WriteLocal}
import Utils.arjun

final class DataCenterMember extends Actor {
  arjun(s"My path is ${context.self.path.toString}")
  val replicator = DistributedData(context.system).replicator
  implicit val node: SelfUniqueAddress =
    DistributedData(context.system).selfUniqueAddress

  val devicesKey = ORMapKey[ActorSelection, HeartbeatInterval]("devices")
  var devices = Map.empty[ActorSelection, HeartbeatInterval]

  var phis = Map.empty[ActorSelection, LocalDeviceRecord]
  val capacity = 100

  replicator ! Subscribe(devicesKey, self)

  def receive: Receive = {
    case Heartbeat(from) => {
      arjun(s"Received Heartbeat from ${from.path}")
      val as = selection(from)
      if (isManager(as)) {
        if (!phis.contains(as)) {
          addToPhis(as)
        }
        arjun(s"phi for $as is now ${phis(as).storage.phi}")
        phis(as).storage.push()
      } else {
        arjun(s"Heartbeat from $from, but it is not registered")
      }
    }
    case hi: HeartbeatInterval => {
      arjun(s"Received HeartbeatInterval $hi from ${sender().path}")
      val as = selection(sender())
      if (canJoin(sender())) {
        arjun(s"Updating heartbeat interval for $as")
        replicator ! Update(
          devicesKey,
          ORMap.empty[ActorSelection, HeartbeatInterval],
          WriteLocal
        )(_ :+ as -> hi)
        devices += as -> hi
        if (isManager(as) && !phis.contains(as)) {
          addToPhis(as)
        }
      } else {
        arjun(s"Rejected join request")
        sender() ! JoinRejected()
      }
    }
    case c: Replicator.Changed[ORMap[ActorSelection, HeartbeatInterval]] => {
      devices = c.dataValue.entries
    }
    case RemoveDevice(as) => removeFromPhis(as)
    case a => arjun(s"Unhandled message $a")
  }

  def addToPhis(as: ActorSelection): Unit = {
    removeFromPhis(as)
    val a = context.actorOf(Props(new ReqHeartbeatSender(devices, as, self)))
    phis += as -> new LocalDeviceRecord(a, new IntervalStorage(capacity, 0))
  }

  def removeFromPhis(as: ActorSelection): Unit = {
    phis.get(as).foreach(_.heartbeatReqSender ! PoisonPill)
    phis -= as
  }

  def canJoin(ref: ActorRef): Boolean = true

  def isManager(as: ActorSelection): Boolean = {
    devices.contains(as)
  }

  def selection(ref: ActorRef): ActorSelection = context.actorSelection(ref.path)
}

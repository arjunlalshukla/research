import Utils.{addressString, arjun, toNode, unreliableSelection}
import akka.actor.{Actor, ActorRef, ActorSelection, PoisonPill, Props}
import akka.cluster.{Cluster, Member}
import akka.cluster.ClusterEvent.{CurrentClusterState, MemberEvent, MemberRemoved, MemberUp, UnreachableMember}
import akka.cluster.ddata.{DistributedData, ORMap, ORMapKey, Replicator, SelfUniqueAddress}
import akka.cluster.ddata.Replicator.{Update, WriteAll}
import scala.concurrent.duration
import scala.concurrent.duration.FiniteDuration
import scala.collection.mutable
import scala.collection.immutable.TreeSet

final class DataCenterMember(val id: Node) extends Actor {
  implicit val logContext = ArjunContext(s"DataCenterMember-${id.port}")
  arjun(s"My path is ${context.self.path.toString}")
  val replicator = DistributedData(context.system).replicator
  implicit val node: SelfUniqueAddress =
    DistributedData(context.system).selfUniqueAddress

  val cluster = Cluster(context.system)
  val devicesKey = DevicesCrdtKey("devices")
  val capacity = 30
  val self_as = context.actorSelection(self.path)

  var devices = DevicesCrdt.empty
  var heartbeatReqSenders = Map.empty[ActorSelection, ActorRef]
  var members = TreeSet.empty[Member]
  var ring = new NodeRing()
  ring.insert(id)
  var subscribers = mutable.HashSet.empty[ActorSelection]

  cluster.registerOnMemberUp {
    cluster.subscribe(self, classOf[MemberEvent], classOf[UnreachableMember])
  }
  replicator ! Replicator.Subscribe(devicesKey, self)

  def receive: Receive = {
    case SetHeartbeatInterval(from, hi) => setHeartbeatInterval(from, hi)
    case c: Replicator.Changed[DevicesCrdt] =>
      devicesUpdate(c.dataValue)
    case RemoveDevice(device) => removeDevice(device)
    case RemoveHeartbeatReqSender(device, toTerminate) =>
      removeHeartbeatReqSender(device, toTerminate)
    case ccc: CurrentClusterState => newMembers(ccc)
    case AmISender(from, sender) => amISender(from, sender)
    case MemberUp(member) => addMember(member)
    case MemberRemoved(member, _) => removeMember(member)
    case SubscribeDevices(subscriber) => {
      arjun(s"Added subscriber $subscriber")
      subscriber ! Devices(id.port, heartbeatReqSenders.keySet, members, devices)
      subscribers.add(subscriber)
    }
    case a => arjun(s"Unhandled message $a")
  }

  def selection(ref: ActorRef): ActorSelection = context.actorSelection(ref.path)

  def devicesUpdate(newDevices: DevicesCrdt): Unit = {
    val str = heartbeatReqSenders.keySet.map(toNode(_, id)).mkString("\n")
    arjun(s"Devices Updated! Total: ${newDevices.states.size} devices; Responsible for: \n$str")
    devices = newDevices
    notify_subrs()
  }

  def notify_subrs(): Unit =
    subscribers.foreach(_ ! Devices(id.port, heartbeatReqSenders.keySet, members, devices))

  def senderUpdated(newSenders: Map[ActorSelection, ActorRef]): Unit = {
    heartbeatReqSenders = newSenders
    notify_subrs()
  }

  // Message Reactions
  def setHeartbeatInterval(from: ActorSelection, hi: HeartbeatInterval): Unit = {
    arjun(s"SetHeartbeatInterval received: $hi from $from")
    val manager = fromNode(ring.responsibility(toNode(from, id)))
    if (heartbeatReqSenders.contains(from) || manager == self_as) {
      replicator ! Update(
        devicesKey,
        DevicesCrdt.empty,
        WriteAll(FiniteDuration(1000000, duration.SECONDS))
      )(_.put(toNode(from, id), hi.interval_millis))
      if (!heartbeatReqSenders.contains(from)) {
        val actor = context.actorOf(Props(
          new ReqHeartbeatSender(from, self, capacity, hi.interval_millis)
        ))
        senderUpdated(heartbeatReqSenders + (from -> actor))
      }
      heartbeatReqSenders(from) ! hi
    } else {
      unreliableSelection(manager, SetHeartbeatInterval(from, hi))
    }
  }

  def removeDevice(device: ActorSelection): Unit = {
    arjun(s"Device removed: $device")
    heartbeatReqSenders.get(device).foreach(_ ! PoisonPill)
    senderUpdated(heartbeatReqSenders - device)
    replicator ! Update(
      devicesKey,
      DevicesCrdt.empty,
      WriteAll(FiniteDuration(1000000, duration.SECONDS))
    )(_.remove(toNode(device, id)))
  }

  def removeHeartbeatReqSender(device: ActorSelection, hbrs: ActorRef): Unit = {
    heartbeatReqSenders.get(device).filter(_ == hbrs) match {
      case Some(actor) =>
        arjun(s"Heartbeat sender removed for $device")
        actor ! PoisonPill
        senderUpdated(heartbeatReqSenders - device)
      case None =>
        arjun(s"Discarding removal of heartbeat sender for $device")
    }
  }

  def removeMember(member: Member): Unit = {
    members -= member
    ring.remove(Node(member.address.host.get, member.address.port.get))
    notify_subrs()
    arjun(s"Member removed: $member;")
  }

  def addMember(member: Member): Unit = {
    members += member
    ring.insert(Node(member.address.host.get, member.address.port.get))
    notify_subrs()
    arjun(s"Member added: $member;")
  }

  def newMembers(ccc: CurrentClusterState): Unit = {
    members = TreeSet.from(ccc._1)
    ring = new NodeRing()
    ring.insert(id)
    members.foreach { member =>
      val host = member.address.host.get
      val port = member.address.port.get
      if (host != id.host || port != id.port) {
        ring.insert(Node(host, port))
      }
    }
    notify_subrs()
  }

  def amISender(dest: ActorSelection, sender: ActorRef): Unit = {
    val isManager = self_as == fromNode(ring.responsibility(toNode(dest, id)))
    val isSender = heartbeatReqSenders.get(dest).contains(sender)
    if (!isManager || !isSender) {
      arjun(s"Killing not-sender for $dest that is $sender")
      arjun(s"Responsible for: ${heartbeatReqSenders.size} nodes")
      senderUpdated(heartbeatReqSenders - dest)
      sender ! PoisonPill
    } else {
      arjun(s"Keeping sender for $dest that is $sender")
    }
  }

  def fromNode(node: Node): ActorSelection = {
    context.actorSelection(addressString(node, "/user/bench-member"))
  }
}

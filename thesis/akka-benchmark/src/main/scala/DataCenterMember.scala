import Utils.{addressString, arjun, responsibility, toNode}
import akka.actor.{Actor, ActorRef, ActorSelection, PoisonPill, Props}
import akka.cluster.{Cluster, Member}
import akka.cluster.ClusterEvent.{CurrentClusterState, MemberEvent, MemberRemoved, MemberUp, UnreachableMember}
import akka.cluster.ddata.Replicator.{Subscribe, Update, WriteLocal}
import akka.cluster.ddata._

import scala.collection.mutable
import scala.collection.mutable.TreeSet

final class DataCenterMember(val id: Node) extends Actor {
  implicit val logContext = ArjunContext("DataCenterMember")
  arjun(s"My path is ${context.self.path.toString}")
  val replicator = DistributedData(context.system).replicator
  implicit val node: SelfUniqueAddress =
    DistributedData(context.system).selfUniqueAddress

  val cluster = Cluster(context.system)
  val devicesKey = ORMapKey[ActorSelection, HeartbeatInterval]("devices")
  val capacity = 30
  val self_as = context.actorSelection(self.path)

  var devices = Map.empty[ActorSelection, HeartbeatInterval]
  var heartbeatReqSenders = Map.empty[ActorSelection, ActorRef]
  var members = TreeSet.empty[Member]
  var servers = IndexedSeq(self_as)

  cluster.registerOnMemberUp {
    cluster.subscribe(self, classOf[MemberEvent], classOf[UnreachableMember])
  }
  replicator ! Subscribe(devicesKey, self)

  def receive: Receive = {
    case SetHeartbeatInterval(from, hi) => setHeartbeatInterval(from, hi)
    case c: Replicator.Changed[ORMap[ActorSelection, HeartbeatInterval]] =>
      devicesUpdated(c.dataValue.entries)
    case RemoveDevice(device) => removeDevice(device)
    case RemoveHeartbeatReqSender(device, toTerminate) =>
      removeHeartbeatReqSender(device, toTerminate)
    case ccc: CurrentClusterState => newMembers(ccc)
    case AmISender(from, sender) => amISender(from, sender)
    case MemberUp(member) => addMember(member)
    case MemberRemoved(member, _) => removeMember(member)
    case a => arjun(s"Unhandled message $a")
  }

  def selection(ref: ActorRef): ActorSelection = context.actorSelection(ref.path)

  def updateServers(): Unit = {
    servers = members.toIndexedSeq.map { member =>
      val node = member.address.host
        .zip(member.address.port)
        .map(tup => Node(tup._1, tup._2))
        .getOrElse(id)
      context.actorSelection(addressString(node, "/user/bench-member"))
    }
  }

  def serverString: String = servers.map('"' + _.toString + '"').mkString(",")

  // Message Reactions
  def setHeartbeatInterval(from: ActorSelection, hi: HeartbeatInterval): Unit = {
    arjun(s"SetHeartbeatInterval received: $hi from $from")
    val manager = responsibility(toNode(from, id), servers)
    if (heartbeatReqSenders.contains(from) || manager == self_as) {
      if (!devices.get(from).contains(hi)) {
        replicator ! Update(
          devicesKey,
          ORMap.empty[ActorSelection, HeartbeatInterval],
          WriteLocal
        )(_ :+ from -> hi)
        devices += from -> hi
      }
      if (!heartbeatReqSenders.contains(from)) {
        heartbeatReqSenders += from -> context.actorOf(Props(
          new ReqHeartbeatSender(from, self, capacity, hi.interval_millis)
        ))
      }
      heartbeatReqSenders(from) ! hi
    } else {
      manager ! SetHeartbeatInterval(from, hi)
    }
  }

  def devicesUpdated(newDevices: Map[ActorSelection, HeartbeatInterval]): Unit = {
    arjun(s"Devices updated to $newDevices")
    devices = newDevices
  }

  def removeDevice(device: ActorSelection): Unit = {
    arjun(s"Device removed: $device")
    heartbeatReqSenders.get(device).foreach(_ ! PoisonPill)
    heartbeatReqSenders -= device
    devices -= device
    replicator ! Update(
      devicesKey,
      ORMap.empty[ActorSelection, HeartbeatInterval],
      WriteLocal
    )(_.remove(device))
  }

  def removeHeartbeatReqSender(device: ActorSelection, hbrs: ActorRef): Unit = {
    heartbeatReqSenders.get(device).filter(_ == hbrs) match {
      case Some(actor) =>
        arjun(s"Heartbeat sender removed for $device")
        actor ! PoisonPill
        heartbeatReqSenders -= device
      case None =>
        arjun(s"Discarding removal of heartbeat sender for $device")
    }
  }

  def removeMember(member: Member): Unit = {
    members.remove(member);
    updateServers();
    arjun(s"Member removed: $member; All servers: $serverString")
  }

  def addMember(member: Member): Unit = {
    members.add(member);
    updateServers();
    arjun(s"Member added: $member; All servers: $serverString")
  }

  def newMembers(ccc: CurrentClusterState): Unit = {
    members = mutable.TreeSet.from(ccc._1)
    updateServers()
    arjun(s"Members updated to $serverString")
    heartbeatReqSenders.values.foreach(_ ! UpdateServers(servers))
  }

  def amISender(dest: ActorSelection, sender: ActorRef): Unit = {
    val isManager = self_as == responsibility(toNode(dest, id), servers)
    val isSender = heartbeatReqSenders.get(dest).contains(sender)
    if (!isManager || !isSender) {
      arjun(s"Killing not-sender for $dest that is $sender")
      sender ! PoisonPill
    } else {
      arjun(s"Keeping sender for $dest that is $sender")
    }
  }
}

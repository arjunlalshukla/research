import akka.actor.{ActorRef, ActorSelection}
import akka.cluster.ddata.ReplicatedData
import scala.collection.immutable.TreeSet
import akka.cluster.Member

case class Node(host: String, port: Int)
case class ArjunContext(s: String)
case class HeartbeatInterval(clock: Long, interval_millis: Int)
  extends ReplicatedData with Ordered[HeartbeatInterval] {
  type T = HeartbeatInterval
  def merge(that: HeartbeatInterval): HeartbeatInterval = {
    if (clock > that.clock) this
    else if (clock < that.clock) that
    else HeartbeatInterval(clock, interval_millis max that.interval_millis)
  }

  def compare(that: HeartbeatInterval): Int = {
    if (clock > that.clock) 1
    else if (clock < that.clock) -1
    else if (interval_millis < that.interval_millis) 1
    else if (interval_millis > that.interval_millis) -1
    else 0
  }
}

// Message Types: Multiple Users
case object Tick
case class ReqReport(replyTo: ActorRef)

// Message Types: Devices
case class ReqHeartbeat(replyTo: ActorRef)
case class JoinRejected()
case class NewInterval(millis: Int)

// Message Types: HeartbeatReqSenders
case class Heartbeat(from: ActorRef)
case class UpdateServers(servers: IndexedSeq[ActorSelection])
case class MultipleSenders(receiver: ActorSelection)

// Message Types: Data Center Member
case class SetHeartbeatInterval(from: ActorSelection, hi: HeartbeatInterval)
case class RemoveHeartbeatReqSender(device: ActorSelection, toTerminate: ActorRef)
case class RemoveDevice(device: ActorSelection)
case class AmISender(dest: ActorSelection, sender: ActorRef)
case class SubscribeDevices(subscriber: ActorSelection)

// MessageTypes: IoTBusiness
case class Manager(port: Int, node: Option[Node])

// Message Types: Data Center Business
case class Devices(port: Int, set: Set[ActorSelection], members: TreeSet[Member], devices: DevicesCrdt)
case class Increment(device: ActorSelection, amount: Long)

// Message Types: Report Receiver
case class IoTReport(data: Seq[Long])

// Message Types: Collector
case class DCReport(from: ActorRef, totals: Map[ActorSelection, Long])
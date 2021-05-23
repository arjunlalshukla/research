import akka.actor.{ActorRef, ActorSelection}
import akka.cluster.ddata.ReplicatedData
import scala.collection.immutable.TreeSet
import akka.cluster.Member

trait MyCbor

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
case class ReqReport(replyTo: ActorRef) extends MyCbor

// Message Types: Devices
case class ReqHeartbeat(replyTo: ActorRef) extends MyCbor
case class JoinRejected() extends MyCbor
case class NewInterval(millis: Int) extends MyCbor

// Message Types: HeartbeatReqSenders
case class Heartbeat(from: ActorRef) extends MyCbor
case class UpdateServers(servers: IndexedSeq[ActorSelection]) extends MyCbor
case class MultipleSenders(receiver: ActorSelection) extends MyCbor

// Message Types: Data Center Member
case class SetHeartbeatInterval(from: ActorSelection, hi: HeartbeatInterval) extends MyCbor
case class RemoveHeartbeatReqSender(device: ActorSelection, toTerminate: ActorRef) extends MyCbor
case class RemoveDevice(device: ActorSelection) extends MyCbor
case class AmISender(dest: ActorSelection, sender: ActorRef) extends MyCbor
case class SubscribeDevices(subscriber: ActorSelection) extends MyCbor

// MessageTypes: IoTBusiness
case class Manager(port: Int, node: Option[Node]) extends MyCbor

// Message Types: Data Center Business
case class Devices(port: Int, set: Set[ActorSelection], members: TreeSet[Member], devices: DevicesCrdt) extends MyCbor
case class Increment(device: ActorSelection, amount: Long, sent: Long, recvd: Long) extends MyCbor

// Message Types: Report Receiver
case class IoTReport(data: Seq[Long]) extends MyCbor

// Message Types: Collector
case class DCReport(from: ActorSelection, totals: Map[ActorSelection, Long]) extends MyCbor
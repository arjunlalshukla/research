import akka.actor.{ActorRef, ActorSelection}
import akka.cluster.ddata.ReplicatedData

case class Node(host: String, port: Int)
case class ArjunContext(s: String)
case class HeartbeatInterval(clock: Long, interval_millis: Int)
  extends ReplicatedData {
  type T = HeartbeatInterval
  def merge(that: HeartbeatInterval): HeartbeatInterval = {
    if (clock > that.clock) this
    else if (clock < that.clock) that
    else HeartbeatInterval(clock, interval_millis max that.interval_millis)
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
case class SubscribeDevices(subscriber: ActorRef)

// Message Types: Data Center Business
case class Devices(set: Set[ActorSelection])
case class Increment(device: ActorSelection, amount: Long)

// Message Types: Report Receiver
case class IoTReport(data: Seq[Long])

// Message Types: Collector
case class DCReport(from: ActorRef, totals: Map[ActorSelection, Long])
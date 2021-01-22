import akka.actor.{ActorRef, ActorSelection}
import akka.cluster.ddata.ReplicatedData

case class Node(host: String, port: Int)
case class ArjunContext(s: String)
case object Tick

// Message Types: Devices
case class ReqHeartbeat(replyTo: ActorRef)
case class JoinRejected()
case class NewInterval(millis: Int)

//Message Types: Data Center
case class Heartbeat(from: ActorRef)
case class SetHeartbeatInterval(from: ActorSelection, hi: HeartbeatInterval)
case class HeartbeatInterval(clock: Long, interval_millis: Int)
  extends ReplicatedData {
  type T = HeartbeatInterval
  def merge(that: HeartbeatInterval): HeartbeatInterval = {
    if (clock > that.clock) this
    else if (clock < that.clock) that
    else HeartbeatInterval(clock, interval_millis max that.interval_millis)
  }
}
case class RemoveHeartbeatReqSender(device: ActorSelection, toTerminate: ActorRef)
case class RemoveDevice(device: ActorSelection)

class LocalDeviceRecord(
  val heartbeatReqSender: ActorRef,
  val storage: IntervalStorage
)

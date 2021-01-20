import akka.actor.{ActorRef, ActorSelection}
import akka.cluster.ddata.ReplicatedData

case class Node(host: String, port: Int)
case object Tick

// Message Types: Devices
case class ReqHeartbeat(replyTo: ActorRef)
case class JoinRejected()
case class NewInterval(millis: Int)
case class AddSeed(seed: ActorSelection)

//Message Types: Data Center
case class Heartbeat(from: ActorRef)
case class HeartbeatInterval(clock: Long, interval_millis: Int)
  extends ReplicatedData {
  type T = HeartbeatInterval
  def merge(that: HeartbeatInterval): HeartbeatInterval = {
    if (clock > that.clock) this
    else if (clock < that.clock) that
    else HeartbeatInterval(clock, interval_millis max that.interval_millis)
  }
}
case class RemoveDevice(as: ActorSelection)

class LocalDeviceRecord(
  val heartbeatReqSender: ActorRef,
  val storage: IntervalStorage
)

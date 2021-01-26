import akka.actor.{ActorRef, ActorSelection}
import akka.cluster.ddata.ReplicatedData

case class Node(host: String, port: Int)
case class ArjunContext(s: String)
case object Tick
case class HeartbeatInterval(clock: Long, interval_millis: Int)
  extends ReplicatedData {
  type T = HeartbeatInterval
  def merge(that: HeartbeatInterval): HeartbeatInterval = {
    if (clock > that.clock) this
    else if (clock < that.clock) that
    else HeartbeatInterval(clock, interval_millis max that.interval_millis)
  }
}

// Message Types: Devices
case class ReqHeartbeat(replyTo: ActorRef)
case class JoinRejected()
case class NewInterval(millis: Int)

//Message Types: HeartbeatReqSenders
case class Heartbeat(from: ActorRef)
case class UpdateServers(servers: IndexedSeq[ActorSelection])
case class MultipleSenders(receiver: ActorSelection)

//Message Types: Data Center
case class SetHeartbeatInterval(from: ActorSelection, hi: HeartbeatInterval)
case class RemoveHeartbeatReqSender(device: ActorSelection, toTerminate: ActorRef)
case class RemoveDevice(device: ActorSelection)
case class AmISender(dest: ActorSelection, sender: ActorRef)
import Utils.{addressString, arjun, toNode, unreliableRef, unreliableSelection}
import akka.actor.{Actor, ActorRef, ActorSelection}

import scala.collection.mutable
import scala.concurrent.duration.DurationInt

final class IoTDevice(
  var seeds: Set[Node],
  val id: Node,
  var interval: Int
) extends Actor {
  implicit val logContext = ArjunContext(s"IoTDevice-${id.port}")
  arjun(s"My path is ${context.self.path.toString}")
  import context.dispatcher

  val intervalStorageCapacity = 10
  val slowNetTolerance = 0.5
  val phi_threshold = 10.0
  val self_as = context.actorSelection(self.path)
  val serverLogCapacity = 10
  val subscribers = mutable.Set.empty[ActorSelection]

  var server: Option[Node] = None
  var serverLog = new FrequencyBuffer[ActorRef](serverLogCapacity)
  var heartbeatReqs = newStorage()
  var numIntervals = 1L
  var numIntervalsServerPOV = 0L

  self ! NewInterval(interval)
  self ! Tick

  def receive: Receive = {
    case ReqHeartbeat(replyTo) => reqHeartbeat(replyTo)
    case NewInterval(millis) => newInterval(millis)
    case Tick => tick()
    case SetHeartbeatInterval(from, HeartbeatInterval(num, millis)) =>
      setHeartbeatInterval(from, num, millis)
    case SubscribeDevices(subr) => {
      arjun(s"Added subscriber $subr")
      subr ! Manager(id.port, server)
      subscribers.add(subr)
    }
    case a => arjun(s"Unhandled message $a")
  }

  def newStorage(): IntervalStorage = {
    heartbeatReqs = new IntervalStorage((intervalStorageCapacity), interval)
    heartbeatReqs
  }

  def newServer(node: Option[Node]): Unit = {
    server = node
    numIntervalsServerPOV = 0
    subscribers.foreach(_ ! Manager(id.port ,server))
  }

  def fromNode(node: Node): ActorSelection = {
    context.actorSelection(addressString(node, "/user/bench-member"))
  }

  def selection(ref: ActorRef): ActorSelection = context.actorSelection(ref.path)

  // Message Reactions
  def reqHeartbeat(replyTo: ActorRef): Unit = {
    val node = replyTo.path.address.host
      .zip(replyTo.path.address.port)
      .map(tup => Node(tup._1, tup._2))
      .getOrElse(id)
    if (!server.contains(node)) {
      newStorage()
      newServer(Option(node))
      seeds += node
    }
    serverLog.push(replyTo)
    if (serverLog.changes + 1 != serverLog.frequencies.size) {
      arjun(s"Multiple ReqHeartbeatSenders detected. Changes = ${serverLog.changes}; " +
        s"Servers = ${serverLog.frequencies.keys}; Buffer = ${serverLog.buffer}")
      serverLog.frequencies.keys.foreach(unreliableRef(_, MultipleSenders(self_as)))
      serverLog = new FrequencyBuffer[ActorRef](serverLogCapacity)
    }
    arjun(s"Received ReqHeartbeat from $replyTo")
    heartbeatReqs.push()
    unreliableRef(replyTo, Heartbeat(self))
    if (numIntervals != numIntervalsServerPOV) {
      arjun(s"Resending interval to server. Our count: $numIntervals; " +
        s"Their count: $numIntervalsServerPOV")
      newInterval(interval)
    }
  }

  def newInterval(millis: Int): Unit = {
    arjun(s"Sending new interval $millis")
    if (interval != millis) {
      numIntervals += 1
      interval = millis
    }
    if (server.isEmpty) {
      arjun(s"No server, contacting seeds $seeds")
    }
    val msg =
      SetHeartbeatInterval(self_as, HeartbeatInterval(numIntervals, interval))
    server.map(Set(_)).getOrElse(seeds).map(fromNode)
      .foreach(unreliableSelection(_, msg))
  }

  def setHeartbeatInterval(from: ActorSelection, num: Long, millis: Int): Unit = {
    arjun(s"Acknowledgement of SetHeartbeatInterval received. num = $num; " +
      s"millis = $millis; previous server POV = $numIntervalsServerPOV; " +
      s"num intervals = $numIntervals; from $from"
    )
    newServer(Option(toNode(from, id)))
    numIntervalsServerPOV = num
    if (numIntervalsServerPOV < numIntervals) {
      newInterval(interval)
    } else if (numIntervalsServerPOV > numIntervals || interval != millis) {
      numIntervals = num + 1
      newInterval(interval)
    } else {
      newStorage()
    }
  }

  def tick(): Unit = {
    arjun("Received Tick")
    if (heartbeatReqs.phi > phi_threshold) {
      arjun(s"No ReqHeartbeats received for a while. Assuming the server is down")
      arjun(heartbeatReqs.summary)
      newServer(None)
      self ! NewInterval(interval)
    }
    // Use pi because it is irrational. We want the tick to be sporadically
    // interleaved with heartbeat requests
    context.system.scheduler.scheduleOnce((interval*4/math.Pi).toInt.millis, self, Tick)
  }
}

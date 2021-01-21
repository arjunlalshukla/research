import Utils.{addressString, arjun, unreliableRef, unreliableSelection}
import akka.actor.{Actor, ActorRef, ActorSelection}
import scala.concurrent.duration.DurationInt

final class IoTDevice(
  var seeds: Set[Node],
  val id: Node,
  var interval: Int,
  val newIntervalsFailToSend: Boolean
) extends Actor {
  implicit val logContext = ArjunContext("IoTDevice")
  arjun(s"My path is ${context.self.path.toString}")
  import context.dispatcher
  val intervalStorageCapacity = 10
  val slowNetTolerance = 0.5
  val firstInterval = interval
  val phi_threshold = 10.0
  val self_as = context.actorSelection(self.path)
  // val previousInterval = 0

  var clock = 0L
  var server: Option[Node] = None
  var heartbeatReqs = newStorage()

  self ! NewInterval(interval)
  self ! Tick

  def receive: Receive = {
    case ReqHeartbeat(replyTo) => {
      val node = replyTo.path.address.host
        .zip(replyTo.path.address.port)
        .map(tup => Node(tup._1, tup._2))
        .getOrElse(id)
      if (!server.contains(node)) {
        newStorage()
        server = Option(node)
        seeds += node
      }
      arjun(s"Received ReqHeartbeat from $replyTo")
      unreliableRef(replyTo, Heartbeat(self))
      // How do we detect if heartbeats are being sent with the wrong interval?
      // Messages telling the server to change interval might be lost. Current
      // implementation does not work well.
      val tooFast = heartbeatReqs.full && heartbeatReqs.mean < interval
      val tooSlow = heartbeatReqs.full && heartbeatReqs.mean*slowNetTolerance > interval
      if (tooFast || tooSlow) {
        val speed = if (tooFast) "fast" else "slow"
        arjun(s"Detected wrong interval! Too $speed! Expects $interval ms")
        arjun(heartbeatReqs.summary)
        self ! NewInterval(interval)
      }
      heartbeatReqs.push()
    }
    case NewInterval(millis) => {
      arjun(s"Sending new interval $millis")
      if (interval != millis) {
        newStorage()
      }
      val fail_prob =
        if (interval != firstInterval && newIntervalsFailToSend) 1.0
        else Utils.fail_prob
      interval = millis
      clock += 1
      server match {
        case None => {
          arjun(s"No server, contacting seeds $seeds")
          seeds.map(fromNode).foreach(unreliableSelection(_,
            SetHeartbeatInterval(self_as, HeartbeatInterval(clock, interval)),
            fail_prob = fail_prob)
          )
        }
        case Some(node) => {
          unreliableSelection(
            fromNode(node),
            SetHeartbeatInterval(self_as, HeartbeatInterval(clock, interval)),
            fail_prob = fail_prob
          )
        }
      }
    }
    case Tick => {
      arjun("Received Tick")
      if (heartbeatReqs.phi > phi_threshold) {
        arjun(s"No ReqHeartbeats received for a while. Assuming the server is down")
        arjun(heartbeatReqs.summary)
        server = None
        self ! NewInterval(interval)
      }
      // Use pi because it is irrational. We want the tick to be sporadically
      // interleaved with heartbeat requests
      context.system.scheduler.scheduleOnce((interval*4/math.Pi).toInt.millis, self, Tick)
    }
    case AddSeed(seed) => {
      seeds += seed.anchorPath.address.host
        .zip(seed.anchorPath.address.port)
        .map(tup => Node(tup._1, tup._2))
        .getOrElse(id)
    }
    case a => arjun(s"Unhandled message $a")
  }

  def newStorage(): IntervalStorage = {
    heartbeatReqs = new IntervalStorage((intervalStorageCapacity), interval)
    heartbeatReqs
  }

  def fromNode(node: Node): ActorSelection =
    context.actorSelection(addressString(node, "/user/bench-member"))

  def selection(ref: ActorRef): ActorSelection = context.actorSelection(ref.path)
}

import akka.actor.{ActorContext, ActorRef, ActorSelection}

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.Try

object Utils {
  val clusterName = "AkkaBenchCluster"
  val rand = scala.util.Random
  val props = System.getProperties.asScala
  val min_delay = props.getOrElse("MIN_DELAY", "0").toInt
  println(s"Default min delay is $min_delay")
  val max_delay = props.getOrElse("MAX_DELAY", "0").toInt
  println(s"Default max delay is $max_delay")
  val fail_prob = props.getOrElse("FAIL_PROB", "0.0").toDouble
  println(s"Default fail prob is $fail_prob")

  def rand_range(min: Int, max: Int): Int = {
    if (max - min <= 0) {
      0
    } else {
      rand.nextInt(max - min) + min
    }
  }

  def arjun(s: Any)(implicit context: ArjunContext): Unit =
    println(s"[     arjun     ][${context.s}] ${s.toString}")

  def selection(ref: ActorRef)(implicit context: ActorContext): ActorSelection = {
    context.actorSelection(ref.path)
  }

  def addressString(node: Node, localPath: String): String = {
    s"akka://$clusterName@${node.host}:${node.port}$localPath"
  }

  def responsibility(device: Node, servers: IndexedSeq[ActorSelection])
    (implicit context: ArjunContext): ActorSelection = {
    val hash = device.hashCode.abs
    val as = servers(hash % servers.length)
    arjun(s"Device $device  with hash $hash will be managed by $as")
    as
  }

  def toNode(as: ActorSelection, id: Node): Node = {
    as.anchorPath.address.host
      .zip(as.anchorPath.address.port)
      .map(tup => Node(tup._1, tup._2))
      .getOrElse(id)
  }

  def unreliableRef(
    ref: ActorRef,
    msg: Any,
    max_delay: Int = Utils.max_delay,
    min_delay: Int = Utils.min_delay,
    fail_prob: Double = fail_prob,
    toPrint: Option[String] = None
  )(implicit context: ActorContext, logContext: ArjunContext): Unit = {
    val p = toPrint.getOrElse(msg.toString)
    if (min_delay == 0 && max_delay == 0 && fail_prob == 0) {
      ref ! msg
    } else if (rand.nextDouble > fail_prob) {
      import context.dispatcher
      val delay = rand_range(min_delay, max_delay).millis
      context.system.scheduler.scheduleOnce(delay)(ref ! msg)
      arjun(s"Delay of $delay milliseconds put on send of $p to $ref")
    } else {
      arjun(s"Dropped message due to unreliable send of $p to $ref")
    }
  }


  def unreliableSelection(
    ref: ActorSelection,
    msg: Any,
    max_delay: Int = Utils.max_delay,
    min_delay: Int = Utils.min_delay,
    fail_prob: Double = fail_prob,
    toPrint: Option[String] = None
  )(implicit context: ActorContext, logContext: ArjunContext): Unit = {
    val p = toPrint.getOrElse(msg.toString)
    if (min_delay == 0 && max_delay == 0 && fail_prob == 0) {
      ref ! msg
    } else if (rand.nextDouble > fail_prob) {
      import context.dispatcher
      val delay = rand_range(min_delay, max_delay).millis
      context.system.scheduler.scheduleOnce(delay)(ref ! msg)
      arjun(s"Delay of $delay milliseconds put on send of $p to $ref")
    } else {
      arjun(s"Dropped message due to unreliable send of $p to $ref")
    }
  }
}
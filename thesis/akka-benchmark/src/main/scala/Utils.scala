import akka.actor.{ActorContext, ActorRef, ActorSelection}
import scala.concurrent.duration._

object Utils {
  val clusterName = "AkkaBenchCluster"
  val rand = scala.util.Random
  val min_delay = 500
  val max_delay = 3000
  val fail_prob = 0.5

  def rand_range(min: Int, max: Int): Int = rand.nextInt(max - min) + min

  def arjun(s: Any)(implicit context: String): Unit =
    println(s"[     arjun     ][$context] ${s.toString}")

  def selection(ref: ActorRef)(implicit context: ActorContext): ActorSelection = {
    context.actorSelection(ref.path)
  }

  def addressString(node: Node, localPath: String): String = {
    s"akka://$clusterName@${node.host}:${node.port}$localPath"
  }

  def unreliableRef(
    ref: ActorRef,
    msg: Any,
    max_delay: Int = Utils.max_delay,
    min_delay: Int = Utils.min_delay,
    fail_prob: Double = fail_prob
  )(implicit context: ActorContext, logContext: String): Unit = {
    if (rand.nextDouble > fail_prob) {
      import context.dispatcher
      val delay = rand_range(min_delay, max_delay).millis
      context.system.scheduler.scheduleOnce(delay)(ref ! msg)
      arjun(s"Unreliable send of $msg to $ref was delayed by $delay")
    } else {
      arjun(s"Unreliable send of $msg to $ref was dropped!")
    }
  }

  def unreliableSelection(
    ref: ActorSelection,
    msg: Any,
    max_delay: Int = Utils.max_delay,
    min_delay: Int = Utils.min_delay,
    fail_prob: Double = fail_prob
  )(implicit context: ActorContext, logContext: String): Unit = {
    if (rand.nextDouble > fail_prob) {
      import context.dispatcher
      val delay = rand_range(min_delay, max_delay).millis
      context.system.scheduler.scheduleOnce(delay)(ref ! msg)
      arjun(s"Unreliable send of $msg to $ref was delayed by $delay")
    } else {
      arjun(s"Unreliable send of $msg to $ref was dropped!")
    }
  }
}
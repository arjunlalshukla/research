import akka.actor.{ActorContext, ActorRef, ActorSelection}

import java.io.{File, FileWriter, PrintWriter}
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.Try
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

object Utils {
  val clusterName = "AkkaBenchCluster"
  val rand = scala.util.Random
  val props = System.getProperties.asScala
  val logCxt = ArjunContext("Utils")
  val loggingOn = props.getOrElse("LOGGING_ON", "true").toBoolean
  val append = props.getOrElse("APPEND_LOG", "false").toBoolean
  val logger = props.get("BENCH_LOG_FILE")
    .map(str => new PrintWriter(new FileWriter(str, append)))
    .getOrElse(new PrintWriter(System.out))
  
  val started = LocalDateTime.now()

  val min_delay = props.getOrElse("MIN_DELAY", "0").toIntOption
    .filter(_ >= 0).get
  arjun(s"Default min delay is $min_delay")(logCxt)
  val max_delay = props.getOrElse("MAX_DELAY", "0").toIntOption
    .filter(_ >= min_delay).get
  arjun(s"Default max delay is $max_delay")(logCxt)
  val fail_prob = props.getOrElse("FAIL_PROB", "0.0").toDoubleOption
    .filter(prob => prob >= 0.0 && prob <= 1.0).get
  arjun(s"Default fail prob is $fail_prob")(logCxt)

  def rand_range(min: Int, max: Int): Int = {
    if (max - min <= 0) {
      0
    } else {
      rand.nextInt(max - min) + min
    }
  }

  def arjun(s: Any, toPrint: Boolean = true)(implicit context: ArjunContext): Unit = {
    if (toPrint && loggingOn) {
      val elapsed = ChronoUnit.MILLIS.between(started, LocalDateTime.now()).toDouble / 1000.0
      logger.println(s"[     arjun     ][${context.s}][$elapsed] ${s.toString}")
      logger.flush()
    }
  }

  def selection(ref: ActorRef)(implicit context: ActorContext): ActorSelection = {
    context.actorSelection(ref.path)
  }

  def addressString(node: Node, localPath: String): String = {
    s"akka://$clusterName@${node.host}:${node.port}$localPath"
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
    toPrint: Option[String] = None,
    log: Boolean = true
  )(implicit context: ActorContext, logContext: ArjunContext): Unit = {
    val p = toPrint.getOrElse(msg.toString)
    if (min_delay == 0 && max_delay == 0 && fail_prob == 0) {
      ref ! msg
    } else if (rand.nextDouble > fail_prob) {
      import context.dispatcher
      val delay = rand_range(min_delay, max_delay).millis
      context.system.scheduler.scheduleOnce(delay)(ref ! msg)
      arjun(s"Delay of $delay milliseconds put on send of $p to $ref", log)
    } else {
      arjun(s"Dropped message due to unreliable send of $p to $ref", log)
    }
  }

  def unreliableSelection(
    ref: ActorSelection,
    msg: Any,
    max_delay: Int = Utils.max_delay,
    min_delay: Int = Utils.min_delay,
    fail_prob: Double = fail_prob,
    toPrint: Option[String] = None,
    log: Boolean = true
  )(implicit context: ActorContext, logContext: ArjunContext): Unit = {
    val p = toPrint.getOrElse(msg.toString)
    if (min_delay == 0 && max_delay == 0 && fail_prob == 0) {
      ref ! msg
    } else if (rand.nextDouble > fail_prob) {
      import context.dispatcher
      val delay = rand_range(min_delay, max_delay).millis
      context.system.scheduler.scheduleOnce(delay)(ref ! msg)
      arjun(s"Delay of $delay milliseconds put on send of $p to $ref", log)
    } else {
      arjun(s"Dropped message due to unreliable send of $p to $ref", log)
    }
  }
}

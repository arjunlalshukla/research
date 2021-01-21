import Utils.arjun

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import scala.collection.mutable
import scala.math.sqrt

final class IntervalStorage(var capacity: Int, initial_fill: Long)(implicit logContext: String) {
  if (capacity < 1) {
    throw new IllegalArgumentException
  }

  private[this] val intervals = mutable.ArrayDeque(initial_fill)
  private[this] var sumSquares = initial_fill*initial_fill
  private[this] var sum = initial_fill
  private[this] var _latest = LocalDateTime.now()

  def latest: LocalDateTime = _latest

  def millis_since_latest(now: LocalDateTime = LocalDateTime.now()): Long =
    ChronoUnit.MILLIS.between(latest, LocalDateTime.now())

  def full: Boolean = intervals.length >= capacity

  def push(): Unit = {
    while (full) {
      sum -= intervals.last
      sumSquares -= intervals.last * intervals.last
      intervals.removeLastOption()
    }
    val newLatest = LocalDateTime.now()
    intervals.prepend(
      if (intervals.length == 1) initial_fill else millis_since_latest(newLatest)
    )
    sum += intervals.head
    sumSquares += intervals.head * intervals.head
    _latest = newLatest
    arjun(s"Storage [${intervals.mkString(",")}]")
  }

  def mean: Double = sum.toDouble/intervals.length

  def stdev: Double = sqrt(sumSquares/intervals.length - mean*mean)

  // Copied directly from Akka PhiAccrualFailureDetector.scala
  def phi: Double = {
    val diff = ChronoUnit.MILLIS.between(latest, LocalDateTime.now())
    val y = (diff - mean) / stdev
    val e = math.exp(-y * (1.5976 + 0.070566 * y * y))
    if (diff > mean)
      -math.log10(e / (1.0 + e))
    else
      -math.log10(1.0 - 1.0 / (1.0 + e))
  }
}

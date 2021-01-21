import Utils.arjun

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import scala.collection.mutable
import scala.math.sqrt

final class IntervalStorage(var capacity: Int, initial_fill: Long)(implicit logContext: ArjunContext) {
  if (capacity < 1) {
    throw new IllegalArgumentException
  }

  private[this] val intervals = mutable.ArrayDeque(initial_fill*2, 0L)
  private[this] var sumSquares = intervals.map(x => x*x).sum
  private[this] var sum = intervals.sum
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
  }

  def mean: Double = sum.toDouble/intervals.length

  def stdev: Double = sqrt(sumSquares/intervals.length - mean*mean)

  def summary: String =
    s"phi = $phi; since latest = ${millis_since_latest()}; mean = $mean; " +
      s"stdev = $stdev; entries = [${intervals.mkString(",")}]"

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

import scala.collection.mutable

final class FrequencyBuffer[T](private[this] var capacity: Int) {
  val buffer = mutable.ArrayDeque.empty[T]
  private[this] var _frequencies = Map.empty[T, Int].withDefaultValue(0)
  private[this] var _changes = 0
  setCapacity(capacity)

  def changes: Int= _changes
  def frequencies: Map[T, Int] = _frequencies

  def removeLast(): Unit = buffer.removeLastOption().foreach { remove =>
    if (buffer.nonEmpty && buffer(buffer.length-1) != remove) {
      _changes -= 1
    }
    _frequencies(remove) match {
      case 0 | 1 => this._frequencies -= remove
      case n => this._frequencies += remove -> (n-1)
    }
  }

  def push(item: T): Unit = {
    while (buffer.length >= capacity) {
      removeLast()
    }
    if (buffer.nonEmpty && buffer.head != item) {
      _changes += 1
    }
    buffer.prepend(item)
    _frequencies += item -> (_frequencies(item) + 1)
  }

  def setCapacity(newCap: Int): Unit = {
    if (capacity < 1) {
      throw new IllegalArgumentException
    }
    capacity = newCap
    while (buffer.length > capacity) {
      removeLast()
    }
  }
}

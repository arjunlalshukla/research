import scala.collection.mutable.TreeMap
import scala.collection.mutable.ArrayBuffer

class NodeRing {
  private var ring = ArrayBuffer.empty[Node]

  def insert(node: Node): Unit = {
    ring.append(node)
    ring.sortInPlace()
  }

  def remove(node: Node): Unit = {
    ring.remove(ring.indexOf(node))
  }

  def responsibility(node: Node): Node = {
    val hash = node.murHash.toInt
    if (hash < 0) {
      ring((-hash) % ring.length)
    } else {
      ring(hash % ring.length)
    }
  }
}

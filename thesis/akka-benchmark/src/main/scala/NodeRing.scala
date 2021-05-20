import scala.collection.mutable.TreeMap

class NodeRing {
  private val ring = TreeMap.empty[Int, Node]

  def insert(node: Node): Unit = {
    ring.put(node.hashCode, node)
  }

  def remove(node: Node): Unit = {
    ring.remove(node.hashCode)
  }

  def responsibility(o: Object): Node = {
    val hash = o.hashCode()
    val rng = ring.rangeFrom(hash)
    if (rng.isEmpty) {
      ring.head._2
    } else {
      rng.head._2
    }
  }
}

import scala.collection.mutable.TreeMap

class NodeRing {
  private val ring = TreeMap.empty[Int, Node]

  def insert(node: Node): Unit = {
    ring.put(node.##, node)
  }

  def remove(node: Node): Unit = {
    ring.remove(node.##)
  }

  def responsibility(node: Node): Node = {
    val hash = node.##
    val rng = ring.rangeFrom(hash)
    if (rng.isEmpty) {
      ring.head._2
    } else {
      rng.head._2
    }
  }
}

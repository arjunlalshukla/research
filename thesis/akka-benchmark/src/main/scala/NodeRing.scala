import scala.collection.mutable.TreeMap
import scala.math.BigInt

class NodeRing {
  var ring = TreeMap.empty[BigInt, Node]
  val vnodes = 20

  def insert(node: Node): Unit = {
    //val rand = new Random(node.murHash)
    var h = node.murHash
    (1 to vnodes).foreach { _ =>
      h = Utils.hashBig(s"$h")
      ring.put(h, node)
    }
  }

  def remove(node: Node): Unit = {
    //val rand = new Random(node.murHash)
    var h = node.murHash
    (1 to vnodes).foreach { _ => 
      h = Utils.hashBig(s"$h")
      ring.remove(h)
    }
  }

  def responsibility(node: Node): Node = {
    val hash = node.murHash
    val range = ring.rangeFrom(hash)
    if (range.isEmpty) {
      ring.head._2
    } else {
      range.head._2
    }
  }
}

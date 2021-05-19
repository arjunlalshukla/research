import akka.cluster.ddata.{DeltaReplicatedData, Key, ReplicatedData, ReplicatedDataSerialization, ReplicatedDelta}

import scala.math.Ordering.Implicits.infixOrderingOps

case class DeviceEntry(removals: Long, hbi: Option[HeartbeatInterval]) extends Ordered[DeviceEntry] {
  def compare(that: DeviceEntry): Int = {
    val cmp_removals = removals.compare(that.removals)
    if (cmp_removals != 0) {
      cmp_removals
    } else {
      (hbi, that.hbi) match {
        case (None, None) => 0
        case (Some(_), None) => 1
        case (None, Some(_)) => -1
        case (Some(a), Some(b)) => a.compare(b)
      }
    }
  }
}

case class DevicesCrdtKey(_id: String) extends Key[DevicesCrdt](_id)

object DevicesCrdt {
  def empty = new DevicesCrdt(Map())
}
case class DevicesCrdt(
  states: Map[Node, DeviceEntry],
  del: Option[DevicesCrdt] = None
) extends DeltaReplicatedData with ReplicatedData with ReplicatedDelta {
  type D = DevicesCrdt

  def put(node: Node, interval: Int): DevicesCrdt = {
    val entry = states.get(node)
    if (entry.flatMap(_.hbi).exists(_.interval_millis == interval)) {
      this
    } else {
      val new_entry = entry match {
        case Some(DeviceEntry(removals, Some(HeartbeatInterval(clock, _)))) =>
          DeviceEntry(removals, Some(HeartbeatInterval(clock + 1, interval)))
        case Some(DeviceEntry(removals, None)) =>
          DeviceEntry(removals, Some(HeartbeatInterval(1, interval)))
        case None => DeviceEntry(0, Some(HeartbeatInterval(1, interval)))
      }
      val new_delta = del match {
        case Some(d) => new DevicesCrdt(d.states + (node -> new_entry))
        case None => new DevicesCrdt(Map(node -> new_entry))
      }
      new DevicesCrdt(states + (node -> new_entry), Some(new_delta))
    }
  }

  def remove(node: Node): DevicesCrdt = {
    val entry = states.get(node)
    val new_entry = entry match {
      case Some(DeviceEntry(removals, Some(_))) =>
        DeviceEntry(removals + 1, None)
      case _ => return this
    }
    val new_delta = del match {
      case Some(d) => new DevicesCrdt(d.states + (node -> new_entry))
      case None => new DevicesCrdt(Map(node -> new_entry))
    }
    new DevicesCrdt(states + (node -> new_entry), Some(new_delta))
  }

  def delta: Option[DevicesCrdt] = del

  def mergeDelta(thatDelta: DevicesCrdt): DevicesCrdt = this.merge(thatDelta)

  def resetDelta: DevicesCrdt = del match {
    case Some(_) => new DevicesCrdt(states)
    case None => this
  }

  type T = DevicesCrdt

  def merge(that: DevicesCrdt): DevicesCrdt = {
    val new_states = states.keySet.union(that.states.keySet).map { node =>
      (states.get(node), that.states.get(node)) match {
        case (None, Some(a)) => node -> a
        case (Some(a), None) => node -> a
        case (Some(a), Some(b)) => node -> (a max b)
      }
    }
    .toMap
    new DevicesCrdt(new_states)
  }

  def zero: DeltaReplicatedData = new DevicesCrdt(Map())
}

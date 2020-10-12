package multiple

import scala.collection.concurrent
import scala.concurrent.Future
import scala.util.Random
import scala.concurrent.ExecutionContext.global

sealed abstract class NetworkType[A <: Server[A]] {
  def elect(initiator: A): Unit
}

case class FullyConnected[A <: Server[A]]() extends NetworkType[A] {
  def elect(initiator: A): Unit = ???
}

abstract class Purpose[A <: Server[A], B] {
  def by(svr: A): B
  val ord: Ordering[B]
}

final case class ConcretePurpose() extends Purpose[ConcreteServer, Int] {
  def by(svr: ConcreteServer): Int = svr.pid
  val ord: Ordering[Int] = (x: Int, y: Int) => {
    if (x == y) 0
    else if (x > y) 1
    else -1
  }
}

case class ELECTION[A <: Server[A], B](pps: Purpose[A, B], to_cmp: B)
case class NEW_COOR[A <: Server[A]](pps: Purpose[A, _], svr: Server[A])
case class ACK()

abstract sealed class ElectionResponse[A]
case object LOWER extends ElectionResponse[Nothing]
case class HIGHER[A](to_cmp: A) extends ElectionResponse[A]

abstract class Server[A <: Server[A]] {
  val nt: NetworkType[A]
  val coors: collection.Map[Purpose[A, _], A]

  def new_coor(msg: NEW_COOR[A]): Future[ACK]
  def elect[B](msg: ELECTION[A, B]): Future[ElectionResponse[A]]
}

class ConcreteServer extends Server[ConcreteServer] {
  val pid: Int = new Random().nextInt()
  val nt: NetworkType[ConcreteServer] = FullyConnected()
  val coors: concurrent.Map[Purpose[ConcreteServer, _], ConcreteServer] = null

  def new_coor(msg: NEW_COOR[ConcreteServer]): Future[ACK] = ???

  def elect[B](msg: ELECTION[ConcreteServer, B]): Future[ElectionResponse[ConcreteServer]] = ???

  private[this] def foo[A](pps: Purpose[ConcreteServer, A]): Unit = {
    val v: A = pps.by(this)
  }

  private[this] def bar(): Unit = {
    val k = coors.keySet.
  }
}

object bar extends App {
  println("it type checks!")
}
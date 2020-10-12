package single

import scala.concurrent.ExecutionContext.global
import scala.concurrent.Future

sealed abstract class NetworkType[A] {
  def elect(initiator: Server[A]): Unit
}
case class FullyConnected[A]() extends NetworkType[A] {
  def elect(initiator: Server[A]): Unit = {
    implicit val g = global
    val my_res = initiator.by()
    val net = initiator.net() - initiator

    val f: Future[Set[(ElectionResponse[A], Server[A])]] = Future.sequence(
      net.map(svr => svr.elect(ELECTION(my_res)).zip(Future.successful(svr)))
    )

    val h: Future[Set[(A, Server[A])]] = f.map(
      _.map {
        case (h: HIGHER[A], svr) => Option((h.to_cmp, svr))
        case _ => None
      }
      .filter(_.nonEmpty)
      .map(_.get)
    )

    val i: Future[Server[A]] = h.map(svrs =>
      (svrs ++ Set((my_res, initiator))).maxBy(_._1)(initiator.ord)._2
    )

    i.foreach { new_coor =>
      (initiator.net() ++ Set(initiator)).map(_.new_coor(NEW_COOR(new_coor)))
    }
  }
}
//case object Ring extends single.NetworkType

case class ELECTION[A](to_cmp: A)
case class NEW_COOR[A](svr: Server[A])
case class ACK()

abstract sealed class ElectionResponse[A]
case object LOWER extends ElectionResponse[Nothing]
case class HIGHER[A](to_cmp: A) extends ElectionResponse[A]

abstract class Server[A] {
  val nt: NetworkType[A]
  def net(): Set[Server[A]]
  def by(): A
  val ord: Ordering[A]
  def new_coor(msg: NEW_COOR[A]): Future[ACK]
  def elect(msg: ELECTION[A]): Future[ElectionResponse[A]]
}

final class ConcreteServer extends Server[Int] {
  val nt: NetworkType[Int] = FullyConnected()

  def net(): Set[Server[Int]] = ???

  def by(): Int = ???

  val ord: Ordering[Int] = (x: Int, y: Int) => {
    if (x == y) 0
    else if (x > y) 1
    else -1
  }

  def new_coor(msg: NEW_COOR[Int]): Future[ACK] = ???

  def elect(msg: ELECTION[Int]): Future[ElectionResponse[Int]] = ???
}

object Foo extends App {
  println("done")
}
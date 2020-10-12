object Join extends App {
  case class OnFuncPair[A, L, R](left: L => A, right: R => A)

  def cartesian[L, R](left: Iterable[L], right: Iterable[R]): Iterable[(L, R)] =
    left.flatMap(l => right.map((l, _)))

  def outerJoin[L, R](
    left: Seq[L],
    right: Seq[R],
    ons: Seq[OnFuncPair[_, L, R]]
  ): Seq[(Option[L], Option[R])] = {
    val leftGroup = left.groupBy(l => ons.map(_.left(l)))
    val rightGroup = right.groupBy(r => ons.map(_.right(r)))

    leftGroup.keySet.union(rightGroup.keySet).toSeq
      .map(key => leftGroup.get(key) -> rightGroup.get(key))
      .flatMap {
        case (Some(l), Some(r)) =>
          cartesian(l, r).map(t => Option(t._1) -> Option(t._2))
        case (Some(l), None) => l.map(Option(_) -> None)
        case (None, Some(r)) => r.map(None -> Option(_))
      }
  }

  case class Foo(a: String, b: Int)
  case class Bar(a: String, b: String)

  val d = Seq(
    Foo("fds", 3),
    Foo("dsa", 4),
    Foo("fdsa", 4),
    Foo("dfaf", 3),
    Foo("f", 6)
  )

  val f = Seq(
    Bar("red", "3"),
    Bar("fds", "4"),
    Bar("asdf", "4"),
    Bar("tgvb", "3"),
    Bar("l", "5")
  )

  outerJoin(d, f, Seq(OnFuncPair((r: Foo) => r.b, (r: Bar) => r.b.toInt))).foreach(println)
}

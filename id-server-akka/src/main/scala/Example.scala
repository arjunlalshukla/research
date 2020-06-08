object Example extends App {
  def multiply(y: Int)(implicit foo: Int) = foo * y  * x

  implicit val x: Int = 5

  println(multiply(4))
}

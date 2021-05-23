import akka.actor.ActorSystem

object Main {
  def main(args: Array[String]): Unit = {
    val iter = args.iterator
    val mode = iter.next()

    mode match {
      case "server" => server(iter)
      case "client" => client(iter)
      case "killer" => killer(iter)
      case "collector" => collector(iter)
    }
  }

  def server(args: Iterator[String]) = {
    val host = args.next()
    val port = args.next().toInt

  }

  def client(args: Iterator[String]) = {}

  def killer(args: Iterator[String]) = {}

  def collector(args: Iterator[String]) = {}
}

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.global
import scala.concurrent.duration._

object ResourceLeak extends App {
  val f: Future[String] = Future {
    // infinite stream, will never finish
    for (i <- LazyList.from(0, 10)) {
      Thread.sleep(1000)
      println(s"future background thread, iter $i")
    }
    "done!"
  }(global)

  val res = try {
    val s = Await.result(f, 5.seconds)
    println(s"success, future value is '$s'")
    s
  } catch {
    case e: Throwable => println(s"failed with exception $e")
  }

  // just hang, the future thread will continue running in the background
  for (i <- LazyList.from(1, 10)) {
    Thread.sleep(1000)
    println(s"main thread, iter $i")
  }
}

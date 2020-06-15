
import akka.http.scaladsl.server.{Directives, Route}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json._


object Foo extends App {
  // domain model
  final case class Item(name: String, id: Long)
  final case class Order(items: List[Item])

  // collect your json format instances into a support trait:
  trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
    implicit val itemFormat: RootJsonFormat[Item] = jsonFormat2(Item)
    implicit val orderFormat: RootJsonFormat[Order] = jsonFormat1(Order) // contains List[Item]
  }

  // use it wherever json (un)marshalling is needed
  class MyJsonService extends Directives with JsonSupport {

    val route: Route =
      concat(
        get {
          pathSingleSlash {
            complete(Item("thing", 42)) // will render as JSON
          }
        },
        post {
          entity(as[Order]) { order => // will unmarshal JSON to Order
            val itemsCount = order.items.size
            val itemNames = order.items.map(_.name).mkString(", ")
            complete(s"Ordered $itemsCount items: $itemNames")
          }
        }
      )
  }
}

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.AskPattern._
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.scaladsl.Sink
import akka.util.Timeout

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

import IdServer._

object WebServer extends App {
  new WebServer(8080, 1)
}

final class WebServer(port: Int, reqTimeout: Int) {

  private[this] implicit val timeout: Timeout = reqTimeout.second
  private[this] implicit val system: ActorSystem[IdServer.Message] =
    ActorSystem(IdServer("Jane"), "MySystem")
  private[this] implicit val ec: ExecutionContextExecutor = system.executionContext

  Http(system).bind(interface = "localhost", port = port)
    .to(Sink.foreach { connection =>
      println("Accepted new connection from " + connection.remoteAddress)
      connection.handleWithAsyncHandler(requestHandler)
    }).run()

  private[this] def requestHandler(req: HttpRequest) =
    (req.method, req.uri.path.toString) match {
      case (GET, "/get-all") => getAll(req)
      case (GET, "/get-users") => getUsers(req)
      case (GET, "/get-uuids") => getUuids(req)
      case (GET, "/login-lookup") => loginLookup(req)
      case (GET, "/uuid-lookup") => uuidLookup(req)
      case _ => Future(HttpResponse(entity = "Page/method combo is invalid"))
    }

  private[this] def getAll(req: HttpRequest) = Future {
    if (req.uri.query().isEmpty)
      system.ref.ask[GetAllResponse](GetAll)
        .transform {
          case Success(res) => Success(
            HttpResponse(entity = s"good request: () -> $res")
          )
          case Failure(_) => Success(HttpResponse(entity = "server error"))
        }
    else
      Future(HttpResponse(entity = "bad request"))
  }.flatten

  private[this] def getUsers(req: HttpRequest) = Future {
    if (req.uri.query().isEmpty)
      system.ref.ask[GetUsersResponse](GetUsers)
        .transform {
          case Success(res) => Success(
            HttpResponse(entity = s"good request: () -> $res")
          )
          case Failure(_) => Success(HttpResponse(entity = "server error"))
        }
    else
      Future(HttpResponse(entity = "bad request"))
  }.flatten

  private[this] def getUuids(req: HttpRequest) = Future {
    if (req.uri.query().isEmpty)
      system.ref.ask[GetUuidsResponse](GetUuids)
        .transform {
          case Success(res) => Success(
            HttpResponse(entity = s"good request: () -> $res")
          )
          case Failure(e) => Success(
            HttpResponse(entity = s"server error: ${e.getMessage}")
          )
        }
    else
      Future(HttpResponse(entity = "bad request"))
  }.flatten

  private[this] def loginLookup(req: HttpRequest) = Future {
    val query = req.uri.query().toMultiMap
    if (query.keySet == Set("login") && query("login").length == 1) {
      val params = LoginLookup(query("login").head)
      system.ref.ask[LoginLookupResponse](LoginLookupInternal(_, params))
        .transform {
          case Success(res) => Success(
            HttpResponse(entity = s"good request: $params -> $res")
          )
          case Failure(e) => Success(
            HttpResponse(entity = s"server error: ${e.getMessage}")
          )
        }
    } else
      Future(HttpResponse(entity = "bad request"))
  }.flatten

  private[this] def uuidLookup(req: HttpRequest) = Future {
    val query = req.uri.query().toMultiMap
    if (query.keySet == Set("uuid") && query("uuid").length == 1
      && query("uuid").head.toLongOption.nonEmpty) {
      val params = UuidLookup(query("uuid").head.toInt)
      system.ref.ask[UuidLookupResponse](UuidLookupInternal(_, params))
        .transform {
          case Success(res) => Success(
            HttpResponse(entity = s"good request: $params -> $res")
          )
          case Failure(e) => Success(
            HttpResponse(entity = s"server error: ${e.getMessage}")
          )
        }
    } else
      Future(HttpResponse(entity = "bad request"))
  }.flatten
}
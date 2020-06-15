import akka.actor.typed.{ActorRef, ActorSystem}
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

final class WebServer(
  val interface: String,
  val port: Int,
  system: ActorSystem[Nothing],
  server: ActorRef[Message[_]],
  reqTimeout: Int
) {

  private[this] implicit val timeout: Timeout = reqTimeout.second
  private[this] implicit val s: ActorSystem[Nothing] = system
  private[this] implicit val ec: ExecutionContextExecutor = system.executionContext

  Http(system)
    .bind(interface = interface, port = port)
    .to(Sink.foreach { connection =>
      println("Accepted new connection from " + connection.remoteAddress)
      connection.handleWithAsyncHandler(requestHandler)
    }).run()

  private[this] def requestHandler(req: HttpRequest): Future[HttpResponse] =
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
      server.ask[Message[GetAllResponse]](GetAll)
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

  private[this] def getUsers(req: HttpRequest) = Future {
    if (req.uri.query().isEmpty)
      server.ask[Message[GetUsersResponse]](GetUsers)
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

  private[this] def getUuids(req: HttpRequest) = Future {
    if (req.uri.query().isEmpty)
      server.ask[Message[GetUuidsResponse]](GetUuids)
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
      server.ask[Message[LoginLookupResponse]](LoginLookupInternal(_, params))
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
      server.ask[Message[UuidLookupResponse]](UuidLookupInternal(_, params))
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
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.actor.typed.scaladsl.AskPattern.{Askable, schedulerFromActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ContentTypes.`application/x-www-form-urlencoded`
import akka.http.scaladsl.model.HttpMethods.{GET, POST}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.model.HttpEntity.Strict
import akka.http.scaladsl.model.Uri.Query
import akka.stream.scaladsl.Sink
import akka.util.Timeout
import org.mongodb.scala.bson.ObjectId

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}
import IdServer.{Create, CreateInternal, Delete, DeleteInternal, ExternalRequest, ExternalResponse, GetAll, GetAllResponse, GetUsers, GetUsersResponse, GetUuids, GetUuidsResponse, LoginLookup, LoginLookupInternal, LoginLookupResponse, Message, Modify, ModifyInternal, UuidLookup, UuidLookupInternal, UuidLookupResponse}

final class WebServer(
  val interface: String,
  val port: Int,
  system: ActorSystem[Nothing],
  server: ActorRef[Message],
  reqTimeout: Int
) {

  private[this] implicit val timeout: Timeout = reqTimeout.second
  private[this] implicit val s: ActorSystem[Nothing] = system
  private[this] implicit val ec: ExecutionContextExecutor = system.executionContext
  private[this] val oid_regex = "[0-9a-fA-F]{24}".r

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
      case (POST, "/create") => create(req)
      case (POST, "/delete") => delete(req)
      case (POST, "/modify") => modify(req)
      case _ => Future(HttpResponse(entity = "Page/method combo is invalid"))
    }

  private[this] def getProcess[A, B <: ExternalRequest[_], C](
    req: HttpRequest,
    validate: Map[String, List[String]] => Boolean,
    getParams: Map[String, List[String]] => C,
    getRequest: (ActorRef[ExternalResponse[A]], C) => B
  ) = Future {
    val query = req.uri.query().toMultiMap
    if (validate(query)) {
      val params = getParams(query)
      server.ask[ExternalResponse[A]](getRequest(_, params))
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

  private[this] def getAll(req: HttpRequest) = getProcess(req,
    _.isEmpty,
    _ => None,
    (a: ActorRef[ExternalResponse[GetAllResponse]], _: Option[Nothing]) =>
      GetAll(a)
  )

  private[this] def getUsers(req: HttpRequest) = getProcess(req,
    _.isEmpty,
    _ => None,
    (a: ActorRef[ExternalResponse[GetUsersResponse]], _: Option[Nothing]) =>
      GetUsers(a)
  )

  private[this] def getUuids(req: HttpRequest) = getProcess(req,
    _.isEmpty,
    _ => None,
    (a: ActorRef[ExternalResponse[GetUuidsResponse]], _: Option[Nothing]) =>
      GetUuids(a)
  )

  private[this] def loginLookup(req: HttpRequest) = getProcess(req,
    q => q.keySet == Set("login") && q("login").length == 1,
    q => LoginLookup(q("login").head),
    LoginLookupInternal.apply
  )

  private[this] def uuidLookup(req: HttpRequest) = getProcess(req,
    q => q.keySet == Set("uuid") && q("uuid").length == 1 &&
      oid_regex.matches(q("uuid").head),
    q => UuidLookup(new ObjectId(q("uuid").head)),
    UuidLookupInternal.apply
  )

  private[this] def postProcess[A, B <: ExternalRequest[_], C](
    req: HttpRequest,
    validate: Map[String, List[String]] => Boolean,
    getParams: Map[String, List[String]] => C,
    getRequest: (ActorRef[ExternalResponse[A]], C) => B
  ) = {
    Future {
      (req.entity match {
        case Strict(`application/x-www-form-urlencoded`, data) =>
          Some(Query(data.utf8String).toMultiMap)
        case _ => None
      })
      .filter(validate) match {
        case None => Future(HttpResponse(entity = "bad request"))
        case Some(query) =>
          val params = getParams(query)
          server.ask[ExternalResponse[A]](getRequest(_, params))
            .transform {
              case Success(res) => Success(
                HttpResponse(entity = s"good request: $params -> $res")
              )
              case Failure(e) => Success(
                HttpResponse(entity = s"server error: ${e.getMessage}")
              )
            }
      }
    }.flatten
  }

  private[this] def create(req: HttpRequest) = postProcess(req,
    q => q.keySet == Set("login", "name", "passw") && q.forall(_._2.length == 1),
    q => Create(q("login").head, q("name").head, q("passw").head),
    CreateInternal.apply
  )

  private[this] def delete(req: HttpRequest) = postProcess(req,
    q => q.keySet == Set("login", "passw") && q.forall(_._2.length == 1),
    q => Delete(q("login").head, q("passw").head),
    DeleteInternal.apply
  )

  private[this] def modify(req: HttpRequest) = postProcess(req,
    q => q.keySet == Set("login", "name", "passw") && q.forall(_._2.length == 1),
    q => Modify(q("login").head, q("name").head, q("passw").head),
    ModifyInternal.apply
  )
}
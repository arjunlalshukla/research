import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.receptionist.Receptionist.{Deregister, Deregistered, Find, Listing, Register, Registered}
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import akka.actor.typed.scaladsl.AskPattern.{Askable, schedulerFromActorSystem}
import akka.util.Timeout
import com.google.common.hash.Hashing.sha512
import java.net.InetAddress
import java.nio.charset.StandardCharsets.US_ASCII
import java.security.SecureRandom

import org.apache.commons.lang3.RandomStringUtils.random
import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
import org.mongodb.scala.{MongoClient, MongoCollection}
import org.mongodb.scala.MongoClient.DEFAULT_CODEC_REGISTRY
import org.mongodb.scala.bson.ObjectId
import org.mongodb.scala.bson.codecs.Macros.createCodecProvider

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContextExecutor, Future}
import spray.json._
import IdServer.{GetHttp, HttpServer, Message, allNodes}

import scala.util.{Failure, Success}

case class User(
  login: String,
  name: String,
  pw_hash: String,
  salt: String,
  _id: ObjectId = new ObjectId()
)

object IdServer {

  sealed trait Message {
    private[IdServer] def process(ids: IdServer): Future[Unit]
  }

  sealed trait Response[+A] extends Message {
    final private[IdServer] def process(ids: IdServer): Future[Unit] =
      Future.unit
  }
  sealed trait InternalResponse[+A] extends Response[A]
  sealed trait ExternalResponse[+A] extends Response[A]

  sealed trait Request[A <: Response[Any]] extends Message {
    private[IdServer] def execute(ids: IdServer): Unit
  }
  sealed trait InternalRequest[A <: InternalResponse[_]] extends Request[A] {
    val sender: ActorRef[InternalResponse[Nothing]]
    final private[IdServer] def process(ids: IdServer): Future[Unit] =
      Future(execute(ids))(ids.ec)
  }
  sealed trait ExternalRequest[A <: ExternalResponse[_]] extends Request[A] {
    val sender: ActorRef[ExternalResponse[Nothing]]
    final private[IdServer] def process(ids: IdServer): Future[Unit] = {
      import ids.{askTimeout, ec, system}
      val logger = org.slf4j.LoggerFactory.getLogger("FOO_Logger")
      system.receptionist.ask[Listing](Find(coorKey, _))
        .map(_.serviceInstances(coorKey).toList)
        .map {
          case Nil =>
            ids.newCoor().map(_.foreach { actor =>
              logger.info("adding new coor")
              actor.ask(CoorThyself)
            }).transformWith { _ =>
              logger.info("starting process again")
              process(ids)
            }
          case first :: rest =>
            Future.sequence(rest.map { actor =>
              logger.info(s"removing $actor from coordinators")
              actor.ask(UncoorThyself)
            }).transformWith(_ =>
              if (first == ids.context.self) {
                logger.info("i am the coordinator")
                Future {
                  execute(ids)
                }
              } else {
                logger.info("the coordinator, i am not")
                first.ask(GetHttp).map {
                  case hs: HttpServer => sender.tell(IAmNotTheCoor(hs))
                }
              }
            )
        }
    }
  }

  private val allNodes = ServiceKey[Message]("all-node-key")
  private val coorKey = ServiceKey[Message]("coor-key")

  private val rand = new SecureRandom
  private[this] val saltLen = 16

  final case class HttpServer(host: String, port: Int)
    extends InternalResponse[HttpServer] {
    lazy val sortKey: Long = {
      val x = host.split("\\.").map(_.toLong)
      (x(0)<<40) | (x(1)<<32) | (x(2)<<24) | (x(3)<<16) | (port & 0xffff)
    }
  }
  final case class GetHttp(sender: ActorRef[InternalResponse[HttpServer]])
    extends InternalRequest[HttpServer] {
    private[IdServer] def execute(ids: IdServer): Unit = {
      sender ! HttpServer(InetAddress.getLocalHost.getHostAddress, ids.httpPort)
    }
  }

  final case class IAmUncoored() extends InternalResponse[IAmUncoored]
  final case class UncoorThyself(sender: ActorRef[InternalResponse[IAmUncoored]])
    extends InternalRequest[IAmUncoored] {
    private[IdServer] def execute(ids: IdServer): Unit = {
      import ids.{askTimeout, system, ec}
      system.receptionist.ask[Deregistered](Deregister(coorKey, ids.ctx.self, _))
        .transformWith( _ => Future { sender ! IAmUncoored() })
    }
  }

  final case class IAmCoored() extends InternalResponse[IAmCoored]
  final case class CoorThyself(sender: ActorRef[InternalResponse[IAmCoored]])
    extends InternalRequest[IAmCoored] {
    private[IdServer] def execute(ids: IdServer): Unit = {
      import ids._
      system.receptionist.ask[Registered](Register(coorKey, ids.ctx.self, _))
        .transformWith( _ => Future { sender ! IAmCoored() })
    }
  }

  final case class IAmNotTheCoor(hs: HttpServer)
    extends ExternalResponse[Nothing]

  final case class ExternalRequestFailed(e: Throwable)
    extends ExternalResponse[Nothing]

  final case class CreateResponse(id: Option[ObjectId], msg: String)
    extends ExternalResponse[CreateResponse]
  final case class Create(login: String, name: String, passw: String)
  final case class CreateInternal(
    sender: ActorRef[ExternalResponse[CreateResponse]],
    req: Create
  ) extends ExternalRequest[ExternalResponse[CreateResponse]] {
    private[IdServer] def execute(ids: IdServer): Unit = {
      val u = User(req.login, req.name, "hash", "salt")
      // sha512.hashString(passw, US_ASCII).toString
      // random(saltLen, 0, 0, false, false, null, rand)
      sender ! CreateResponse(Some(u._id), s"Created $u")
    }
  }

  final case class ModifyResponse(msg: String)
    extends ExternalResponse[ModifyResponse]
  final case class Modify(login: String, newName: String, passw: String)
  final case class ModifyInternal(sender: ActorRef[ExternalResponse[ModifyResponse]], req: Modify)
    extends ExternalRequest[ExternalResponse[ModifyResponse]] {
    private[IdServer] def execute(ids: IdServer): Unit = {
      sender ! ModifyResponse("failure")
    }
  }

  final case class DeleteResponse(msg: String)
    extends ExternalResponse[DeleteResponse]
  case class Delete(login: String, passw: String)
  final case class DeleteInternal(
    sender: ActorRef[ExternalResponse[DeleteResponse]],
    req: Delete
  ) extends ExternalRequest[ExternalResponse[DeleteResponse]] {
    private[IdServer] def execute(ids: IdServer): Unit = {
      sender ! DeleteResponse("failure")
    }
  }

  final case class LoginLookupResponse(user: User)
    extends ExternalResponse[LoginLookupResponse]
  final case class LoginLookup(login: String)
  final case class LoginLookupInternal(
    sender: ActorRef[ExternalResponse[LoginLookupResponse]],
    req: LoginLookup
  ) extends ExternalRequest[ExternalResponse[LoginLookupResponse]] {
    private[IdServer] def execute(ids: IdServer): Unit = {
      sender ! LoginLookupResponse(User(req.login, "doe", "foo", "bar"))
    }
  }

  final case class UuidLookupResponse(user: User)
    extends ExternalResponse[UuidLookupResponse]
  final case class UuidLookup(uuid: Long)
  final case class UuidLookupInternal(
    sender: ActorRef[ExternalResponse[UuidLookupResponse]],
    req: UuidLookup
  ) extends ExternalRequest[ExternalResponse[UuidLookupResponse]] {
    private[IdServer] def execute(ids: IdServer): Unit = {
      sender ! UuidLookupResponse(User("chocolate", "cake", "is", "good"))
    }
  }

  final case class GetAllResponse(users: Seq[User])
    extends ExternalResponse[GetAllResponse]
  final case class GetAll(sender: ActorRef[ExternalResponse[GetAllResponse]])
    extends ExternalRequest[ExternalResponse[GetAllResponse]] {
    private[IdServer] def execute(ids: IdServer): Unit = {
      ids.usersDB.find.toFuture.onComplete {
        case Success(seq) => sender ! GetAllResponse(seq)
        case Failure(e) => sender ! ExternalRequestFailed(e)
      }(ids.ec)
    }
  }

  final case class GetUsersResponse(users: Seq[String])
    extends ExternalResponse[GetUsersResponse]
  final case class GetUsers(sender: ActorRef[ExternalResponse[GetUsersResponse]])
    extends ExternalRequest[ExternalResponse[GetUsersResponse]] {
    private[IdServer] def execute(ids: IdServer): Unit = {
      ids.usersDB.find.toFuture.onComplete {
        case Success(seq) => sender ! GetUsersResponse(seq.map(_.login))
        case Failure(e) => sender ! ExternalRequestFailed(e)
      }(ids.ec)
    }
  }

  final case class GetUuidsResponse(users: Seq[ObjectId])
    extends ExternalResponse[GetUuidsResponse]
  final case class GetUuids(sender: ActorRef[ExternalResponse[GetUuidsResponse]])
    extends ExternalRequest[ExternalResponse[GetUuidsResponse]] {
    private[IdServer] def execute(ids: IdServer): Unit = {
      ids.usersDB.find.toFuture.onComplete {
        case Success(seq) => sender ! GetUuidsResponse(seq.map(_._id))
        case Failure(e) => sender ! ExternalRequestFailed(e)
      }(ids.ec)
    }
  }

  /*
  object MessageJsonProtocol extends DefaultJsonProtocol {
    //implicit val userFormat: RootJsonFormat[User] = jsonFormat6(User)
    implicit val createFormat: RootJsonFormat[Create] = jsonFormat3(Create)
    implicit val createResponseFormat: RootJsonFormat[CreateResponse] =
      jsonFormat2(CreateResponse)
    implicit val modifyFormat: RootJsonFormat[Modify] = jsonFormat3(Modify)
    implicit val modifyResponseFormat: RootJsonFormat[ModifyResponse] =
      jsonFormat1(ModifyResponse)
    implicit val deleteFormat: RootJsonFormat[Delete] = jsonFormat2(Delete)
    implicit val deleteResponseFormat: RootJsonFormat[DeleteResponse] =
      jsonFormat1(DeleteResponse)
    implicit val loginFormat: RootJsonFormat[LoginLookup] =
      jsonFormat1(LoginLookup)
    implicit val loginResponseFormat: RootJsonFormat[LoginLookupResponse] =
      jsonFormat1(LoginLookupResponse)
    implicit val uuidFormat: RootJsonFormat[UuidLookup] =
      jsonFormat1(UuidLookup)
    implicit val uuidResponseFormat: RootJsonFormat[UuidLookupResponse] =
      jsonFormat1(UuidLookupResponse)
    implicit val allResponseFormat: RootJsonFormat[GetAllResponse] =
      jsonFormat1(GetAllResponse)
    implicit val usersResponseFormat: RootJsonFormat[GetUsersResponse] =
      jsonFormat1(GetUsersResponse)
    implicit val uuidsResponseFormat: RootJsonFormat[GetUuidsResponse] =
      jsonFormat1(GetUuidsResponse)
    implicit val httpServerFormat: RootJsonFormat[HttpServer] =
      jsonFormat2(HttpServer)
  }
  */

  def apply(name: String, httpPort: Int): Behavior[Message] =
    Behaviors.setup(new IdServer(_, name, httpPort))
}

final class IdServer(
  context: ActorContext[Message],
  val name: String,
  val httpPort: Int
) extends AbstractBehavior[Message](context) {

  private val ctx = context
  private implicit val system: ActorSystem[Nothing] = ctx.system
  private implicit val ec: ExecutionContextExecutor =
    ctx.system.executionContext
  // mysterious maximum delay given by Akka
  private implicit val askTimeout: Timeout = 21474835.second

  private val usersDB: MongoCollection[User] = MongoClient().getDatabase("id-db").withCodecRegistry(
    fromRegistries(fromProviders(classOf[User]), DEFAULT_CODEC_REGISTRY )
  ).getCollection("users")

  ctx.system.receptionist ! Register(allNodes, ctx.self)

  private val webServer =
    new WebServer("localhost", httpPort, ctx.system, ctx.self, 240)

  def onMessage(msg: Message): Behavior[Message] = {
    ctx.log.info("Got message: {}", msg)
    println(msg)
    msg.process(this)
    this
  }

  private def newCoor(): Future[Option[ActorRef[Message]]] = {
    system.receptionist.ask[Listing](Find(allNodes, _)).map( listing =>
      Future.sequence(listing.serviceInstances(allNodes).map(node =>
        node.ask(GetHttp).map { case hs: HttpServer => (node, hs)} )
      )
    ).flatten.map(_.maxByOption(_._2.sortKey)).map(_.map(_._1))
  }
}


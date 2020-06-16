import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, Scheduler}
import java.security.SecureRandom

import akka.actor.typed.scaladsl.AskPattern._
import org.apache.commons.lang3.RandomStringUtils.random
import com.google.common.hash.Hashing.sha512
import java.nio.charset.StandardCharsets.US_ASCII

import scala.concurrent.duration._
import spray.json._
import IdServer._
import akka.actor.typed.pubsub.Topic
import akka.actor.typed.receptionist.{Receptionist, ServiceKey}
import akka.actor.typed.receptionist.Receptionist._
import java.net.InetAddress

import akka.util.Timeout

import scala.collection.Set
import scala.collection.immutable.Set
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.util.{Failure, Success, Try}

case class User(
  login: String,
  name: String,
  uuid: Long,
  pw_hash: String,
  salt: String
)

object IdServer {

  private val allNodes = ServiceKey[Message[_]]("all-node-key")
  private val coorKey = ServiceKey[Message[_]]("coor-key")

  private val rand = new SecureRandom
  private[this] val saltLen = 16

  sealed abstract class Message[+A] {
    private[IdServer] def process(ids: IdServer): Future[Unit] =
      Future(validReq(ids))(ids.system.executionContext)
    private[IdServer] def validReq(ids: IdServer): Unit = ()
    private[IdServer] def notTheCoor(hs: HttpServer): Unit = ()
  }

  sealed abstract class External[+A] extends Message[A] {
    final private[IdServer] override def process(ids: IdServer): Future[Unit] = {
      import ids._
      system.receptionist.ask[Listing](Find(coorKey, _))
        .map(_.serviceInstances(coorKey).toList)
        .map {
          case Nil =>
            newCoor(ids).map(_.foreach{ actor =>
              println("adding new coor")
              actor.ask[IAmCoored](CoorThyself)
            }).transformWith{ _ =>
              println("starting process again")
              process(ids)
            }
          case first :: rest =>
            Future.sequence(rest.map{ actor =>
              println(s"removing $actor from coordinators")
              actor.ask[IAmUncoored](UncoorThyself)
            }).transformWith( _ =>
              if (first == context.self) {
                println("i am the coordinator")
                Future { validReq(ids) }
              } else {
                println("the coordinator, i am not")
                first.ask[HttpServer](GetHttp).map(notTheCoor)
              }
            )
        }
    }

    private[this] def newCoor(ids: IdServer): Future[Option[ActorRef[Message[_]]]] = {
      import ids._
      ids.system.receptionist.ask[Listing](Find(allNodes, _)).map( listing =>
        Future.sequence(listing.serviceInstances(allNodes).map(node =>
          node.ask[HttpServer](GetHttp).map((node, _))))
      ).flatten.map(_.maxByOption { tup =>
        val x = tup._2.host.split("\\.").map(_.toLong)
        (x(0)<<40) | (x(1)<<32) | (x(2)<<24) | (x(3)<<16) | (tup._2.port & 0xffff)
      }).map(_.map(_._1))
    }
  }

  final case class IAmNotTheCoor(hs: HttpServer) extends Message[Nothing]

  final case class HttpServer(host: String, port: Int)
    extends Message[HttpServer]
  final case class GetHttp(sender: ActorRef[HttpServer])
    extends Message[GetHttp] {
    private[IdServer] override def validReq(ids: IdServer): Unit = {
      sender ! HttpServer(InetAddress.getLocalHost.getHostAddress, ids.httpPort)
    }
  }

  final case class IAmUncoored() extends Message[IAmUncoored]
  final case class UncoorThyself(sender: ActorRef[IAmUncoored])
    extends Message[UncoorThyself] {
    private[IdServer] override def validReq(ids: IdServer): Unit = {
      import ids._
      system.receptionist.ask[Deregistered](Deregister(coorKey, ids.ctx.self, _))
        .transformWith( _ => Future { sender ! IAmUncoored() })
    }
  }

  final case class IAmCoored() extends Message[IAmCoored]
  final case class CoorThyself(sender: ActorRef[IAmCoored])
    extends Message[CoorThyself] {
    private[IdServer] override def validReq(ids: IdServer): Unit = {
      import ids._
      system.receptionist.ask[Registered](Register(coorKey, ids.ctx.self, _))
        .transformWith( _ => Future { sender ! IAmCoored() })
    }
  }

  final case class CreateResponse(uuid: Option[Long], msg: String)
    extends Message[CreateResponse]
  final case class Create(login: String, name: String, passw: String)
  final case class CreateInternal(sender: ActorRef[Message[CreateResponse]], req: Create)
    extends External[CreateInternal] {
    private[IdServer] override def validReq(ids: IdServer): Unit = {
      val u = User(req.login, req.name, 12, "hash", "salt")
      // sha512.hashString(passw, US_ASCII).toString
      // random(saltLen, 0, 0, false, false, null, rand)
      sender ! CreateResponse(Some(u.uuid), s"Created $u")
    }

    private[IdServer] override def notTheCoor(hs: HttpServer): Unit =
      sender ! IAmNotTheCoor(hs)
  }

  final case class ModifyResponse(msg: String) extends Message[ModifyResponse]
  final case class Modify(login: String, newName: String, passw: String)
  final case class ModifyInternal(sender: ActorRef[Message[ModifyResponse]], req: Modify)
    extends External[ModifyInternal] {
    private[IdServer] override def validReq(ids: IdServer): Unit = {
      sender ! ModifyResponse("failure")
    }

    private[IdServer] override def notTheCoor(hs: HttpServer): Unit =
      sender ! IAmNotTheCoor(hs)
  }

  final case class DeleteResponse(msg: String) extends Message[DeleteResponse]
  case class Delete(login: String, passw: String)
  final case class DeleteInternal(sender: ActorRef[Message[DeleteResponse]], req: Delete)
    extends External[DeleteInternal] {
    private[IdServer] override def validReq(ids: IdServer): Unit = {
      sender ! DeleteResponse("failure")
    }

    private[IdServer] override def notTheCoor(hs: HttpServer): Unit =
      sender ! IAmNotTheCoor(hs)
  }

  final case class LoginLookupResponse(user: User) extends Message[LoginLookupResponse]
  final case class LoginLookup(login: String)
  final case class LoginLookupInternal(
    sender: ActorRef[Message[LoginLookupResponse]],
    req: LoginLookup
  ) extends External[LoginLookupInternal] {
    private[IdServer] override def validReq(ids: IdServer): Unit = {
      sender ! LoginLookupResponse(User(req.login, "doe", 0, "foo", "bar"))
    }

    private[IdServer] override def notTheCoor(hs: HttpServer): Unit =
      sender ! IAmNotTheCoor(hs)
  }

  final case class UuidLookupResponse(user: User) extends Message[UuidLookupResponse]
  final case class UuidLookup(uuid: Long)
  final case class UuidLookupInternal(
    sender: ActorRef[Message[UuidLookupResponse]],
    req: UuidLookup
  ) extends External[UuidLookupInternal] {
    private[IdServer] override def validReq(ids: IdServer): Unit = {
      sender ! UuidLookupResponse(User("chocolate", "cake", req.uuid, "is", "good"))
    }

    private[IdServer] override def notTheCoor(hs: HttpServer): Unit =
      sender ! IAmNotTheCoor(hs)
  }

  final case class GetAllResponse(users: Seq[User])
    extends  Message[GetAllResponse]
  final case class GetAll(sender: ActorRef[Message[GetAllResponse]])
    extends External[GetAll] {
    private[IdServer] override def validReq(ids: IdServer): Unit = {
      sender ! GetAllResponse(Seq(
        User("zelda", "really", 2, "likes", "pringles"),
        User("snails", "move", 3, "rather", "slowly")
      ))
    }

    private[IdServer] override def notTheCoor(hs: HttpServer): Unit =
      sender ! IAmNotTheCoor(hs)
  }

  final case class GetUsersResponse(users: Seq[String])
    extends Message[GetUsersResponse]
  final case class GetUsers(sender: ActorRef[Message[GetUsersResponse]])
    extends External[GetUsers] {
    private[IdServer] override def validReq(ids: IdServer): Unit = {
      sender ! GetUsersResponse(Seq("the", "cloud", "is", "my", "butt"))
    }

    private[IdServer] override def notTheCoor(hs: HttpServer): Unit =
      sender ! IAmNotTheCoor(hs)
  }

  final case class GetUuidsResponse(users: Seq[Long])
    extends Message[GetUuidsResponse]
  final case class GetUuids(sender: ActorRef[Message[GetUuidsResponse]])
    extends External[GetUuids] {
    private[IdServer] override def validReq(ids: IdServer): Unit = {
      sender ! GetUuidsResponse(Seq(2, 3, 5, 7, 11, 13, 17, 19))
    }

    private[IdServer] override def notTheCoor(hs: HttpServer): Unit =
      sender ! IAmNotTheCoor(hs)
  }

  object MessageJsonProtocol extends DefaultJsonProtocol {
    implicit val userFormat: RootJsonFormat[User] = jsonFormat5(User)
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

  def apply(name: String, httpPort: Int): Behavior[Message[_]] =
    Behaviors.setup(new IdServer(_, name, httpPort))
}

final class IdServer(
  context: ActorContext[Message[_]],
  val name: String,
  val httpPort: Int
) extends AbstractBehavior[Message[_]](context) {

  private val ctx = context
  private implicit val system: ActorSystem[Nothing] = ctx.system
  private implicit val ec: ExecutionContextExecutor =
    ctx.system.executionContext
  // mysterious maximum delay given by Akka
  private implicit val askTimeout: Timeout = 21474835.second

  val id: Long = rand.nextLong

  ctx.system.receptionist ! Register(allNodes, ctx.self)

  println("finished startup")

  private val webServer =
    new WebServer("localhost", httpPort, ctx.system, ctx.self, 240)

  def onMessage(msg: Message[_]): Behavior[Message[_]] = {
    ctx.log.info("Got message: {}", msg)
    println(msg)
    msg.process(this)
    this
  }
}


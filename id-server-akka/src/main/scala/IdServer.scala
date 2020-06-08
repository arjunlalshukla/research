import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import java.security.SecureRandom
import org.apache.commons.lang3.RandomStringUtils.random
import com.google.common.hash.Hashing.sha512
import java.nio.charset.StandardCharsets.US_ASCII
import spray.json._

import IdServer._

case class User(
  login: String,
  name: String,
  uuid: Long,
  pw_hash: String,
  salt: String
)

object IdServer {

  private[this] val rand = new SecureRandom
  private[this] val saltLen = 16

  sealed abstract class Message {
    private[IdServer] def process(ids: IdServer): Behavior[Message] = ids
  }

  object MessageJsonProtocol extends DefaultJsonProtocol {
    implicit val userFormat           = jsonFormat5(User)
    implicit val createFormat         = jsonFormat3(Create)
    implicit val createResponseFormat = jsonFormat2(CreateResponse)
    implicit val modifyFormat         = jsonFormat3(Modify)
    implicit val modifyResponseFormat = jsonFormat1(ModifyResponse)
    implicit val deleteFormat         = jsonFormat2(Delete)
    implicit val deleteResponseFormat = jsonFormat1(DeleteResponse)
    implicit val loginFormat          = jsonFormat1(LoginLookup)
    implicit val loginResponseFormat  = jsonFormat1(LoginLookupResponse)
    implicit val uuidFormat           = jsonFormat1(UuidLookup)
    implicit val uuidResponseFormat   = jsonFormat1(UuidLookupResponse)
    implicit val allResponseFormat    = jsonFormat1(GetAllResponse)
    implicit val usersResponseFormat  = jsonFormat1(GetUsersResponse)
    implicit val uuidsResponseFormat  = jsonFormat1(GetUuidsResponse)
  }

  final case class CreateResponse(uuid: Option[Long], msg: String)
    extends Message
  final case class Create(login: String, name: String, passw: String)
  final case class CreateInternal(sender: ActorRef[CreateResponse], req: Create)
    extends Message {
    private[IdServer] override def process(ids: IdServer): Behavior[Message] = {
      val u = User(req.login, req.name, 12, "hash", "salt")
      // sha512.hashString(passw, US_ASCII).toString
      // random(saltLen, 0, 0, false, false, null, rand)
      sender ! CreateResponse(Some(u.uuid), s"Created $u")
      ids
    }
  }

  final case class ModifyResponse(msg: String) extends Message
  final case class Modify(login: String, newName: String, passw: String)
  final case class ModifyInternal(sender: ActorRef[ModifyResponse], req: Modify)
    extends Message {
    private[IdServer] override def process(ids: IdServer): Behavior[Message] = {
      ids.context.log.info("Modify received, request id is {}", req)
      sender ! ModifyResponse("failure")
      ids.context.log.info("ModifyResponse sent")
      ids
    }
  }

  final case class DeleteResponse(msg: String) extends Message
  case class Delete(login: String, passw: String)
  final case class DeleteInternal(sender: ActorRef[DeleteResponse], req: Delete)
    extends Message {
    private[IdServer] override def process(ids: IdServer): Behavior[Message] = {
      sender ! DeleteResponse("failure")
      ids
    }
  }

  final case class LoginLookupResponse(user: User) extends Message
  final case class LoginLookup(login: String)
  final case class LoginLookupInternal(
    sender: ActorRef[LoginLookupResponse],
    req: LoginLookup
  ) extends Message {
    private[IdServer] override def process(ids: IdServer): Behavior[Message] = {
      sender ! LoginLookupResponse(User(req.login, "doe", 0, "foo", "bar"))
      ids
    }
  }

  final case class UuidLookupResponse(user: User) extends Message
  final case class UuidLookup(uuid: Long)
  final case class UuidLookupInternal(
    sender: ActorRef[UuidLookupResponse],
    req: UuidLookup
  ) extends Message {
    private[IdServer] override def process(ids: IdServer): Behavior[Message] = {
      sender ! UuidLookupResponse(User("chocolate", "cake", req.uuid, "is", "good"))
      ids
    }
  }

  final case class GetAllResponse(users: Seq[User])
    extends  Message
  final case class GetAll(sender: ActorRef[GetAllResponse])
    extends Message {
    private[IdServer] override def process(ids: IdServer): Behavior[Message] = {
      sender ! GetAllResponse(Seq(
        User("zelda", "really", 2, "likes", "pringles"),
        User("snails", "move", 3, "rather", "slowly")
      ))
      ids
    }
  }

  final case class GetUsersResponse(users: Seq[String])
    extends Message
  final case class GetUsers(sender: ActorRef[GetUsersResponse])
    extends Message {
    private[IdServer] override def process(ids: IdServer): Behavior[Message] = {
      sender ! GetUsersResponse(Seq("the", "cloud", "is", "my", "butt"))
      ids
    }
  }

  final case class GetUuidsResponse(users: Seq[Long])
    extends Message
  final case class GetUuids(sender: ActorRef[GetUuidsResponse])
    extends Message {
    private[IdServer] override def process(ids: IdServer): Behavior[Message] = {
      sender ! GetUuidsResponse(Seq(2, 3, 5, 7, 11, 13, 17, 19))
      ids
    }
  }

  def apply(name: String): Behavior[Message] =
    Behaviors.setup(context => new IdServer(context, name))
}

final class IdServer(context: ActorContext[Message], name: String)
  extends AbstractBehavior[Message](context) {
  def onMessage(msg: Message): Behavior[Message] = msg.process(this)
}


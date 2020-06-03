import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import IdServer._

case class User(
  login: String,
  name: String,
  uuid: Long,
  pw_hash: String,
  salt: String
)

object IdServer {
  sealed abstract class Message {
    val reqId: Long
    private[IdServer] def process(ids: IdServer): Behavior[Message] = ids
  }

  final case class CreateResponse(reqId: Long, uuid: Option[Long], msg: String)
    extends Message
  final case class Create(
    sender: ActorRef[CreateResponse],
    reqId: Long,
    login: String,
    name: String,
    passw: String
  ) extends Message {
    private[IdServer] override def process(ids: IdServer): Behavior[Message] = {
      sender ! CreateResponse(reqId, None, "failure")
      ids
    }
  }

  final case class ModifyResponse(reqId: Long, msg: String) extends Message
  final case class Modify(
    sender: ActorRef[ModifyResponse],
    reqId: Long,
    login: String,
    newName: String,
    passw: String
  ) extends Message {
    private[IdServer] override def process(ids: IdServer): Behavior[Message] = {
      sender ! ModifyResponse(reqId, "failure")
      ids
    }
  }

  final case class DeleteResponse(reqId: Long, msg: String) extends Message
  case class Delete(
    sender: ActorRef[DeleteResponse],
    reqId: Long,
    login: String,
    passw: String
  ) extends Message {
    private[IdServer] override def process(ids: IdServer): Behavior[Message] = {
      sender ! DeleteResponse(reqId, "failure")
      ids
    }
  }

  final case class LoginLookupResponse(reqId: Long, user: User) extends Message
  final case class LoginLookup(
    sender: ActorRef[LoginLookupResponse],
    reqId: Long,
    login: String
  ) extends Message {
    private[IdServer] override def process(ids: IdServer): Behavior[Message] = {
      sender ! LoginLookupResponse(reqId, User("jane", "doe", 0, "foo", "bar"))
      ids
    }
  }

  final case class UuidLookupResponse(reqId: Long, user: User) extends Message
  final case class UuidLookup(
    sender: ActorRef[UuidLookupResponse],
    reqId: Long,
    uuid: Long
  ) extends Message {
    private[IdServer] override def process(ids: IdServer): Behavior[Message] = {
      sender ! UuidLookupResponse(reqId, User("chocolate", "cake", 1, "is", "good"))
      ids
    }
  }

  final case class GetAllResponse(reqId: Long, users: Seq[User])
    extends  Message
  final case class GetAll(sender: ActorRef[GetAllResponse], reqId: Long)
    extends Message {
    private[IdServer] override def process(ids: IdServer): Behavior[Message] = {
      sender ! GetAllResponse(reqId, Seq(
        User("zelda", "really", 2, "likes", "pringles"),
        User("snails", "move", 3, "rather", "slowly")
      ))
      ids
    }
  }

  final case class GetUsersResponse(reqId: Long, users: Seq[String])
    extends Message
  final case class GetUsers(sender: ActorRef[GetUsersResponse], reqId: Long)
    extends Message {
    private[IdServer] override def process(ids: IdServer): Behavior[Message] = {
      sender ! GetUsersResponse(reqId, Seq("the", "cloud", "is", "my", "butt"))
      ids
    }
  }

  final case class GetUuidsResponse(reqId: Long, users: Seq[Long])
    extends Message
  final case class GetUuids(sender: ActorRef[GetUuidsResponse], reqId: Long)
    extends Message {
    private[IdServer] override def process(ids: IdServer): Behavior[Message] = {
      sender ! GetUuidsResponse(reqId, Seq(2, 3, 5, 7, 11, 13, 17, 19))
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


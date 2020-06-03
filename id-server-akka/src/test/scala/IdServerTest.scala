import IdServer._
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scala.util.Random

final class IdServerTest extends AnyWordSpec with Matchers with BeforeAndAfterAll {
  private[this] val tk = ActorTestKit()
  import tk._

  private[this] val ids = spawn(IdServer("Server0"))

  private[this] val rand = new Random

  override def afterAll(): Unit = shutdownTestKit()

  "Create" in {
    val probe = createTestProbe[CreateResponse]()
    val reqId = rand.nextLong
    ids ! Create(probe.ref, reqId, "Foo", "Bar", "PW")
    probe.expectMessage(CreateResponse(reqId, None, "failure"))
  }

  "Modify" in {
    val probe = createTestProbe[ModifyResponse]()
    val reqId = rand.nextLong
    ids ! Modify(probe.ref, reqId, "Foo", "newBar", "PW")
    probe.expectMessage(ModifyResponse(reqId, "failure"))
  }

  "Delete" in {
    val probe = createTestProbe[DeleteResponse]()
    val reqId = rand.nextLong
    ids ! Delete(probe.ref, reqId, "Foo", "PW")
    probe.expectMessage(DeleteResponse(reqId, "failure"))
  }

  "LoginLookup" in {
    val probe = createTestProbe[LoginLookupResponse]()
    val reqId = rand.nextLong
    ids ! LoginLookup(probe.ref, reqId, "arbitrary")
    probe.expectMessage(LoginLookupResponse(reqId, User("jane", "doe", 0, "foo", "bar")))
  }

  "UuidLookup" in {
    val probe = createTestProbe[UuidLookupResponse]()
    val reqId = rand.nextLong
    ids ! UuidLookup(probe.ref, reqId, 0)
    probe.expectMessage(UuidLookupResponse(reqId, User("chocolate", "cake", 1, "is", "good")))
  }

  "GetAll" in {
    val probe = createTestProbe[GetAllResponse]()
    val reqId = rand.nextLong
    ids ! GetAll(probe.ref, reqId)
    probe.expectMessage(GetAllResponse(reqId, Seq(
      User("zelda", "really", 2, "likes", "pringles"),
      User("snails", "move", 3, "rather", "slowly")
    )))
  }

  "GetUsers" in {
    val probe = createTestProbe[GetUsersResponse]()
    val reqId = rand.nextLong
    ids ! GetUsers(probe.ref, reqId)
    probe.expectMessage(GetUsersResponse(reqId, Seq("the", "cloud", "is", "my", "butt")))
  }

  "GetUuids" in {
    val probe = createTestProbe[GetUuidsResponse]()
    val reqId = rand.nextLong
    ids ! GetUuids(probe.ref, reqId)
    probe.expectMessage(GetUuidsResponse(reqId, Seq(2, 3, 5, 7, 11, 13, 17, 19)))
  }
}

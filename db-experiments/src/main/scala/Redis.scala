import com.redis.{
  RedisClient
}

object Redis extends App {
  val rc = new RedisClient("localhost", 6379)
  Set("blue", "green", "orange").foreach(rc.sadd("mySet", _))
}



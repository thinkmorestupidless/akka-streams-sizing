package com.example

//#user-registry-actor
import java.util.concurrent.TimeUnit

import akka.actor.{ Actor, ActorLogging, Props }

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Random

//#user-case-classes
final case class User(name: String, age: Int, countryOfResidence: String)
final case class Users(users: Seq[User])
//#user-case-classes

object UserRegistryActor {
  final case object DoSomeWork
  final case class SomeWorkDone(id: Int)

  final case class ActionPerformed(description: String)
  final case object GetUsers
  final case class CreateUser(user: User)
  final case class GetUser(name: String)
  final case class DeleteUser(name: String)

  def props: Props = Props[UserRegistryActor]
}

class UserRegistryActor extends Actor with ActorLogging {
  import UserRegistryActor._

  val delay = FiniteDuration(context.system.settings.config.getDuration("estimator.server.delay").toMillis, TimeUnit.MILLISECONDS)

  println(s"Server Worker delay = $delay")

  implicit val ex: ExecutionContext = context.dispatcher

  var users = Set.empty[User]

  def receive: Receive = {
    case DoSomeWork =>
      context.system.scheduler.scheduleOnce(delay, sender(), SomeWorkDone(Random.nextInt(100)))
    case GetUsers =>
      sender() ! Users(users.toSeq)
    case CreateUser(user) =>
      users += user
      sender() ! ActionPerformed(s"User ${user.name} created.")
    case GetUser(name) =>
      sender() ! users.find(_.name == name)
    case DeleteUser(name) =>
      users.find(_.name == name) foreach { user => users -= user }
      sender() ! ActionPerformed(s"User ${name} deleted.")
  }
}
//#user-registry-actor
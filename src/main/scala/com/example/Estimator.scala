package com.example

import java.util.concurrent.TimeUnit

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.scaladsl.{ Balance, Flow, GraphDSL, Merge, Sink, Source }
import akka.stream._
import com.example.Estimator.decider
import com.lightbend.cinnamon.akka.stream.CinnamonAttributes.SourceWithInstrumented
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.Logger

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.{ Failure, Random, Success }

object Estimator extends App {

  val SourceListSize = 100
  val decider: Supervision.Decider = {
    case _: StreamTcpException => {
      println("StreamTcpException received, restarting operation")
      Supervision.Restart
    }
    case _ => Supervision.Stop
  }

  implicit val system = ActorSystem("Estimate")
  implicit val materializer = ActorMaterializer(ActorMaterializerSettings(system).withSupervisionStrategy(decider))
  implicit val executionContext = system.dispatcher

  val config = ConfigFactory.load()
  val cLogger = Logger("console")
  val pLogger = Logger("stats")

  val parallelism = config.getInt("estimator.parallelism")
  val url = config.getString("estimator.url")
  val timeout = config.getLong("estimator.timeout")

  cLogger.info(s"parallelism = $parallelism, url = $url, timeout = $timeout")

  case class CallStage(stage: Int, in: Long, out: Long, response: String)

  case class CallWrapper(data: String, createdAt: Long, stages: List[CallStage] = List.empty)

  def callRemoteService(w: CallWrapper): Future[CallWrapper] = {
    Http().singleRequest(HttpRequest(method = HttpMethods.POST, entity = HttpEntity(w.data), uri = url))
      .flatMap { httpResponse => Unmarshal(httpResponse.entity).to[String] }
      .map { entity =>
        {
          val callStage = CallStage(w.stages.size + 1, 0l, System.currentTimeMillis(), entity)
          CallWrapper(w.data, w.createdAt, w.stages :+ callStage)
        }
      }
  }

  def randomCallWrapper(n: NotUsed): CallWrapper = {
    val r = Random.nextInt(100)
    val now = System.currentTimeMillis()

    r match {
      case x if 0 until 50 contains x => CallWrapper(Data.Small, now)
      case x if 50 until 75 contains x => CallWrapper(Data.Medium, now)
      case _ => CallWrapper(Data.Large, now)
    }
  }

  val sourceRepeat = Source.repeat(NotUsed)
    .map(randomCallWrapper)

  val sourceSingle = Source.single(NotUsed)
    .map(randomCallWrapper)

  def dummyStream() = {

    def dummyRemoteService(w: CallWrapper): Future[CallWrapper] = {
      FutureUtil.futureWithTimeout(Future[CallWrapper] {
        val callStage = CallStage(w.stages.size + 1, 0l, System.currentTimeMillis(), "")
        CallWrapper(w.data, w.createdAt, w.stages :+ callStage)
      }, FiniteDuration(timeout, TimeUnit.MILLISECONDS))
    }

    sourceRepeat
      .mapAsync(parallelism)(dummyRemoteService)
      .mapAsync(parallelism)(dummyRemoteService)
      .mapAsync(parallelism)(dummyRemoteService)
      .mapAsync(parallelism)(dummyRemoteService)
      .mapAsync(parallelism)(dummyRemoteService)
      .mapAsync(parallelism)(dummyRemoteService)
      .mapAsync(parallelism)(dummyRemoteService)
      .instrumentedRunWith(Sink.ignore)(name = "my-stream")
  }

  def singleStream() = {
    sourceRepeat
      .mapAsync(parallelism)(callRemoteService)
      .mapAsync(parallelism)(callRemoteService)
      .mapAsync(parallelism)(callRemoteService)
      .mapAsync(parallelism)(callRemoteService)
      .mapAsync(parallelism)(callRemoteService)
      .mapAsync(parallelism)(callRemoteService)
      .mapAsync(parallelism)(callRemoteService)
      .instrumentedRunWith(Sink.foreach(w => pLogger.debug(s"completed ${w.stages.size} stages in ${System.currentTimeMillis() - w.createdAt}ms")))(name = "my-stream")
  }

  def customGraph() = {

    val callStage1: Flow[CallWrapper, CallWrapper, NotUsed] =
      Flow[CallWrapper].mapAsync(parallelism)(callRemoteService)

    val callStages: Flow[CallWrapper, CallWrapper, NotUsed] =
      Flow.fromGraph(GraphDSL.create() { implicit builder =>
        import GraphDSL.Implicits._

        val dispatch = builder.add(Balance[CallWrapper](3))
        val merge = builder.add(Merge[CallWrapper](3))

        dispatch.out(0) ~> callStage1 ~> callStage1 ~> callStage1 ~> callStage1 ~> callStage1 ~> callStage1 ~> callStage1 ~> merge.in(0)
        dispatch.out(1) ~> callStage1 ~> callStage1 ~> callStage1 ~> callStage1 ~> callStage1 ~> callStage1 ~> callStage1 ~> merge.in(1)
        dispatch.out(2) ~> callStage1 ~> callStage1 ~> callStage1 ~> callStage1 ~> callStage1 ~> callStage1 ~> callStage1 ~> merge.in(2)

        FlowShape(dispatch.in, merge.out)
      })

    sourceRepeat
      .via(callStages)
      .instrumentedRunWith(Sink.ignore)(name = "my-stream")
  }

  /**
   * Doesn't call an HTTP endpoint - just waits for a timeout period (setting estimator.timeout)
   */
  //  dummyStream()

  /**
   * Calls an HTTP endpoint
   */
  singleStream().onComplete(r => {
    r match {
      case Success(_) =>
        cLogger.info(s"Stream completed successfully")
      case Failure(e) =>
        cLogger.error(s"Stream failed with error :$e")
    }
    System.exit(0)
  })

  /**
   * Using a custom graph stage to fan-out/fan-in
   */
  //  customGraph()
}

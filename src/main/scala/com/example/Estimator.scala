package com.example

import java.util
import java.util.concurrent.TimeUnit

import akka.{ Done, NotUsed }
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Supervision.Decider
import akka.stream.scaladsl.{ Balance, Flow, GraphDSL, Merge, Sink, Source }
import akka.stream._
import com.example.Estimator.decider
import com.lightbend.cinnamon.akka.stream.CinnamonAttributes.SourceWithInstrumented
import com.typesafe.config.{ Config, ConfigFactory, ConfigObject }
import com.typesafe.scalalogging.Logger

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._
import scala.util.{ Failure, Random, Success }
import scala.collection.JavaConverters._

object Estimator extends App {

  val SourceListSize = 100
  val decider: Decider = {
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

  // This isn't used when we are running perfTestStream
  // cLogger.info(s"parallelism = $parallelism, url = $url, timeout = $timeout")

  case class CallStage(stage: Int, in: Long, out: Long, response: String)

  case class CallWrapper(data: String, createdAt: Long, stages: List[CallStage] = List.empty)

  case class PerfTest(name: String, parallelism: Int, elementCount: Int)

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

  def perfTestStream(elementCount: Int, testParallelism: Int) = {
    Source(1 to elementCount)
      .map(i => {
        pLogger.debug(s"Starting element $i")
        randomCallWrapper(NotUsed.getInstance())
      })
      .mapAsync(testParallelism)(callRemoteService)
      .mapAsync(testParallelism)(callRemoteService)
      .mapAsync(testParallelism)(callRemoteService)
      .mapAsync(testParallelism)(callRemoteService)
      .mapAsync(testParallelism)(callRemoteService)
      .mapAsync(testParallelism)(callRemoteService)
      .mapAsync(testParallelism)(callRemoteService)
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

  pLogger.info(s"STARTING SUITE: ${config.getString("estimator.perfTestSuiteMessage")}")
  val perfTests = parseTests(config)
  perfTests.foreach(runPerfTest(_))
  System.exit(0)

  /**
   * Using a custom graph stage to fan-out/fan-in
   */
  //  customGraph()

  private def runPerfTest(perfTest: PerfTest): Unit = {
    val testStartTime = System.currentTimeMillis()
    val perfTestFuture = perfTestStream(perfTest.elementCount, perfTest.parallelism)
    perfTestFuture.onComplete(r => {
      r match {
        case Success(_) =>
          cLogger.info(s"Stream ${perfTest.name} completed successfully")
        case Failure(e) =>
          cLogger.error(s"Stream ${perfTest.name} failed with error :$e")
      }
    })
    Await.result(perfTestFuture, 1.hour)
    val testStopTime = System.currentTimeMillis()
    val testDurationSecs = (testStopTime - testStartTime) / 1000.0
    val testFlowsPerSec = perfTest.elementCount / testDurationSecs
    pLogger.info(f"COMPLETED TEST ${perfTest.name} in $testDurationSecs%.1f seconds, parallelism ${perfTest.parallelism}, count ${perfTest.elementCount}, fps ${testFlowsPerSec}%.1f")
  }

  private def parseTests(config: Config): Seq[PerfTest] = {
    val perfTestObjects: Seq[ConfigObject] = asScalaBuffer(config.getObjectList("estimator.perfTests"))
    perfTestObjects.map { co =>
      PerfTest(
        co.get("name").unwrapped().asInstanceOf[String],
        co.get("parallelism").unwrapped().asInstanceOf[Int],
        co.get("elementCount").unwrapped().asInstanceOf[Int])
    }
  }
}

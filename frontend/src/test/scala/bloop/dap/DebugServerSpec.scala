package bloop.dap
import java.net.{ConnectException, SocketException, SocketTimeoutException}
import java.util.NoSuchElementException
import java.util.concurrent.TimeUnit.{MILLISECONDS, SECONDS}

import bloop.bsp.BspBaseSuite
import bloop.cli.BspProtocol
import bloop.logging.RecordingLogger
import bloop.util.{TestProject, TestUtil}
import ch.epfl.scala.bsp.ScalaMainClass
import monix.eval.Task
import monix.execution.Cancelable

import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Promise, TimeoutException}
import com.microsoft.java.debug.core.protocol.Types.Capabilities
import com.microsoft.java.debug.core.protocol.Requests.SetBreakpointArguments
import com.microsoft.java.debug.core.protocol.Types
import com.microsoft.java.debug.core.protocol.Types.SourceBreakpoint

object DebugServerSpec extends BspBaseSuite {
  override val protocol: BspProtocol.Local.type = BspProtocol.Local
  private val ServerNotListening = new IllegalStateException("Server is not accepting connections")

  test("cancelling closes server connection") {
    startDebugServer(Task.now(())) { server =>
      val test = for {
        _ <- Task(server.cancel())
        serverClosed <- awaitClosed(server)
      } yield {
        assert(serverClosed)
      }

      TestUtil.await(5, SECONDS)(test)
    }
  }

  test("cancelling closes client connection") {
    startDebugServer(Task.now(())) { server =>
      val test = for {
        client <- server.startConnection
        _ <- Task(server.cancel())
        clientClosed <- awaitClosed(client)
      } yield {
        assert(clientClosed)
      }

      TestUtil.await(10, SECONDS)(test)
    }
  }

  test("sends exit and terminated events when cancelled") {
    TestUtil.withinWorkspace { workspace =>
      val main =
        """|/Main.scala
           |object Main {
           |  def main(args: Array[String]): Unit = {
           |    synchronized(wait())  // block for all eternity
           |  }
           |}
           |""".stripMargin

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val project = TestProject(workspace, "p", List(main))

      loadBspState(workspace, List(project), logger) { state =>
        val runner = mainRunner(project, state)

        startDebugServer(runner) { server =>
          val test = for {
            client <- server.startConnection
            _ <- client.initialize()
            _ <- client.launch()
            _ <- client.initialized
            _ <- client.configurationDone()
            _ <- Task(server.cancel())
            _ <- client.terminated
            _ <- client.exited
            clientClosed <- awaitClosed(client)
          } yield {
            assert(clientClosed)
          }

          TestUtil.await(10, SECONDS)(test)
        }
      }
    }
  }

  test("closes the client when debuggee finished and terminal events are sent") {
    TestUtil.withinWorkspace { workspace =>
      val main =
        """|/Main.scala
           |object Main extends MainBase {
           |  def main(args: Array[String]): Unit = {
           |    val thunk = () => {
           |      println("I have a breakpoint")
           |      println("Breakpoint was continued!")
           |    }
           |    class Bar {
           |      def print = {
           |        println("I have a breakpoint II")
           |        println("Breakpoint was continued II!")
           |      }
           |    }
           |    thunk()
           |    val b = new Bar
           |    b.print
           |    //main2(Array.empty)
           |  }
           |}
           |""".stripMargin

      val mainBase =
        """|/MainBase.scala
           |class MainBase {
           |  def main2(args: Array[String]): Unit = {
           |    val thunk = () => {
           |      println("I have a breakpoint")
           |      println("Breakpoint was continued!")
           |    }
           |    class Bar {
           |      def print = {
           |        println("I have a breakpoint II")
           |        println("Breakpoint was continued II!")
           |      }
           |    }
           |    thunk()
           |    val b = new Bar
           |    b.print
           |  }
           |}
           |""".stripMargin

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val project = TestProject(workspace, "r", List(main, mainBase))

      loadBspState(workspace, List(project), logger) { state =>
        val runner = mainRunner(project, state)

        val buildProject = state.toTestState.getProjectFor(project)
        val `Main.scala` = buildProject.sources
          .map(_.resolve("Main.scala"))
          .find(_.exists)
          .get
          .getParent
          .resolve("Main3.scala")
        val firstBreakpoint = {
          val arguments = new SetBreakpointArguments()
          val sourceBreakpoint = new SourceBreakpoint()
          sourceBreakpoint.line = 4
          arguments.source = new Types.Source(`Main.scala`.syntax, 0)
          arguments.sourceModified = false
          arguments.breakpoints = Array(sourceBreakpoint)
          arguments
        }

        val secondBreakpoint = {
          val arguments = new SetBreakpointArguments()
          val sourceBreakpoint = new SourceBreakpoint()
          sourceBreakpoint.line = 9
          arguments.source = new Types.Source(`Main.scala`.syntax, 0)
          arguments.sourceModified = false
          arguments.breakpoints = Array(sourceBreakpoint)
          arguments
        }

        startDebugServer(runner) { server =>
          val test = for {
            client <- server.startConnection
            capabilities <- client.initialize()
            _ <- client.launch()
            _ <- client.initialized
            _ <- client.setBreakpoints(firstBreakpoint)
            _ <- client.setBreakpoints(secondBreakpoint)
            _ <- client.configurationDone()
            stopped <- client.stopped
            _ <- client.continue(stopped.threadId)
            stopped2 <- client.stopped
            _ <- client.continue(stopped2.threadId)
            _ <- client.terminated
            _ <- client.exited
            clientClosed <- awaitClosed(client)
            output <- client.allOutput
          } yield {
            assert(clientClosed)
            assertNoDiff(
              output,
              """|I have a breakpoint
                 |Breakpoint was continued!
                 |I have a breakpoint II
                 |Breakpoint was continued II!
                 |""".stripMargin
            )
          }

          TestUtil.await(10, SECONDS)(test)
        }
      }
    }
  }

  test("sends exit and terminated events when cannot run debuggee") {
    TestUtil.withinWorkspace { workspace =>
      // note that there is nothing that can be run (no sources)
      val project = TestProject(workspace, "p", Nil)

      val logger = new RecordingLogger(ansiCodesSupported = false)
      loadBspState(workspace, List(project), logger) { state =>
        val runner = mainRunner(project, state)

        startDebugServer(runner) { server =>
          val test = for {
            client <- server.startConnection
            _ <- client.initialize()
            _ <- client.launch()
            _ <- client.initialized
            _ <- client.configurationDone()
            _ <- client.exited
            _ <- client.terminated
          } yield ()

          TestUtil.await(5, SECONDS)(test)
        }
      }
    }
  }

  test("does not accept a connection unless the previous session requests a restart") {
    startDebugServer(Task.now(())) { server =>
      val test = for {
        firstClient <- server.startConnection
        secondClient <- server.startConnection
        requestBeforeRestart <- secondClient.initialize().timeout(FiniteDuration(1, SECONDS)).failed
        _ <- firstClient.disconnect(restart = true)
        _ <- secondClient.initialize().timeout(FiniteDuration(100, MILLISECONDS))
      } yield {
        assert(requestBeforeRestart.isInstanceOf[TimeoutException])
      }

      TestUtil.await(5, SECONDS)(test)
    }
  }

  test("responds to launch when jvm could not be started") {
    // note that the runner is not starting the jvm
    // therefore the debuggee address will never be bound
    // and correct response to the launch request
    // will never be sent
    val runner = Task.now(())

    startDebugServer(runner) { server =>
      val test = for {
        client <- server.startConnection
        _ <- client.initialize()
        _ <- client.launch()
      } yield ()

      TestUtil.await(20, SECONDS)(test)
    }
  }

  test("restarting closes current client and debuggee") {
    val cancelled = Promise[Boolean]()
    val awaitCancellation = Task
      .fromFuture(cancelled.future)
      .doOnFinish(_ => complete(cancelled, false))
      .doOnCancel(complete(cancelled, true))

    startDebugServer(awaitCancellation) { server =>
      val test = for {
        firstClient <- server.startConnection
        _ <- firstClient.disconnect(restart = true)
        secondClient <- server.startConnection
        debuggeeCanceled <- Task.fromFuture(cancelled.future)
        firstClientClosed <- awaitClosed(firstClient)
        secondClientClosed <- awaitClosed(secondClient)
          .timeoutTo(FiniteDuration(1, SECONDS), Task(false))
      } yield {
        // second client should remain unaffected
        assert(debuggeeCanceled, firstClientClosed, !secondClientClosed)
      }

      TestUtil.await(15, SECONDS)(test)
    }
  }

  test("disconnecting closes server, client and debuggee") {
    val cancelled = Promise[Boolean]()
    val awaitCancellation = Task
      .fromFuture(cancelled.future)
      .doOnFinish(_ => complete(cancelled, false))
      .doOnCancel(complete(cancelled, true))

    startDebugServer(awaitCancellation) { server =>
      val test = for {
        client <- server.startConnection
        _ <- client.disconnect(restart = false)
        debuggeeCanceled <- Task.fromFuture(cancelled.future)
        clientClosed <- awaitClosed(client)
        serverClosed <- awaitClosed(server)
      } yield {
        assert(debuggeeCanceled, clientClosed, serverClosed)
      }

      TestUtil.await(5, SECONDS)(test)
    }
  }

  test("closes the client even though the debuggee cannot close") {
    val blockedDebuggee = Promise[Nothing]

    startDebugServer(Task.fromFuture(blockedDebuggee.future)) { server =>
      val test = for {
        client <- server.startConnection
        _ <- Task(server.cancel())
        clientDisconnected <- awaitClosed(client)
      } yield {
        assert(clientDisconnected)
      }

      TestUtil.await(20, SECONDS)(test) // higher limit to accommodate the timout
    }
  }

  private def awaitClosed(client: DebugAdapterConnection): Task[Boolean] = {
    Task(await(client.socket.isClosed))
  }

  @tailrec
  private def await(condition: => Boolean): Boolean = {
    if (condition) true
    else if (Thread.interrupted()) false
    else {
      Thread.sleep(250)
      await(condition)
    }
  }

  private def awaitClosed(server: TestServer): Task[Boolean] = {
    server.startConnection.failed
      .map {
        case _: SocketTimeoutException => true
        case _: ConnectException => true
        case ServerNotListening => true
        case e: SocketException =>
          val message = e.getMessage
          message.endsWith("(Connection refused)") || message.endsWith("(connect failed)")

        case _ =>
          false
      }
      .onErrorRestartIf {
        case e: NoSuchElementException =>
          e.getMessage == "failed"
      }
  }

  private def mainRunner(
      project: TestProject,
      state: DebugServerSpec.ManagedBspTestState
  ): DebuggeeRunner = {
    val testState = state.compile(project).toTestState
    DebuggeeRunner.forMainClass(
      Seq(testState.getProjectFor(project)),
      new ScalaMainClass("Main", Nil, Nil),
      testState.state
    ) match {
      case Right(value) => value
      case Left(error) => throw new Exception(error)
    }
  }

  def startDebugServer(task: Task[_])(f: TestServer => Any): Unit = {
    startDebugServer(_ => task.map(_ => ()))(f)
  }

  def startDebugServer(runner: DebuggeeRunner)(f: TestServer => Any): Unit = {
    val logger = new RecordingLogger(ansiCodesSupported = false)
    val server = DebugServer.start(runner, logger, defaultScheduler)

    val testServer = new TestServer(server)
    val test = Task(f(testServer))
      .doOnFinish(_ => Task(testServer.close()))
      .doOnCancel(Task(testServer.close()))

    TestUtil.await(20, SECONDS)(test)
    ()
  }

  private final class TestServer(val server: StartedDebugServer)
      extends Cancelable
      with AutoCloseable {
    private val task = server.listen.runAsync(defaultScheduler)
    private val clients = mutable.Set.empty[DebugAdapterConnection]

    override def cancel(): Unit = {
      task.cancel()
    }

    // terminates both server and its clients
    override def close(): Unit = {
      cancel()
      val allClientsClosed = clients.map(c => Task(awaitClosed(c)))
      TestUtil.await(10, SECONDS)(Task.sequence(allClientsClosed))

      clients.foreach { client =>
        client.socket.close()
      }
    }

    def startConnection: Task[DebugAdapterConnection] = {
      server.address.flatMap {
        case Some(uri) =>
          val connection = DebugAdapterConnection.connectTo(uri)(defaultScheduler)
          clients += connection
          Task(connection)
        case None =>
          throw ServerNotListening
      }
    }
  }

  private def complete[A](promise: Promise[A], value: A): Task[Unit] =
    Task {
      promise.trySuccess(value)
      ()
    }
}

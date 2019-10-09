package bloop.dap

import java.net.{InetSocketAddress, Socket, URI}

import bloop.dap.DebugTestEndpoints._
import bloop.dap.DebugTestProtocol.Response
import com.microsoft.java.debug.core.protocol.Events
import com.microsoft.java.debug.core.protocol.Requests._
import com.microsoft.java.debug.core.protocol.Types.Capabilities
import monix.eval.Task
import monix.execution.Scheduler
import com.microsoft.java.debug.core.Breakpoint
import com.microsoft.java.debug.core.protocol.Responses.SetBreakpointsResponseBody
import com.microsoft.java.debug.core.protocol.Responses.ContinueResponseBody

/**
 * Manages a connection with a debug adapter.
 * It closes the connection after receiving a response to the 'disconnect' request
 */
private[dap] final class DebugAdapterConnection(val socket: Socket, adapter: DebugAdapterProxy) {
  def initialize(): Task[Response[Capabilities]] = {
    val arguments = new InitializeArguments()
    // These are the defaults specified in the DAP specification
    arguments.linesStartAt1 = true
    arguments.columnsStartAt1 = true
    adapter.request(Initialize, arguments)
  }

  def initialized: Task[Events.InitializedEvent] = {
    adapter.events.first(Initialized)
  }

  def setBreakpoints(
      arguments: SetBreakpointArguments
  ): Task[Response[SetBreakpointsResponseBody]] = {
    adapter.request(SetBreakpoints, arguments)
  }

  def stopped: Task[Events.StoppedEvent] = {
    adapter.events.first(StoppedEvent)
  }

  def continue(threadId: Long): Task[Response[ContinueResponseBody]] = {
    val arguments = new ContinueArguments()
    arguments.threadId = threadId
    adapter.request(Continue, arguments)
  }

  def configurationDone(): Task[Response[Unit]] = {
    adapter.request(ConfigurationDone, ())
  }

  def launch(): Task[Response[Unit]] = {
    val arguments = new LaunchArguments
    arguments.noDebug = true
    adapter.request(Launch, arguments)
  }

  def disconnect(restart: Boolean): Task[Response[Unit]] = {
    val arguments = new DisconnectArguments
    arguments.restart = restart
    for {
      response <- adapter.request(Disconnect, arguments)
      _ <- Task(socket.close())
    } yield response
  }

  def exited: Task[Events.ExitedEvent] = {
    adapter.events.first(Exited)
  }

  def terminated: Task[Events.TerminatedEvent] = {
    adapter.events.first(Terminated)
  }

  def output(expected: String): Task[String] = {
    adapter.events.all(OutputEvent).map { events =>
      val builder = new StringBuilder
      events
        .takeWhile(_ => builder.toString() != expected)
        .foreach(e => builder.append(e.output))
      builder.toString()
    }
  }

  def firstOutput: Task[String] = {
    adapter.events.first(OutputEvent).map(_.output)
  }

  def allOutput: Task[String] = {
    adapter.events.all(OutputEvent).map { events =>
      val builder: StringBuilder =
        events.foldLeft(new StringBuilder)((acc, e) => acc.append(e.output))
      builder.toString()
    }
  }
}

object DebugAdapterConnection {
  def connectTo(uri: URI)(scheduler: Scheduler): DebugAdapterConnection = {
    val socket = new Socket() // create unconnected socket
    socket.connect(new InetSocketAddress(uri.getHost, uri.getPort), 500)

    val proxy = DebugAdapterProxy(socket)
    proxy.startBackgroundListening(scheduler)
    new DebugAdapterConnection(socket, proxy)
  }
}

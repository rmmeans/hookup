package io.backchat.websocket

import org.jboss.netty.bootstrap.ClientBootstrap
import org.jboss.netty.channel._
import socket.nio.NioClientSocketChannelFactory
import org.jboss.netty.handler.codec.http._
import collection.JavaConverters._
import websocketx._
import org.jboss.netty.buffer.ChannelBuffers
import akka.util.duration._
import akka.dispatch.{ExecutionContext, Await, Promise, Future}
import akka.jsr166y.ForkJoinPool
import java.lang.Thread.UncaughtExceptionHandler
import org.jboss.netty.handler.timeout.{IdleStateAwareChannelHandler, IdleStateEvent, IdleState, IdleStateHandler}
import org.jboss.netty.logging.{InternalLogger, InternalLoggerFactory}
import java.util.concurrent.atomic.AtomicLong
import net.liftweb.json.{DefaultFormats, Formats}
import io.backchat.websocket.WebSocketServer.MessageAckingHandler
import java.util.concurrent.{TimeUnit, Executors}
import org.jboss.netty.util.{Timeout => NettyTimeout, TimerTask, HashedWheelTimer, CharsetUtil}
import akka.util.{Duration, Timeout}
import java.io.File
import io.backchat.websocket.WebSocket.ActiveThrottle
import java.net.{ConnectException, InetSocketAddress, URI}


/**
* Usage of the simple websocket client:
*
* <pre>
*   new WebSocket {
*     val uri = new URI("ws://localhost:8080/thesocket")
*
*     def receive = {
*       case Disconnected(_) => println("The websocket to " + url.toASCIIString + " disconnected.")
*       case TextMessage(message) => {
*         println("RECV: " + message)
*         send("ECHO: " + message)
*       }
*     }
*
*    connect() onSuccess {
 *    case Success =>
 *      println("The websocket to " + url.toASCIIString + "is connected.")
 *    case _ =>
 *   }
*  }
* </pre>
*/
object WebSocket {

  object ParseToWebSocketInMessage {
    import net.liftweb.json._
    def apply(message: String)(implicit format: Formats) = inferMessageTypeFromContent(message)

    private def inferMessageTypeFromContent(content: String)(implicit format: Formats): WebSocketInMessage = {
      val possiblyJson = content.trim.startsWith("{") || content.trim.startsWith("[")
      if (!possiblyJson) TextMessage(content)
      else parseOpt(content) map inferJsonMessageFromContent getOrElse TextMessage(content)
    }

    private def inferJsonMessageFromContent(content: JValue)(implicit format: Formats): WebSocketInMessage = {
      val contentType = (content \ "type").extractOpt[String].map(_.toLowerCase) getOrElse "none"
      (contentType) match {
        case "ack_request" => AckRequest(inferContentMessage(content \ "message"), (content \ "id").extract[Long])
        case "ack" => Ack((content \ "id").extract[Long])
        case "text" => TextMessage((content \ "content").extract[String])
        case "json" => JsonMessage((content \ "content"))
        case _ => JsonMessage(content)
      }
    }

    private def inferContentMessage(content: JValue)(implicit format: Formats): Ackable = {
      val contentType = (content \ "type").extractOrElse("none")
      (contentType) match {
        case "text" => TextMessage((content \ "content").extract[String])
        case "json" => JsonMessage((content \ "content"))
        case "none" => JsonMessage(content)
      }
    }
  }

  object ParseToWebSocketOutMessage {
    import net.liftweb.json._
    def apply(message: String)(implicit format: Formats): WebSocketOutMessage = inferMessageTypeFromContent(message)

    private def inferMessageTypeFromContent(content: String)(implicit format: Formats): WebSocketOutMessage = {
      val possiblyJson = content.trim.startsWith("{") || content.trim.startsWith("[")
      if (!possiblyJson) TextMessage(content)
      else parseOpt(content) map inferJsonMessageFromContent getOrElse TextMessage(content)
    }

    private def inferJsonMessageFromContent(content: JValue)(implicit format: Formats): WebSocketOutMessage = {
      val contentType = (content \ "type").extractOpt[String].map(_.toLowerCase) getOrElse "none"
      (contentType) match {
        case "needs_ack" => NeedsAck(inferContentMessage(content \ "content"), (content \ "timeout").extract[Long].millis )
        case "text" => TextMessage((content \ "content").extract[String])
        case "json" => JsonMessage((content \ "content"))
        case _ => JsonMessage(content)
      }
    }

    private def inferContentMessage(content: JValue)(implicit format: Formats): Ackable = content match {
      case JString(text) => TextMessage(text)
      case _ =>
        val contentType = (content \ "type").extractOrElse("none")
        (contentType) match {
          case "text" => TextMessage((content \ "content").extract[String])
          case "json" => JsonMessage((content \ "content"))
          case "none" => JsonMessage(content)
        }
    }
  }

  object RenderOutMessage {
    import net.liftweb.json._
    import JsonDSL._

    def apply(message: WebSocketOutMessage): String = {
      message match {
        case TextMessage(text) => text
        case JsonMessage(json) => compact(render(("type" -> "json") ~ ("content" -> json)))
        case NeedsAck(msg, timeout) =>
          compact(render(("type" -> "needs_ack") ~ ("timeout" -> timeout.toMillis) ~ ("content" -> contentFrom(msg))))
        case Ack(id) => compact(render(("type" -> "ack") ~ ("id" -> id)))
        case x => sys.error(x.getClass.getName + " is an unsupported message type")
      }
    }

    private[this] def contentFrom(message: Ackable): (String, JValue) = message match {
      case TextMessage(text) => ("text", JString(text))
      case JsonMessage(json) => ("json", json)
    }
  }

  type Receive = PartialFunction[WebSocketInMessage, Unit]

  private val logger = InternalLoggerFactory.getInstance("WebSocket")

  class WebSocketClientHostHandler(handshaker: WebSocketClientHandshaker, host: WebSocketHost)(implicit formats: Formats) extends SimpleChannelHandler {
    private val msgCount = new AtomicLong(0)

    override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
      e.getMessage match {
        case resp: HttpResponse if handshaker.isHandshakeComplete =>
          throw new WebSocketException("Unexpected HttpResponse (status=" + resp.getStatus + ", content="
                              + resp.getContent.toString(CharsetUtil.UTF_8) + ")")
        case resp: HttpResponse =>
          handshaker.finishHandshake(ctx.getChannel, resp)
          host.receive lift Connected

        case f: TextWebSocketFrame =>
          val inferred = ParseToWebSocketInMessage(f.getText)
          inferred match {
            case a: Ack => Channels.fireMessageReceived(ctx, a)
            case a: AckRequest => Channels.fireMessageReceived(ctx, a)
            case r => host.receive lift r
          }
        case f: BinaryWebSocketFrame => host.receive lift BinaryMessage(f.getBinaryData.array())
        case f: ContinuationWebSocketFrame =>
          logger warn "Got a continuation frame this is not (yet) supported"
        case _: PingWebSocketFrame => ctx.getChannel.write(new PongWebSocketFrame())
        case _: PongWebSocketFrame =>
        case _: CloseWebSocketFrame => host.close()
      }
    }



    override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
      (host.isReconnecting, e.getCause) match {
        case (true, ex: ConnectException) => // this is expected
        case (false, ex) =>
          host.receive.lift(Error(Option(e.getCause))) getOrElse logger.error("Oops, something went amiss", e.getCause)
          e.getChannel.close()
      }
    }

    override def writeRequested(ctx: ChannelHandlerContext, e: MessageEvent) {
      e.getMessage match {
        case content: String => e.getChannel.write(new TextWebSocketFrame(content))
        case m: JsonMessage => sendOutMessage(m, e.getChannel)
        case m: TextMessage => sendOutMessage(m, e.getChannel)
        case BinaryMessage(content) => e.getChannel.write(new BinaryWebSocketFrame(ChannelBuffers.copiedBuffer(content)))
        case Disconnect => // ignore here
        case _: WebSocketOutMessage =>
        case _ => ctx.sendDownstream(e)
      }
    }

    private def sendOutMessage(msg: WebSocketOutMessage with Ackable, channel: Channel) {
      channel.write(new TextWebSocketFrame(RenderOutMessage(msg)))
    }

    override def channelDisconnected(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
      if (!host.isClosing||host.isReconnecting) {
        host.reconnect()
      }
    }
  }

  private final class WebSocketHost(val client: WebSocket)(implicit executionContext: ExecutionContext, formats: Formats) extends WebSocketLike with BroadcastChannel with Connectable with Reconnectable {

    private[this] val normalized = client.uri.normalize()
    private[this] val tgt = if (normalized.getPath == null || normalized.getPath.trim().isEmpty) {
      new URI(normalized.getScheme, normalized.getAuthority,"/", normalized.getQuery, normalized.getFragment)
    } else normalized
    private[this] val protos = if (client.protocols.isEmpty) null else client.protocols.mkString(",")

    private[this] var bootstrap: ClientBootstrap = null
    private[this] var handshaker: WebSocketClientHandshaker = null
    private[this] val timer = new HashedWheelTimer()
    private[this] var channel: Channel = null
    private[this] var _isConnected: Promise[OperationResult] = Promise[OperationResult]()
    private[this] val buffer = new FileBuffer(client.bufferPath, logger)

    def isConnected = channel != null && channel.isConnected && _isConnected.isCompleted

    private def configureBootstrap() {
      val self = this
      val ping = client.ping.duration.toSeconds.toInt
      bootstrap.setPipelineFactory(new ChannelPipelineFactory {
        def getPipeline = {
          val pipeline = Channels.pipeline()
          pipeline.addLast("timeouts", new IdleStateHandler(timer, ping, 0, 0))
          pipeline.addLast("pingpong", new PingPongHandler(logger))
          if (client.version == WebSocketVersion.V00)
            pipeline.addLast("decoder", new WebSocketHttpResponseDecoder)
          else
            pipeline.addLast("decoder", new HttpResponseDecoder)

          pipeline.addLast("encoder", new HttpRequestEncoder)
          pipeline.addLast("ws-handler", new WebSocketClientHostHandler(handshaker, self))
          pipeline.addLast("acking", new MessageAckingHandler(logger, client.raiseEvents))
          if (client.raiseEvents) {
            pipeline.addLast("eventsHook", new SimpleChannelHandler {
              override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
                e.getMessage match {
                  case m: Ack => client.receive lift m
                  case m: AckRequest => client.receive lift m
                  case _ => ctx.sendUpstream(e)
                }
              }
            })
          }
          pipeline
        }
      })
    }

    var throttle = client.throttle
    var isClosing = false

    def connect(): Future[OperationResult] = synchronized {
      handshaker = new WebSocketClientHandshakerFactory().newHandshaker(tgt, client.version, protos, false, client.initialHeaders.asJava)
      isClosing = false
      bootstrap = new ClientBootstrap(new NioClientSocketChannelFactory(Executors.newCachedThreadPool, Executors.newCachedThreadPool))
      configureBootstrap()
      if (isConnected) Promise.successful(Success)
      else {
        val fut = bootstrap.connect(new InetSocketAddress(client.uri.getHost, client.uri.getPort))
        val af = fut.toAkkaFuture flatMap {
          case Success =>
            if (isReconnecting) throttle = client.throttle
            channel = fut.getChannel
            handshaker.handshake(channel).toAkkaFuture
          case x => Promise.successful(x)
        }
        try {
          val fut = af flatMap { _ =>
            isReconnecting = false
            buffer.drain(channel.send(_)) flatMap { _ =>
              _isConnected.success(Success)
              _isConnected
            }

          }
          if (client.buffered) buffer.open()
          Await.ready(fut, 5 seconds)
        } catch {
          case ex => {
            logger.error("Couldn't connect, killing all")
            bootstrap.releaseExternalResources()
            Promise.failed(ex)
          }
        }
      }
    }

    var isReconnecting = false

    def reconnect(): Future[OperationResult] = {
      if (!isReconnecting) client.receive lift Reconnecting
      isReconnecting = true
      close() andThen { case _ =>
        delay {
          connect()
        }
      }
    }


    def delay(thunk: => Future[OperationResult]): Future[OperationResult] = {
      if (client.throttle != NoThrottle) {
        val promise = Promise[OperationResult]()
        val theDelay = throttle.delay
        throttle = throttle.next()
        timer.newTimeout(task(promise, theDelay, thunk), theDelay.toMillis, TimeUnit.MILLISECONDS)
        promise
      } else Promise.successful(Cancelled)
    }

    private def task(promise: Promise[OperationResult], theDelay: Duration, thunk: => Future[OperationResult]): TimerTask = {
      logger info "Connection to host [%s] lost, reconnecting in %s".format(client.uri.toASCIIString, theDelay)
      new TimerTask {
        def run(timeout: NettyTimeout) {
          if (!timeout.isCancelled) {
            val rr = thunk
            rr onComplete {
              case Left(ex) => delay(thunk)
              case Right(x) => promise.success(x)
            }
          } else promise.success(Cancelled)
        }
      }
    }

    def close(): Future[OperationResult] = synchronized {
      isClosing = true;
      val closing = Promise[OperationResult]()
      if (_isConnected.isCompleted) _isConnected = Promise[OperationResult]()
      if(isConnected) {
        channel.write(new CloseWebSocketFrame()).awaitUninterruptibly()
        channel.close().awaitUninterruptibly(5000L)
      }

      try {
        val kill = new Thread() {
          override def run = {
            try {
              if (!isReconnecting) buffer.close()
              if (bootstrap != null) bootstrap.releaseExternalResources()
            } catch {
              case e => logger.error("error while closing the connection", e)
            } finally {
              if (!closing.isCompleted) closing.success(Success)
            }
          }
        }
        kill.setDaemon(false)
        kill.start()
        kill.join()
      } catch {
        case _ => if (!closing.isCompleted) closing.success(Success)
      }


      closing
    }

    def id = if (channel == null) 0 else channel.id

    def send(message: WebSocketOutMessage): Future[OperationResult] = {
      if (isConnected) {
        channel.write(message).toAkkaFuture
      } else {
        logger info "buffering message until fully connected"
        buffer write message
        Promise.successful(Success)
      }
    }

    def receive: Receive = client.receive

  }

  /**
   * Fix bug in standard HttpResponseDecoder for web socket clients. When status 101 is received for Hybi00, there are 16
   * bytes of contents expected
   */
  class WebSocketHttpResponseDecoder extends HttpResponseDecoder {

    val codes = List(101, 200, 204, 205, 304)

    protected override def isContentAlwaysEmpty(msg: HttpMessage) = {
      msg match {
        case res: HttpResponse => codes contains res.getStatus.getCode
        case _ => false
      }
    }
  }

  implicit val executionContext =
    ExecutionContext.fromExecutorService(new ForkJoinPool(
      Runtime.getRuntime.availableProcessors(),
      ForkJoinPool.defaultForkJoinWorkerThreadFactory,
      new UncaughtExceptionHandler {
        def uncaughtException(t: Thread, e: Throwable) {
          e.printStackTrace()
        }
      },
      true))

  /**
   * A WebSocket related exception
   *
   * Copied from https://github.com/cgbystrom/netty-tools
   */
  class WebSocketException(s: String,  th: Throwable) extends java.io.IOException(s, th) {
    def this(s: String) = this(s, null)
  }

  class PingPongHandler(logger: InternalLogger) extends IdleStateAwareChannelHandler {

    override def channelIdle(ctx: ChannelHandlerContext, e: IdleStateEvent) {
      if (e.getState == IdleState.READER_IDLE || e.getState == IdleState.WRITER_IDLE) {
        logger.debug("Sending ping")
        e.getChannel.write(new PingWebSocketFrame())
      }
    }
  }

  trait Throttle {
    def delay: Duration
    def maxWait: Duration
    def next(): Throttle
  }
  case object NoThrottle extends Throttle {

    val delay = 0.millis
    val maxWait = 0.millis

    def next(): Throttle = NoThrottle
  }
  case class ActiveThrottle(delay: Duration, maxWait: Duration) extends Throttle {

    def next(): Throttle = {
      copy(delay = delay.doubled min maxWait)
    }
  }
}

trait Connectable { self: BroadcastChannelLike =>
  def isConnected: Boolean
  def connect(): Future[OperationResult]
}
trait Reconnectable {
  def reconnect(): Future[OperationResult]
}
trait WebSocketLike extends BroadcastChannelLike {

  def receive: WebSocket.Receive
}


trait WebSocket extends WebSocketLike with Connectable with Reconnectable {

  import WebSocket.{WebSocketHost, Throttle, NoThrottle }

  def uri: URI

  def protocols: Seq[String] = Nil

  def version: WebSocketVersion = WebSocketVersion.V13

  def initialHeaders: Map[String, String] = Map.empty[String, String]

  def ping = Timeout(60 seconds)

  def throttle: Throttle = NoThrottle

  def bufferPath: File = null

  def buffered = false

  private[websocket] def raiseEvents: Boolean = false

  implicit protected def executionContext = WebSocket.executionContext
  implicit protected def formats: Formats = DefaultFormats

  private[websocket] lazy val channel: BroadcastChannel with Connectable with Reconnectable = new WebSocketHost(this)

  def isConnected = channel.isConnected

  final def !(message: WebSocketOutMessage) = send(message)

  final def connect(): Future[OperationResult] = channel.connect()


  def reconnect() = channel.reconnect()

  final def close(): Future[OperationResult] = channel.close()

  final def send(message: WebSocketOutMessage): Future[OperationResult] = channel.send(message)

}

trait BufferedWebSocket { self: WebSocket =>

  override def throttle = ActiveThrottle(500 millis, 30 minutes)

  override def bufferPath = new File("./work/buffer.log")
  override def buffered = true

}
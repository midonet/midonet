/*
 * Copyright 2016 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.cluster.services.state.client

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference

import scala.PartialFunction._
import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}

import com.google.protobuf.Message

import io.netty.bootstrap.Bootstrap
import io.netty.channel._
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.protobuf.{ProtobufDecoder, ProtobufEncoder, ProtobufVarint32FrameDecoder, ProtobufVarint32LengthFieldPrepender}
import io.netty.handler.timeout.{ReadTimeoutException, ReadTimeoutHandler}

import rx.Observer

/**
  * A Connection class that establishes a TCP connection and allows the exchange
  * of Protocol Buffers [[Message]]s with the remote server.
  * Type parameter S stands for the subclass of [[Message]]s it is able to send.
  * Type parameter R defined the subclass of Message that it receives and
  * propagates through the observer.
  *
  * The life-cycle is that of a single connection. Once disconnected it cannot
  * be reconnected: A new instance should be created.
  *
  * @param host is the server host
  * @param port is the server port
  * @param observer is the Observer that, after a successful connect, will
  *                   receive zero or more messages (onNext event), and one of
  *                   onCompleted or onError when the connection is closed
  *                   gracefully (by client or server) or in the event of an
  *                   error.
  * @param decoder is a protobuf [[Message]] used as a base for decoding
  *                incoming messages.
  *                Usually MyMessageType.getDefaultInstance()
  * @param eventLoopGroup is the [[NioEventLoopGroup]] to be used for Netty's
  *                       event loop.
  *
  * Connection
  *
  * Use [[connect()]] to establish the connection. If the connection succeeds,
  * the returned [[Future]] will complete and the Connection will exhibit
  * Observable behavior by posting events to the [[observer]]. No events
  * will be generated for a failed connect.
  *
  * Events
  *
  * The event stream will be that of zero or more onNext messages, followed by
  * a terminating onCompleted or onError message.
  *
  * onNext(msg): will be called for every incoming [[Message]]
  *
  * onCompleted(): will be called when the connection is closed gracefully,
  *                either a local or remote close (see [[close()]])
  *
  * onError(cause): will be called if the connection is closed due to an error
  */
class Connection[S <: Message, R <: Message]
                (host: String,
                 port: Int,
                 observer: Observer[R],
                 decoder: R,
                 connectTimeout: Duration = Connection.DefaultConnectTimeout,
                 readTimeout: Duration = Connection.DefaultReadTimeout)
                (implicit eventLoopGroup: NioEventLoopGroup)

    extends SimpleChannelInboundHandler[Message] {

    import Connection._

    private val state = new AtomicReference(Init: State)

    def isConnected: Boolean = cond(state.get)  { case Connected(_)  => true }
    private def isConnecting(s:State) = cond(s) { case Connecting(_) => true }

    /**
      * override this method to provide a custom pipeline
      */
    protected def initPipeline(pipeline: ChannelPipeline,
                               channelHandler: ChannelHandler) = {
        pipeline.addLast("frameDecoder", new ProtobufVarint32FrameDecoder)
        pipeline.addLast("protobufDecoder", new ProtobufDecoder(decoder))

        pipeline.addLast("frameEncoder", new  ProtobufVarint32LengthFieldPrepender)
        pipeline.addLast("protobufEncoder", new ProtobufEncoder())
        pipeline.addLast("readTimeoutHandler",
                         new ReadTimeoutHandler(readTimeout.toSeconds.toInt))
        pipeline.addLast(channelHandler)
    }

    private def createBootstrap(channelHandler: ChannelHandler) = new Bootstrap()
        .group(eventLoopGroup)
        .channel(classOf[NioSocketChannel])
        // send TCP keep-alive messages
        .option[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, true)
        // no need for delays, already have flush()
        .option[java.lang.Boolean](ChannelOption.TCP_NODELAY, true)
        // we want async close
        .option[java.lang.Integer](ChannelOption.SO_LINGER, -1)
        // set connection timeout
        .option[java.lang.Integer](ChannelOption.CONNECT_TIMEOUT_MILLIS,
                                   connectTimeout.toMillis.toInt)
        .handler(new ChannelInitializer[io.netty.channel.Channel] {
            override def initChannel(ch: Channel) = {
                initPipeline(ch.pipeline(), channelHandler)
            }
        })

    private def completeConnection(promise: Promise[Unit],
                                   future: ChannelFuture): Unit = {
        val currentState = state.get
        if (future.isSuccess) {
            val newState = Connected(future.channel())

            if (isConnecting(currentState)
                && state.compareAndSet(currentState, newState)) {

                promise.success(())
            } else {
                // connect cancelled: Cleanup
                future.channel().close()
                promise.failure(connectionCancelledException)
            }
        } else {
            state.compareAndSet(currentState, Closed)
            promise.failure(future.cause())
        }
    }

    /**
      * Initiates the connection to the remote server
      *
      * @return a Future that will signal completion
      */
    def connect(): Future[Unit] = {
        state.get match {
            case Init => if (state.compareAndSet(Init, PreConnect)) {
                val channelFuture = createBootstrap(this).connect(host, port)
                if (state.compareAndSet(PreConnect,
                                        Connecting(channelFuture))) {

                    val promise = Promise[Unit]

                    channelFuture.addListener(new ChannelFutureListener {
                        override def operationComplete(future: ChannelFuture) =
                            completeConnection(promise, future)
                        })

                    promise.future
                } else {
                    // cancel and cleanup if succeeded
                    channelFuture.addListener(new ChannelFutureListener {
                        override def operationComplete(future: ChannelFuture) =
                            if (future.isSuccess) {
                                future.channel().close()
                            }
                    })
                    channelFuture.cancel(false)

                    Future.failed(connectionCancelledException)
                }
            } else {
                Future.failed(connectionInProgressException)
            }

            case PreConnect | Connecting(_) =>
                Future.failed(connectionInProgressException)
            case _ => Future.failed(alreadyConnectedException)
        }
    }

    /**
      * Triggers delivery of all data in Netty's send buffer. Asynchronous.
      */
    def flush(): Boolean = {
        state.get match {
            case Connected(ctx) => ctx.flush(); true
            case _ => false
        }
    }

    /**
      * Stores a message in Netty's send buffer. Asynchronous.
      * By default, the send buffer is flushed (see [[flush()]])
      *
      * @param msg the [[Message]] to send
      * @param flush flush after this message or not (default is true)
      */
    def write(msg: S, flush: Boolean = true): Boolean = {
        state.get match {
            case Connected(ctx) =>
                if (flush) ctx.writeAndFlush(msg) else ctx.write(msg)
                true
            case _ => false
        }
    }

    /**
      * Terminates the connection. Asynchronous.
      */
    def close(): Unit = {
        state.getAndSet(Closed) match {
            case Connected(ctx) =>
                ctx.close()
            case Connecting(channelFuture) =>
                channelFuture.cancel(false)
            case _ =>
        }
    }

    override def toString = s"$host:$port"

    protected override def channelRead0(ctx: ChannelHandlerContext,
                                        msg: Message) = {
        state.get match {
            case Connected(_) =>
                observer.onNext(msg.asInstanceOf[R])
            case _ =>
        }
    }

    protected override def exceptionCaught(ctx: ChannelHandlerContext,
                                           cause: Throwable): Unit = {
        state.getAndSet(Closed) match {
            case Connected(_) =>
                ctx.close()
                observer.onError(cause)
            case _ =>
        }
    }

    protected override def channelInactive(ctx: ChannelHandlerContext): Unit = {

        state.getAndSet(Closed) match {
            case Connected(_) => observer.onCompleted()
            case _ =>
        }
    }
}

object Connection {

    val DefaultConnectTimeout = 5 seconds
    val DefaultReadTimeout = 10 seconds

    private sealed trait State
    private case object Init extends State
    private case object PreConnect extends State
    private case class Connecting(future: ChannelFuture) extends State
    private case class Connected(context: Channel) extends State
    private case object Closed extends State

    private def connectionInProgressException =
        new IllegalStateException("Connection already in progress")

    private def alreadyConnectedException =
        new IllegalStateException("Already connected")

    private def connectionCancelledException =
        new CancellationException("Connection cancelled")
}

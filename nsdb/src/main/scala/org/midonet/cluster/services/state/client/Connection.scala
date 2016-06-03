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
import scala.concurrent.{Future, Promise}

import com.google.protobuf.{Message, MessageLite}

import io.netty.bootstrap.Bootstrap
import io.netty.channel._
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.protobuf.{ProtobufDecoder, ProtobufEncoder, ProtobufVarint32FrameDecoder, ProtobufVarint32LengthFieldPrepender}

/**
  * A Connection class that establishes a TCP connection and allows the exchange
  * of Protocol Buffers [[Message]]s with the remote server.
  *
  * The life-cycle is that of a single connection. Once disconnected it cannot
  * be reconnected: A new instance should be created.
  *
  * @param host is the server host
  * @param port is the server port
  * @param subscriber is the Observer that, after a successful connect, will
  *                   receive zero or more messages (onNext event), and one of
  *                   onCompleted or onError when the connection is closed
  *                   gracefully (by client or server) or in the event of an
  *                   error.
  * @param decoder is a protobuf [[MessageLite]] used as a base for decoding
  *                incoming messages.
  * @param eventLoopGroup is the [[NioEventLoopGroup]] to be used for Netty's
  *                       event loop.
  *
  * Connection
  *
  * Use [[connect()]] to establish the connection. If the connection succeeds,
  * the returned [[Future]] will complete and the Connection will exhibit
  * Observable behavior by posting events to the [[subscriber]]. No events
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
class Connection(host: String,
                 port: Int,
                 subscriber: Connection.Observer,
                 decoder: MessageLite)
                 (implicit eventLoopGroup: NioEventLoopGroup)

                 extends SimpleChannelInboundHandler[Message] {

    import Connection._

    private val state = new AtomicReference(Init: State)

    def isConnected: Boolean = cond(state.get){ case Connected(_) => true }

    /**
      * override this method to provide a custom pipeline
      */
    protected def initPipeline(pipeline: ChannelPipeline,
                               channelHandler: ChannelHandler) = {
        pipeline.addLast("frameDecoder", new ProtobufVarint32FrameDecoder)
        pipeline.addLast("protobufDecoder", new ProtobufDecoder(decoder))

        pipeline.addLast("frameEncoder", new  ProtobufVarint32LengthFieldPrepender)
        pipeline.addLast("protobufEncoder", new ProtobufEncoder())

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
        .handler(new ChannelInitializer[io.netty.channel.Channel] {
            override def initChannel(ch: Channel) = {
                initPipeline(ch.pipeline(), channelHandler)
            }
        })

    private def completeConnection(promise: Promise[Unit],
                                   future: ChannelFuture): Unit = {
        if (future.isSuccess) {
            val curState = state.get
            val newState = Connected(future.channel())

            if (   !isConnecting(curState)
                || !state.compareAndSet(curState, newState)
                || !promise.trySuccess(())) {

                // connect canceled: Cleanup
                future.channel().close()
                state.set(Closed)
                promise.tryFailure(connectionCancelledException)
            }
        } else {
            state.set(Closed)
            promise.tryFailure(future.cause())
        }
    }

    /**
      * Initiates the connection to the remote server
      *
      * @return a Future that will signal completion
      */
    def connect(): Future[Unit] = {
        state.get match {
            case Init => if (state.compareAndSet(Init,PreConnect)) {
                val promise = Promise[Unit]
                val channelFuture = createBootstrap(this).connect(host, port)
                if (state.compareAndSet(PreConnect,
                                        Connecting(channelFuture))) {

                    channelFuture.addListener(new ChannelFutureListener {
                        override def operationComplete(future: ChannelFuture) =
                            completeConnection(promise, future)
                        })

                    promise.future
                } else {
                    // can only happen when cancelled with close()
                    // in that case stop() has taken care of the cleanup
                    // state is already Closed
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
    def write(msg: Message, flush: Boolean = true): Boolean = {
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
        val currentState = state.get
        currentState match {
            case Connected(ctx) =>
                ctx.close()
            case Connecting(channelFuture) =>
                channelFuture.cancel(false)
            case _ =>
        }
        state.set(Closed)
    }

    protected override def channelRead0(ctx: ChannelHandlerContext,
                                        msg: Message) = {

        state.get match {
            case Connected(_) => subscriber.onNext(msg)
            case _ =>
        }
    }

    protected override def exceptionCaught(ctx: ChannelHandlerContext,
                                           cause: Throwable): Unit = {

        state.get match {
            case Connected(_) => subscriber.onError(cause)
            case _ =>
        }
        state.set(Closed)
    }

    protected override def channelInactive(ctx: ChannelHandlerContext): Unit = {
        state.get match {
            case Connected(_) => subscriber.onCompleted()
            case _ =>
        }
        state.set(Closed)
    }
}

object Connection {
    type Observer = rx.Observer[Message]

    private sealed trait State
    private case object Init extends State
    private case object PreConnect extends State
    private case class Connecting(future: ChannelFuture) extends State
    private case class Connected(context: Channel) extends State
    private case object Closed extends State

    private def isConnecting(s:State) = cond(s){case Connecting(_)=>true}

    private def connectionInProgressException =
        new IllegalStateException("Connection already in progress")

    private def alreadyConnectedException =
        new IllegalStateException("Already connected")

    private def connectionCancelledException =
        new CancellationException("Connection cancelled")
}
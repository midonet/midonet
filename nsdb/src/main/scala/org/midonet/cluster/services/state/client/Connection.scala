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

import com.google.protobuf.{Message, MessageLite}
import io.netty.bootstrap.Bootstrap
import io.netty.channel._
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.protobuf.{ProtobufDecoder, ProtobufEncoder, ProtobufVarint32FrameDecoder, ProtobufVarint32LengthFieldPrepender}
import rx.Observer

import scala.concurrent.{ExecutionContext, Future, Promise}

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
                 subscriber: Observer[Message],
                 decoder: MessageLite,
                 eventLoopGroup: NioEventLoopGroup)
                 (implicit ec: ExecutionContext)

                 extends SimpleChannelInboundHandler[Message] {

    import Connection._

    private val bootstrap = createBootstrap(this)
    private var currentContext = None : Option[ChannelHandlerContext]
    private val sync = new Object

    def isConnected : Boolean = sync.synchronized { currentContext.isDefined }

    /**
      * override this method to provide a custom codec for handling messages
      */
    protected def initPipeline(pipeline: ChannelPipeline,
                               channelHandler: ChannelHandler) = {
        pipeline.addLast("frameDecoder",new ProtobufVarint32FrameDecoder)
        pipeline.addLast("protobufDecoder",new ProtobufDecoder(decoder))
        pipeline.addLast("frameEncoder",new  ProtobufVarint32LengthFieldPrepender)
        pipeline.addLast("protobufEncoder", new ProtobufEncoder())
        pipeline.addLast(channelHandler)
    }

    private def createBootstrap(channelHandler: ChannelHandler) = new Bootstrap()
        .group(eventLoopGroup)
        .channel(classOf[NioSocketChannel])
        // send TCP keep-alive messages
        .option(ChannelOption.SO_KEEPALIVE, Boolean.box(true))
        // no need for delays, already have flush()
        .option(ChannelOption.TCP_NODELAY, Boolean.box(true))
        // we want async close
        .option(ChannelOption.SO_LINGER, Int.box(-1))
        .handler(new ChannelInitializer[io.netty.channel.Channel] {
            override def initChannel(ch: Channel) = {
                initPipeline(ch.pipeline(),channelHandler)
                //pipeline.addLast(new StateProxyProtocolEncoder,
                // new StateProxyProtocolDecoder, channelHandler)
            }
        })

    /**
      * Initiates the connection to the remote server
      * @return a Future that will signal the connection result
      * @throws [[java.lang.AssertionError]] if called more than once
      */
    def connect(): Future[Connection] = {
        sync.synchronized {
            assert(currentContext.isEmpty)
            toScalaFuture(bootstrap.connect(host, port)).map(_ => this)
        }
    }

    /**
      * Triggers delivery of all data in Netty's send buffer. Asynchronous.
      */
    def flush(): Unit = {
        sync.synchronized { // needed?
            assert(currentContext.isDefined)
            currentContext.get.flush()
        }
    }

    /**
      * Stores a message in Netty's send buffer. Asynchronous.
      * By default, the send buffer is flushed (see [[flush()]])
      * @param msg the [[Message]] to send
      * @param flush wether to flush after this message or not (default is true)
      */
    def write(msg: Message, flush: Boolean = true): Unit = {
        sync.synchronized { // needed ?
            assert(currentContext.isDefined)
            val ctx = currentContext.get
            if (flush) ctx.writeAndFlush(msg) else ctx.write(msg)
        }
    }

    /**
      * Terminates the connection. Asynchronous.
      */
    def close(): Unit = {
        sync.synchronized {
            if (currentContext.isDefined) {
                currentContext.get.close()
                currentContext = None
            }
        }
    }

    protected override def channelRead0(ctx: ChannelHandlerContext,
                                        msg: Message) = {

        if (currentContext.isDefined) {
            subscriber.onNext(msg)
        }
    }

    protected override def exceptionCaught(ctx: ChannelHandlerContext,
                                           cause: Throwable): Unit = {

        if (resetContext()) subscriber.onError(cause)
    }

    protected override def handlerAdded(ctx: ChannelHandlerContext): Unit = {
        sync.synchronized {
            currentContext = Some(ctx)
        }
    }

    protected override def channelInactive(ctx: ChannelHandlerContext): Unit = {
        if (resetContext()) subscriber.onCompleted()
    }

    private def resetContext() = {
        sync.synchronized {
            val defined = currentContext.isDefined
            if (defined) currentContext = None
            defined
        }
    }
}

object Connection {

    def toScalaFuture(channelFuture: ChannelFuture): Future[ChannelFuture] = {
        val promise = Promise[ChannelFuture]

        channelFuture.addListener(new ChannelFutureListener {
            override def operationComplete(future: ChannelFuture) {
                if ( future.isSuccess ) {
                    promise.success(future)
                } else {
                    promise.failure(future.cause())
                }
            }
        })
        promise.future
    }
}
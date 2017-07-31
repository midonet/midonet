/*
 * Copyright 2017 Midokura SARL
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

package org.midonet.cluster.services.endpoint.comm

import scala.reflect.{classTag, ClassTag}

import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.{ChannelHandlerContext, SimpleChannelInboundHandler}
import io.netty.util.ReferenceCountUtil
import org.slf4j.LoggerFactory
import rx.subjects.PublishSubject

/**
 * Api message handler.
 * It reports connection establishments and disconnections, and forwards
 * any incoming message to the given observer.
 * Note that the event observable is not expected to emit onCompleted/onError
 * events, as they are transformed into ApiEvents. In particular, an error in a
 * stream does not prevent the same or another client to connect afterwards and
 * continue providing messages for a new connection via the same observable.
 */
@Sharable
class IncomingHandler[MessageIn: ClassTag]
    (connectionHandler: Option[ConnectionHandler[MessageIn, _]] = None)
    extends SimpleChannelInboundHandler[MessageIn](
        classTag[MessageIn].runtimeClass.asInstanceOf[Class[MessageIn]]) {
    private val log = LoggerFactory.getLogger(classOf[IncomingHandler[MessageIn]])

    private val output = PublishSubject.create[IncomingEvent]()

    /** Incoming event stream.
      * Note that this is a hot observable that starts producing events
      * once the first connection is established */
    def observable = output.serialize

    connectionHandler.foreach(observable.subscribe)

    def this(cnxHandler: ConnectionHandler[MessageIn, _]) =
        this(Some(cnxHandler))

    override protected[comm] def channelActive(ctx: ChannelHandlerContext): Unit = {
        output.onNext(IncomingConnect(ctx))
        super.channelActive(ctx)
    }

    override protected[comm] def channelInactive(ctx: ChannelHandlerContext): Unit = {
        output.onNext(IncomingDisconnect(ctx))
        super.channelInactive(ctx)
    }

    override protected[comm] def exceptionCaught(ctx: ChannelHandlerContext,
                                           cause: Throwable): Unit = {
        log.warn("channel exception: " + ctx, cause)
        output.onNext(IncomingError(ctx, cause))
    }

    override protected[comm] def channelRead0(ctx: ChannelHandlerContext,
                                        msg: MessageIn): Unit = {
        // Note: any one wanting to keep the msg contents beyond the
        // onNext call should explicitly call the 'ReferenceCountUtil.retain'
        // method on it (and explicitly release it with
        // 'ReferenceCountUtil.release' once it is not going to be used
        // anymore.
        output.onNext(IncomingMsg(ctx, msg))
        ReferenceCountUtil.release(msg)
    }
}

/**
 * Api events.
 */
abstract class IncomingEvent
case class IncomingConnect(ctx: ChannelHandlerContext) extends IncomingEvent
case class IncomingDisconnect(ctx: ChannelHandlerContext) extends IncomingEvent
case class IncomingError(ctx: ChannelHandlerContext, exc: Throwable) extends IncomingEvent
case class IncomingMsg[TMessage: ClassTag](ctx: ChannelHandlerContext, msg: TMessage) extends IncomingEvent

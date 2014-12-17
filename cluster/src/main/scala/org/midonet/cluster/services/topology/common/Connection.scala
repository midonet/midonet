/*
 * Copyright 2014 Midokura SARL
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

package org.midonet.cluster.services.topology.common

import java.util.concurrent.atomic.AtomicBoolean

import scala.util.Success

import com.google.protobuf.Message

import io.netty.channel.ChannelHandlerContext
import org.slf4j.LoggerFactory
import rx.Subscriber
import rx.subjects.{PublishSubject, Subject}


/**
 * Connection state holder
 * Note: this class exposes an observer (a subject) to receive the
 * messages that should be sent back through the associated
 * low-level communication channel.
 * @param ctx is the low level communication channel
 * @param protocol is the factory generating the start state for the
 *                 communication protocol
 * @param mgr is the ConnectionManager responsible for this particular
 *            connection (used to unregister itself when the low level
 *            communication channel is severed)
 */
class Connection(private val ctx: ChannelHandlerContext,
                 private val protocol: ProtocolFactory)
                (implicit val mgr: ConnectionManager)
    extends Subscriber[Message] {
    private val log = LoggerFactory.getLogger(classOf[Connection])

    // Connection has already been disconnected
    private val done = new AtomicBoolean(false)

    // Send a message through the low level channel
    // TODO: some backpressure mechanism is probably needed here
    private def send(rsp: Message) = if (!done.get()) ctx.writeAndFlush(rsp)

    // Terminate this connection
    private def terminate() = if (done.compareAndSet(false, true)) {
        backendSubscription.value match {
            case Some(Success(Some(subs))) => subs.unsubscribe()
            case _ =>
        }
        ctx.close()
        mgr.unregister(ctx)
    }

    // Process the messages on the outgoing stream
    override def onCompleted(): Unit = terminate()
    override def onError(e: Throwable): Unit = terminate()
    override def onNext(rsp: Message): Unit = send(rsp)

    // State engine
    // TODO: This is not thread-safe, which is currently fine
    // as each channel is currently handled by a single thread at most.
    private var (state, backendSubscription) = protocol.start(this)
    def disconnect() = {
        state = state.process(Interruption)
        // in case protocol does not honor the disconnect request:
        terminate()
    }
    def msg(req: Message) = {
        state = state.process(req)
        // note: the req message is being released by the caller;
        // its refcount should be increased (via 'retain') by the
        // protocol, if needed)
    }
    def error(e: Throwable) = {
        state = state.process(e)
    }
}

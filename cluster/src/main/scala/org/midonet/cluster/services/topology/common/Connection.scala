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

import com.google.protobuf.Message

import io.netty.channel.ChannelHandlerContext
import rx.Observer

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
    extends Observer[Message] {

    // Connection has already been disconnected
    private val done = new AtomicBoolean(false)

    // Send a message through the low level channel
    // TODO: some backpressure mechanism is probably needed here
    private def send(rsp: Message) = if (!done.get()) ctx.writeAndFlush(rsp)

    // Terminate this connection
    private def terminate() = if (done.compareAndSet(false, true)) {
        ctx.close()
        mgr.unregister(ctx)
    }

    // Process the messages on the outgoing stream
    override def onCompleted(): Unit = terminate()
    override def onError(e: Throwable): Unit = terminate()
    override def onNext(rsp: Message): Unit = send(rsp)

    // State engine
    // NOTE: This is not thread-safe, which is currently fine
    // as each channel is currently handled by a single thread.
    private var state = protocol.start(this)

    /**
     * Dismiss the connection state (called upon netty disconnection)
     */
    def disconnect() = {
        state = state.process(Interruption)
        // in case protocol does not honor the disconnect request:
        terminate()
    }

    /**
     * Process a protobuf message received from netty
     * @param req is a protobuf message encoding either a request or a
     *            response in a given protocol. Note that it is not safe
     *            to keep this protobuf for future use without increasing
     *            its reference counter via 'retain' (and releasing for it
     *            when no longer needed via 'ReferenceCountUtils.release'
     */
    def msg(req: Message) = {
        state = state.process(req)
    }

    /**
     * Process exceptions originated in the netty pipeline (normally they
     * are not recuperable, but the high level protocol may want to take
     * some action).
     * @param e the captured exception
     */
    def error(e: Throwable) = {
        state = state.process(e)
    }
}

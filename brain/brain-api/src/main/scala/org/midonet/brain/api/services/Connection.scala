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

package org.midonet.brain.api.services

import com.google.protobuf.Message
import io.netty.channel.ChannelHandlerContext
import org.slf4j.LoggerFactory
import rx.Subscriber
import rx.subjects.{PublishSubject, Subject}

import org.midonet.cluster.models.Commons

/**
 * Connection state holder
 * @param ctx is the low level communication channel
 * @param clMgr is the manager handling persistent back-end subscriptions
 *              for each client
 * @param mgr is the ConnectionManager responsible for this particular
 *            connection (used to unregister itself when the low level
 *            communication channel is severed)
 */
class Connection(private val ctx: ChannelHandlerContext,
                 private val clMgr: ClientManagerBase)
                (implicit val mgr: ConnectionManager)
    extends Subscriber[Message] {
    private val log = LoggerFactory.getLogger(classOf[Connection])

    // Stream of messages to be sent back through the communication channel
    private val outgoing: Subject[Message, Message] =
        PublishSubject.create()
    outgoing.subscribe(this)

    // Function to retrieve a back-end subscription handle (aka client)
    // from the session id
    private object ClFactory extends State.ClientFactory{
        def apply(id: Commons.UUID, onSuccess: Message,
                  onReject: Message): Option[ClientBase] = {
            clMgr.get(id, outgoing, onSuccess, onReject)
        }
    }

    // Send a message through the low level channel
    private def send(rsp: Message) = ctx.writeAndFlush(rsp)

    // Terminate this connection
    private def terminate = {
        this.unsubscribe()
        ctx.close()
        mgr.unregister(ctx)
    }

    // Process the messages on the outgoing stream
    override def onCompleted(): Unit = terminate
    override def onError(e: Throwable): Unit = terminate
    override def onNext(rsp: Message): Unit = send(rsp)

    // State engine
    // TODO: This is not thread-safe, which is currently fine
    // as each channel is currently handled by a single thread at most.
    private var state = State.start(ClFactory)
    def disconnect() = {
        state = state.process(Suspend)
    }
    def msg(req: Message) = {
        state = state.process(req)
    }
    def error(e: Throwable) = {
        state = state.process(e)
    }
}

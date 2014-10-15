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

import io.netty.channel.ChannelHandlerContext
import org.slf4j.LoggerFactory
import rx.Subscriber
import rx.subjects.{BehaviorSubject, Subject}

import org.midonet.cluster.rpc.Commands


/**
 * Connection channel
 */
class Connection(private val ctx: ChannelHandlerContext,
                 private val protocol: Protocol)
                (implicit val mgr: ConnectionManager)
    extends Subscriber[Response] {
    private val log = LoggerFactory.getLogger(classOf[Connection])
    private val inbox: Subject[Command, Command] = BehaviorSubject.create()
    private val subs = inbox.subscribe(protocol)
    protocol.outgoing.subscribe(this)

    private def send(rsp: Response) = ctx.writeAndFlush(Response.encode(rsp))
    private def terminate = {
        inbox.onCompleted()
        subs.unsubscribe()
        this.unsubscribe()
        ctx.close()
        mgr.unregister(ctx)
    }

    override def onCompleted(): Unit = terminate
    override def onError(e: Throwable): Unit = terminate
    override def onNext(rsp: Response): Unit = send(rsp)

    def disconnect = inbox.onCompleted()
    def msg(req: Commands.Request) = inbox.onNext(Command.parse(req))
    def error(e: Throwable) = inbox.onCompleted()
}

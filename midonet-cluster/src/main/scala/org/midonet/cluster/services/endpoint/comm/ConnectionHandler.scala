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

import scala.reflect.ClassTag

import org.slf4j.LoggerFactory

import rx.Observer

import io.netty.channel.ChannelHandlerContext

/**
 * Collects events from the netty pipeline and distributes them to the
 * appropriate connection states
 */
class ConnectionHandler[ReqType: ClassTag, RespType: ClassTag](
    private val mgr: ConnectionManager[ChannelHandlerContext, ReqType, RespType])
    extends Observer[IncomingEvent] {
    private val log =
        LoggerFactory.getLogger(classOf[ConnectionHandler[ReqType, RespType]])

    override def onCompleted(): Unit = {
        log.error("server link terminated")
        mgr.dispose()
    }
    override def onError(e: Throwable): Unit = {
        log.error("server link error", e)
        mgr.dispose()
    }
    override def onNext(event: IncomingEvent): Unit = event match {
        case IncomingConnect(ctx) =>
            // Note that the 'get' method in the connection manager
            // internally registers the connection
            mgr.get(ctx)
            log.debug("connection established: {}", ctx)
        case IncomingDisconnect(ctx) =>
            mgr.get(ctx).foreach(_.disconnect())
            mgr.forget(ctx)
            log.debug("connection terminated: {}", ctx)
        case IncomingError(ctx, exc) =>
            mgr.get(ctx).foreach(_.error(exc))
            log.debug("connection error: " + ctx, exc)
        case IncomingMsg(ctx, msg) => msg match {
            // WARNING: the msg buffer will be released upon return
            // Contents should be copied if they are to be kept.
            case req: ReqType => mgr.get(ctx).foreach(_.msg(req))
            case _ => log.warn("unexpected message: {}", msg)
        }
    }
}


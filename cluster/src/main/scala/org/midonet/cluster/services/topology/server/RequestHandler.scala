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

package org.midonet.cluster.services.topology.server

import org.slf4j.LoggerFactory
import rx.Observer
import rx.functions.{Action0, Action1}
import rx.subjects.{PublishSubject, Subject}

import org.midonet.cluster.services.topology.common._

import io.netty.util.ReferenceCountUtil

/**
 * Processes the requests from the server front-ends.
 * It exposes a subject where the communication events are
 * put by the low-level communication engine, via onNext.
 * This subject should not be completed by the low-level
 * communication engine, as there might be different entities
 * pushing events to this subject; a 'Disconnect' event is the
 * proper way to indicate that a communication channel is not
 * available anymore.
 */
class RequestHandler(private val connMgr: ConnectionManager)
    extends Observer[CommEvent] {
    private val log = LoggerFactory.getLogger(classOf[RequestHandler])

    override def onNext(req: CommEvent): Unit  = req match {
        case Connect(ctx) =>
            connMgr.get(ctx)
            log.debug("api connection established")
        case Disconnect(ctx) =>
            connMgr.get(ctx).disconnect
            log.debug("api connection terminated")
        case Error(ctx, exc) =>
            connMgr.get(ctx).error(exc)
            log.debug("api connection error", exc)
        case Request(ctx, pb) =>
            // WARNING: the reference to the protobuf will be
            // automatically released by the netty back-end;
            // if some component needs to keep it, it should
            // increase the object refcount via 'retain'
            connMgr.get(ctx).msg(pb)
    }

    override def onCompleted(): Unit = {
        log.error("terminated link with topology service clients")
    }

    override def onError(exc: Throwable): Unit = {
        log.error("broken link with topology service clients", exc)
    }
}

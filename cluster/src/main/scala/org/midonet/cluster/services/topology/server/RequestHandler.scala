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
class RequestHandler(private val connMgr: ConnectionManager) {
    private val log = LoggerFactory.getLogger("RequestHandler")

    val subject: Subject[CommEvent, CommEvent] = PublishSubject.create()

    subject.subscribe(new Action1[CommEvent] {
        override def call(req: CommEvent): Unit = req match {
            case Connect(ctx) =>
                connMgr.get(ctx)
                log.debug("api connection established")
            case Disconnect(ctx) =>
                connMgr.get(ctx).disconnect
                log.debug("api connection terminated")
            case Error(ctx, exc) =>
                connMgr.get(ctx).error(exc)
                log.debug("api connection error")
            case Request(ctx, pb) =>
                connMgr.get(ctx).msg(pb)
                // WARNING: we release the reference to the object;
                // if some component needs to keep it, it should
                // increase the object refcount via 'retain'
                ReferenceCountUtil.release(pb)
                log.debug("protobuf received")
        }
    }, new Action1[Throwable] {
        override def call(e: Throwable): Unit = {
            log.error("broken link with topology service clients", e)
        }
    }, new Action0 {
        override def call(): Unit = {
            log.error("terminated link with topology service clients")
        }
    })
}

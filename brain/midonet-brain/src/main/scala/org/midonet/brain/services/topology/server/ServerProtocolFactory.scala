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

package org.midonet.brain.services.topology.server

import java.util.UUID

import org.midonet.brain.services.topology.server.ServerState.CnxnFactory
import org.slf4j.LoggerFactory

import com.google.protobuf.Message
import rx.{Subscription, Observer}

import org.midonet.cluster.services.topology.common.{State, ProtocolFactory}
import org.midonet.util.functors.makeAction0

import scala.concurrent.Promise
import scala.util.Success

/**
 * Protocol handling the server-side communication
 * @param sMgr is the session inventory manager, responsible to maintain
 *             the backend zoom subscriptions for each client.
 */
class ServerProtocolFactory(private val sMgr: SessionInventory)
    extends ProtocolFactory {
    private val log = LoggerFactory.getLogger(classOf[ServerProtocolFactory])

    /**
     * Return the initial state and the future subscription to the client's
     * session.
     * @param out is the stream of messages to be sent to the client
     */
    override def start(out: Observer[Message]): State = {
        val factory = new CnxnFactory {
            private val ready: Promise[Session] = Promise[Session]()
            private val pipe: Promise[Subscription] = Promise[Subscription]()

            override def handshake(cnxnId: UUID, start: Long) : Boolean = try {
                val session = sMgr.claim(cnxnId)
                val subs = session.observable(start).doOnUnsubscribe(
                    makeAction0 {out.onCompleted()}
                ).subscribe(out)
                ready.success(session)
                pipe.success(subs)
                true
            }  catch {
                case error: Exception =>
                    log.warn("cannot establish session: " + cnxnId, error)
                    false
            }

            override def output: Option[Observer[Message]] = Some(out)

            override def session: Option[Session] =
                ready.future.value.filter({_.isSuccess}).map({_.get})

            override def subscription: Option[Subscription] =
                pipe.future.value.filter({_.isSuccess}).map({_.get})
        }
        Ready(factory)
    }
}


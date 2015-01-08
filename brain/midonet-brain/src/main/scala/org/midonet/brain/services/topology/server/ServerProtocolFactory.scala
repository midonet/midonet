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
import rx.Observer

import org.midonet.cluster.services.topology.common.{State, ProtocolFactory}
import org.midonet.util.functors.makeAction0

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
     * @param output is the stream of messages to be sent to the client
     */
    override def start(output: Observer[Message]): State = {
        val factory: CnxnFactory =
            (id: UUID, seq: Long, ack: Message) => try {
                val session = sMgr.claim(id)
                val subs = session.observable(seq).doOnUnsubscribe(
                    makeAction0 {output.onCompleted()}
                ).subscribe(output)
                // NOTE: This ack cannot be injected into the session as
                // a noOp, as it has to be emitted before any messages
                // remaining in the session, in case of recovery
                // (otherwise, the client would reject any prior messages
                // before the handshake ack)
                output.onNext(ack)
                Some((session, subs))
            } catch {
                case error: Exception =>
                    log.warn("cannot establish session: " + id, error)
                    None
            }
        Ready(factory)
    }
}


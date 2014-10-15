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

import scala.concurrent.Promise

import com.google.protobuf.Message

import rx.{Subscription, Observer}
import org.midonet.cluster.services.topology.common.{ProtocolFactory}

/**
 * Protocol handling the server-side communication
 * @param sMgr is the session inventory manager, responsible to maintain
 *             the backend zoom subscriptions for each client.
 */
class ServerProtocolFactory(private val sMgr: SessionInventory)
    extends ProtocolFactory {

    /**
     * Return the initial state and the future subscription to the client's
     * session.
     * @param output is the stream of messages to be sent to the client
     */
    override def start(output: Observer[Message]): ProtocolStart = {
        val subs = Promise[Option[Subscription]]().success(None)
        (Ready(sMgr, output), subs.future)
    }
}


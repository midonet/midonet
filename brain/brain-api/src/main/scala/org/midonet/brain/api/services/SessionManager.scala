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


import scala.collection.concurrent.TrieMap

import com.google.protobuf.Message

import org.slf4j.LoggerFactory
import rx.Observer

import org.midonet.cluster.models.Commons

/**
 * Keep and retrieve client subscriptions for a particular session
 */
trait SessionManagerBase {
    /**
     * Retrieve a client by session id and attach it to an output stream
     */
    def get(id: Commons.UUID, out: Observer[Message],
            onSuccess: Message, onReject: Message): Option[SessionBase]

    /**
     * Forget about a client
     */
    def unregister(id: Commons.UUID)
}
object SessionManagerBase {
    type SessionFactory =
        (Commons.UUID, Message, Message) => Option[SessionBase]
}

class SessionManager extends SessionManagerBase {
    private val log = LoggerFactory.getLogger(classOf[SessionManager])
    private val clients: TrieMap[Commons.UUID, Session] = new TrieMap()

    implicit val mgr: SessionManagerBase = this

    override def get(id: Commons.UUID, out: Observer[Message],
                     onSuccess: Message,
                     onReject: Message): Option[SessionBase] = {
        val candidate = new Session(id)
        val client = clients.putIfAbsent(id, candidate) match {
            case Some(previous) => previous
            case None => candidate
        }
        client.attach(out, onSuccess, onReject)
    }

    override def unregister(id: Commons.UUID): Unit = clients.remove(id)
}

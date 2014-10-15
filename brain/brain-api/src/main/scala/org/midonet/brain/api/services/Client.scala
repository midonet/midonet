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

import java.util.concurrent.atomic.AtomicReference

import com.google.protobuf.Message

import org.slf4j.LoggerFactory
import rx.subjects.Subject

import org.midonet.cluster.models.{Topology, Commons}

/**
 * Liaison between a subscriber and the underlying provider of the update
 * stream of topology elements.
 */
trait ClientBase {

    /**
     * Express interest in a new element of the topology
     */
    def watch(ids: Seq[Commons.UUID], ofType: Topology.Type,
              onSuccess: Message, onReject: Message)

    /**
     * Cancel interest in an element of the topology
     */
    def unwatch(id: Commons.UUID, ofType: Topology.Type,
                onSuccess: Message, onReject: Message)

    /**
     * Client connection is suspended, but subscriptions should be kept
     */
    def suspend()

    /**
     * Client is no longer interested in subscriptions
     */
    def terminate(replying: Message)

    /**
     * Attach the client to a communication channel
     */
    def attach(out: Subject[Message, Message],
               onSuccess: Message, onReject: Message): Option[Client]
}

/**
 * Client backend representation
 */
class Client(id: Commons.UUID)(implicit val mgr: ClientManagerBase)
    extends ClientBase {
    private val log = LoggerFactory.getLogger(classOf[Client])
    private val outgoing =
        new AtomicReference[Subject[Message, Message]](null)
    override def watch(ids: Seq[Commons.UUID], ofType: Topology.Type,
                       onSuccess: Message, onReject: Message) = {
        val out = outgoing.get
        if (out != null) out.onNext(onReject)
    }
    override def unwatch(id: Commons.UUID, ofType: Topology.Type,
                         onSuccess: Message, onReject: Message) = {
        val out = outgoing.get
        if (out != null) out.onNext(onReject)
    }
    override def suspend() = {
        outgoing.set(null)
    }
    override def terminate(replying: Message) = {
        val out = outgoing.get
        if (out != null) out.onNext(replying)
        mgr.unregister(id)
    }
    override def attach(out: Subject[Message, Message], onSuccess: Message,
                        onReject: Message): Option[Client] = {
        if (outgoing.compareAndSet(null, out)) {
            out.onNext(onSuccess)
            Some(this)
        } else {
            out.onNext(onReject)
            None
        }
    }
}

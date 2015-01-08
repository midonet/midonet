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

package org.midonet.cluster.services.topology.common

import com.google.protobuf.Message

import rx.Observer

/**
 * A factory generating the first state of a protocol
 * This is used to obtain the first state of the protocol, and to indicate
 * which is the observer that should be used by the protocol to send back
 * any pertinent messages. The observer must be completed by the protocol
 * to indicate that the protocol has reached a final state and it will not
 * be emitting any more messages.
 */
trait ProtocolFactory {
    /**
     * Provide an observer for any outgoing messages, and get the
     * initial state of the protocol and a potential subscription to a
     * backend provider.
     */
    def start(output: Observer[Message]): ProtocolFactory.State
}

object ProtocolFactory {
    /**
     * The state machine of a Connection to the Topology Cluster. Not sealed just
     * to allow mocking.
     */
    trait State {
        /** Process a message and yield the next state */
        def process(msg: Any): State
    }
}

/**
 * Used to signal the protocol that the underlying connection has been
 * interrupted
 */
case object Interruption

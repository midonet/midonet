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

import scala.concurrent.Future

import com.google.protobuf.Message

import rx.{Observer, Subscription}

/**
 * A factory generating the first state of a protocol
 */
trait ProtocolFactory {
    /**
     * Get the initial state of the protocol and connect to the outgoing stream
     */
    def start(output: Observer[Message]): (State, Future[Option[Subscription]])
}

/**
 * The state machine of a Connection to the Topology Cluster. Not sealed just
 * to allow mocking.
 */
trait State {
    /** Process a message and yield the next state */
    def process(msg: Any): State
}

/**
 * Used to signal the protocol that the underlying connection has been
 * interrupted
 */
case object Interruption

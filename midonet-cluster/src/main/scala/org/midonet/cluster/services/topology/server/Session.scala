/*
 * Copyright 2015 Midokura SARL
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

import java.util.UUID

import com.google.protobuf.Message
import rx.Observable

import org.midonet.cluster.rpc.Commands.Response

/** Broker between an underlying communication channel with a client and the
  * underlying provider of the update stream of topology elements. */
trait Session {
    /** Send a request for an element of the topology. ACK is not required
      * because the data itself will serve as the ACK. */
    def get[T <: Message](id: UUID, ofType: Class[T], reqId: UUID)
    /** Send a request to get the ids of all objects of a given type.
      * ACK is not required because the data itself will serve as the ACK. */
    def getAll[T <: Message](ofType: Class[T], reqId: UUID)
     /** Express interest in an element of the topology. ACK is not required
      * because the data itself will serve as the ACK, given that subscriptions
      * guarantee the latest state of the entity will always be streamed as
      * soon as the subscription is made. */
    def watch[T <: Message](id: UUID, ofType: Class[T], reqId: UUID)
    /** Express interest in all the entities of the given type
      * The ACK is necessary so that we can inform the client that the
      * full subscription was received */
    def watchAll[T <: Message](ofType: Class[T], reqId: UUID)
    /** Cancel interest in an element of the topology. ACK confirms that the
      * unsubscription happened. */
    def unwatch[T <: Message](id: UUID, ofType: Class[T], reqId: UUID)
    /** Cancel interest in all elements of the given type */
    def unwatchAll[T <: Message](ofType: Class[T], reqId: UUID)
    /** The session should be terminated and all associated data updates should
      * be canceled */
    def terminate()
    /** Inject a response into the session output stream, without performing
      * any other operation */
    def noOp(rsp: Response)
    /** Use this observable to subscribe for responses */
    def observable(seqno: Long = 0): Observable[Response]

}

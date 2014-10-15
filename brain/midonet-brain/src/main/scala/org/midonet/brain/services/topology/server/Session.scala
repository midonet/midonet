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

import com.google.protobuf.Message
import rx.Observable

import org.midonet.cluster.rpc.Commands
import org.midonet.cluster.rpc.Commands.Response
import org.midonet.cluster.util.UUIDUtil

/** Accomodation for different types of ids */
abstract class Id
case class Uuid(uuid: UUID) extends Id
case class StrId(strId: String) extends Id

object Id {
    def value(id: Id) = id match {
        case Uuid(uuid) => uuid
        case StrId(str) => str
    }
    def fromProto(id: Commands.ID) = id match {
        case i: Commands.ID if i.hasUuid => Uuid(UUIDUtil.fromProto(i.getUuid))
        case i: Commands.ID if i.hasStrId => StrId(i.getStrId)
        case _ => null
    }
    def toProto(id: Id): Commands.ID = {
        val builder = Commands.ID.newBuilder()
        id match {
            case Uuid(uuid) => builder.setUuid(UUIDUtil.toProto(uuid))
            case StrId(str) => builder.setStrId(str)
        }
        builder.build()
    }
}


/** Liaison between an underlying communication channel with a client and the
  * underlying provider of the update stream of topology elements. */
trait Session {
    /** Express interest in an new element of the topology. ACK is not required
      * because the data itself will serve as the ACK. */
    def get[T <: Message](id: Id, ofType: Class[T], nackWith: Response)
    /** Express interest in an new element of the topology. ACK is not required
      * because the data iteslf will serve as the ACK, given that subscriptions
      * guarantee the latest state of the entity will always be streamed as
      * soon as the subscription is made. */
    def watch[T <: Message](id: Id, ofType: Class[T], nackWith: Response)
    /** Express interest in all the entities of the given type
      * The ACK is necessary so that we can inform the client that the
      * full subscription was received */
    def watchAll[T <: Message](ofType: Class[T], ackWith: Response,
                               nackWith: Response)
    /** Cancel interest in an element of the topology. ACK confirms that the
      * unsubscription happened. */
    def unwatch[T <: Message](id: Id, ofType: Class[T], ackWith: Response,
                              nackWith: Response)
    /** Cancel interest in all elements of the given type */
    def unwatchAll[T <: Message](ofType: Class[T], ackWith: Response,
                   nackWith: Response)
    /** The client is no longer interested in subscriptions */
    def terminate(ackWith: Response)
    /** Use this observable to subscribe for responses */
    def observable: Observable[Response]
}

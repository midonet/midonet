/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.cluster.services.state.client

import java.util.UUID

import org.midonet.cluster.rpc.State.ProxyRequest
import org.midonet.cluster.models.Commons

class StateSubscriptionKey(val objectClass: Class[_],
                           val objectId: UUID,
                           val keyClass: Class[_],
                           val valueClass: Class[_],
                           val tableName: String,
                           val tableArguments: List[String],
                           val lastVersion: Option[Long]) {

    private[client] def toSubscribeMessage
        : ProxyRequest.Subscribe = {

        val msg = ProxyRequest.Subscribe.newBuilder()

        tableArguments foreach (arg => msg.addTableArguments(arg))

        if (lastVersion.isDefined) msg.setLastVersion(lastVersion.get)

        msg.setObjectId(Commons.UUID.newBuilder()
                            .setMsb(objectId.getMostSignificantBits)
                            .setLsb(objectId.getLeastSignificantBits))
            .setObjectClass(objectClass.getName)
            .setKeyClass(keyClass.getName)
            .setValueClass(valueClass.getName)
            .setTableName(tableName)
            .build()
    }
}

object StateSubscriptionKey {
    private[client] implicit def toSubscribeMessage(key: StateSubscriptionKey)
        : ProxyRequest.Subscribe = key.toSubscribeMessage
}
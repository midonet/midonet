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

class StateTable(val objectClass: Option[String] = None,
                 val objectId: Option[UUID] = None,
                 val keyClass: Option[String] = None,
                 val valueClass: Option[String] = None,
                 val tableName: Option[String] = None,
                 val tableArguments: List[String] = List.empty[String],
                 val lastVersion: Option[Long] = None)

object StateTable {

    private[client] implicit def toSubscribeMessage(table: StateTable)
        : ProxyRequest.Subscribe = {

        val msg = ProxyRequest.Subscribe.newBuilder()

        table.objectId foreach (value => msg.setObjectId(
                                    Commons.UUID.newBuilder()
                                        .setMsb(value.getMostSignificantBits)
                                        .setLsb(value.getLeastSignificantBits)))

        table.objectClass    foreach (value => msg.setObjectClass(value))
        table.keyClass       foreach (value => msg.setKeyClass(value))
        table.valueClass     foreach (value => msg.setValueClass(value))
        table.tableName      foreach (value => msg.setTableName(value))
        // TODO: addTableArguments when state_api is updated
        table.tableArguments foreach (value => msg.setTableArguments(value))
        table.lastVersion    foreach (value => msg.setLastVersion(value))

        msg.build()
    }
}
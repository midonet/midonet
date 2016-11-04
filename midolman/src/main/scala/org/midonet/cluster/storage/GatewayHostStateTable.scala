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

package org.midonet.cluster.storage

import java.util.UUID

import org.apache.curator.framework.state.ConnectionState

import rx.Observable

import org.midonet.cluster.backend.Directory
import org.midonet.cluster.data.storage.StateTable.Key
import org.midonet.cluster.data.storage.StateTableEncoder.GatewayHostEncoder
import org.midonet.cluster.data.storage.{DirectoryStateTable, ScalableStateTable}
import org.midonet.cluster.data.storage.metrics.StorageMetrics
import org.midonet.cluster.services.state.client.StateTableClient

final class GatewayHostStateTable(override val tableKey: Key,
                                  override val directory: Directory,
                                  override val proxy: StateTableClient,
                                  override val connection: Observable[ConnectionState],
                                  override val metrics: StorageMetrics)
    extends DirectoryStateTable[UUID, AnyRef]
            with ScalableStateTable[UUID, AnyRef]
            with GatewayHostEncoder {

    override val nullValue = null

}
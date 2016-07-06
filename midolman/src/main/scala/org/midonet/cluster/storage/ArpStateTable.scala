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

import org.apache.curator.framework.state.ConnectionState

import rx.Observable

import org.midonet.cluster.backend.Directory
import org.midonet.cluster.data.storage.StateTable.Key
import org.midonet.cluster.data.storage.model.ArpEntry
import org.midonet.cluster.data.storage.{DirectoryStateTable, ScalableStateTable, StateTableEncoder}
import org.midonet.cluster.services.state.client.StateTableClient
import org.midonet.cluster.storage.ArpStateTable.ArpEncoder
import org.midonet.midolman.serialization.SerializationException
import org.midonet.packets.IPv4Addr

object ArpStateTable {

    trait ArpEncoder extends StateTableEncoder[IPv4Addr, ArpEntry] {
        @inline protected override def encodeKey(address: IPv4Addr): String = {
            address.toString
        }

        @inline protected override def decodeKey(string: String): IPv4Addr = {
            IPv4Addr(string)
        }

        @inline protected override def encodeValue(entry: ArpEntry): String = {
            entry.encode
        }

        @throws[SerializationException]
        @inline protected override def decodeValue(string: String): ArpEntry = {
            ArpEntry.decode(string)
        }
    }
    object ArpEncoder extends ArpEncoder

}

final class ArpStateTable(override val tableKey: Key,
                          override val directory: Directory,
                          override val proxy: StateTableClient,
                          override val connection: Observable[ConnectionState])
    extends DirectoryStateTable[IPv4Addr, ArpEntry]
    with ScalableStateTable[IPv4Addr, ArpEntry]
    with ArpEncoder {

    protected override val nullValue = null

}

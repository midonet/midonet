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

import org.apache.commons.lang.SerializationException
import org.apache.curator.framework.state.ConnectionState

import rx.Observable

import org.midonet.cluster.backend.Directory
import org.midonet.cluster.data.storage.StateTable.Key
import org.midonet.cluster.data.storage.{DirectoryStateTable, ScalableStateTable, StateTableEncoder}
import org.midonet.cluster.data.storage.metrics.StorageMetrics
import org.midonet.cluster.rpc.State.KeyValue
import org.midonet.cluster.services.state.client.StateTableClient
import org.midonet.cluster.storage.Fip64StateTable.{Entry, Fip64Encoder}
import org.midonet.packets.{IPv4Addr, IPv6Addr}

object Fip64StateTable {

    object DefaultValue extends AnyRef

    /**
      * An entry in the floating IPv6 table. An entry matches a floating IPv4
      * with a fixed IPv4 for a given tenant router.
      */
    case class Entry(fixedIp: IPv4Addr, floatingIp: IPv6Addr) {
        override def toString = s"Fip64 [id=$fixedIp floatingIp=$floatingIp]"

        def encode: String = s"$fixedIp;$floatingIp"
    }

    object Entry {
        def decode(string: String): Entry = {
            val fields = string.split(";")
            if (fields.length != 2) {
                throw new SerializationException(
                    s"Cannot decode $string as a FIP64 entry")
            }
            Entry(IPv4Addr(fields(0)), IPv6Addr(fields(1)))
        }
    }

    trait Fip64Encoder extends StateTableEncoder[Entry, AnyRef] {
        @inline protected override def encodeKey(entry: Entry): String = {
            entry.encode
        }

        @inline protected override def decodeKey(string: String): Entry = {
            Entry.decode(string)
        }

        @inline protected override def encodeValue(n: AnyRef): String = {
            "0"
        }

        @inline protected override def decodeValue(string: String): AnyRef = {
            DefaultValue
        }

        @inline protected override def decodeKey(kv: KeyValue): Entry = {
            Entry.decode(kv.getDataVariable.toStringUtf8)
        }

        @inline protected override def decodeValue(kv: KeyValue): AnyRef = {
            DefaultValue
        }
    }

}

final class Fip64StateTable(override val tableKey: Key,
                            override val directory: Directory,
                            override val proxy: StateTableClient,
                            override val connection: Observable[ConnectionState],
                            override val metrics: StorageMetrics)
    extends DirectoryStateTable[Entry, AnyRef]
    with ScalableStateTable[Entry, AnyRef]
    with Fip64Encoder {

    override val nullValue = null

}
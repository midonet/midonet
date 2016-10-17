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
import org.midonet.cluster.data.storage.metrics.StorageMetrics
import org.midonet.cluster.data.storage.{DirectoryStateTable, ScalableStateTable, StateTableEncoder}
import org.midonet.cluster.rpc.State.KeyValue
import org.midonet.cluster.services.state.client.StateTableClient
import org.midonet.cluster.storage.Fip64StateTable.Fip64Encoder

object Fip64StateTable {

    object DefaultValue extends AnyRef

    trait Fip64Encoder extends StateTableEncoder[UUID, AnyRef] {
        @inline protected override def encodeKey(id: UUID): String = {
            id.toString
        }

        @inline protected override def decodeKey(string: String): UUID = {
            UUID.fromString(string)
        }

        @inline protected override def encodeValue(n: AnyRef): String = {
            "0"
        }

        @inline protected override def decodeValue(string: String): AnyRef = {
            DefaultValue
        }

        @inline protected override def decodeKey(kv: KeyValue): UUID = {
            val msb = (kv.getDataVariable.byteAt(0).toLong << 56) |
                      ((kv.getDataVariable.byteAt(1).toLong & 0xFF) << 48) |
                      ((kv.getDataVariable.byteAt(2).toLong & 0xFF) << 40) |
                      ((kv.getDataVariable.byteAt(3).toLong & 0xFF) << 32) |
                      ((kv.getDataVariable.byteAt(4).toLong & 0xFF) << 24) |
                      ((kv.getDataVariable.byteAt(5).toLong & 0xFF) << 16) |
                      ((kv.getDataVariable.byteAt(6).toLong & 0xFF) << 8) |
                      (kv.getDataVariable.byteAt(7).toLong & 0xFF)
            val lsb = (kv.getDataVariable.byteAt(8).toLong << 56) |
                      ((kv.getDataVariable.byteAt(9).toLong & 0xFF) << 48) |
                      ((kv.getDataVariable.byteAt(10).toLong & 0xFF) << 40) |
                      ((kv.getDataVariable.byteAt(11).toLong & 0xFF) << 32) |
                      ((kv.getDataVariable.byteAt(12).toLong & 0xFF) << 24) |
                      ((kv.getDataVariable.byteAt(13).toLong & 0xFF) << 16) |
                      ((kv.getDataVariable.byteAt(14).toLong & 0xFF) << 8) |
                      (kv.getDataVariable.byteAt(15).toLong & 0xFF)
            new UUID(msb, lsb)
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
    extends DirectoryStateTable[UUID, AnyRef]
    with ScalableStateTable[UUID, AnyRef]
    with Fip64Encoder {

    override val nullValue = null

}
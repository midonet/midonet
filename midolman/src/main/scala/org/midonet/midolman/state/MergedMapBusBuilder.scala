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

package org.midonet.midolman.state

import scala.collection.concurrent.TrieMap

import kafka.utils.ZkUtils

import MacTableMergedMap.MacMergedMapSerialization
import org.midonet.cluster.data.storage.{InMemoryMergedMapBus, KafkaBus, KafkaSerialization, MergedMapBus}
import org.midonet.cluster.storage.KafkaConfig

object TableType extends Enumeration {
    type TableType = Value
    val MAC, ARP = Value
}

/**
 * This trait allows to build a communication bus used by Merged Maps.
 */
trait MergedMapBusBuilder {
    import TableType._
    
    def newBus[K, V >: Null <: AnyRef](id: String, ownerId: String,
                                       tableType: TableType,
                                       config: KafkaConfig): MergedMapBus[K, V]
}

class KafkaMergedMapBusBuilder extends MergedMapBusBuilder {
    import TableType._
    
    override def newBus[K, V >: Null <: AnyRef](id: String, ownerId: String,
                                                tableType: TableType,
                                                config: KafkaConfig)
    : MergedMapBus[K, V] = tableType match {
        case TableType.MAC =>
            val zkClient = ZkUtils.createZkClient(config.zkHosts,
                                                  config.zkConnectionTimeout,
                                                  config.zkSessionTimeout)
            new KafkaBus[K, V](id, ownerId, config,
                zkClient, new MacMergedMapSerialization()
                                   .asInstanceOf[KafkaSerialization[K, V]])
        case _ => throw new IllegalArgumentException("Merged map of type: " +
                    tableType + " not supported")
    }
}

object InMemoryMergedMapBusBuilder {
    private val buses = new TrieMap[String, InMemoryMergedMapBus[_, _]]
}

class InMemoryMergedMapBusBuilder extends MergedMapBusBuilder {
    import InMemoryMergedMapBusBuilder._
    import TableType._

    override def newBus[K, V >: Null <: AnyRef](id: String, ownerId: String,
                                                tableType: TableType,
                                                config: KafkaConfig)
    : MergedMapBus[K, V] = {
        buses.getOrElseUpdate(id, {
            val bus = new InMemoryMergedMapBus[K, V](id, ownerId)
            buses.putIfAbsent(id, bus).getOrElse(bus)
        }).asInstanceOf[MergedMapBus[K, V]]
    }
}
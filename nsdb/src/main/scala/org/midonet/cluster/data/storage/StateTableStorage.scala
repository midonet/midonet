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

package org.midonet.cluster.data.storage

import java.util.UUID

import scala.collection.concurrent.TrieMap
import scala.reflect.ClassTag

import org.midonet.cluster.data.ObjId
import org.midonet.cluster.models.Topology
import org.midonet.cluster.services.MidonetBackend
import org.midonet.packets.{IPv4Addr, MAC}

/**
  * Specifies the properties of a [[StateTable]], which includes the key and
  * value classes, which normally are hidden by erasure, and the table provider
  * class. The latter is used to create state table instances using Guice.
  */
private[storage] case class TableProvider(key: Class[_], value: Class[_],
                                          clazz: Class[_ <: StateTable[_,_]])

/**
  * Stores the registered tables for a given object class.
  */
private[storage] class TableInfo {
    val tables = new TrieMap[String, TableProvider]
}

/**
  * A trait that complements the [[Storage]] trait with support for high
  * performance state tables.
  */
trait StateTableStorage {

    protected[this] val tableInfo = new TrieMap[Class[_], TableInfo]

    /**
      * Registers a new state table for the given class. The state table is
      * identified by the given key class, value class and name, and it
      * associated with the specified provider class. The method throws an
      * [[IllegalStateException]] if the storage was already built, and an
      * [[IllegalArgumentException]] if the specified object class was not
      * previously registered, or if a state table with same parameters was
      * already registered.
      */
    @throws[IllegalStateException]
    @throws[IllegalArgumentException]
    def registerTable[K, V](clazz: Class[_], key: Class[K], value: Class[V],
                            name: String,
                            provider: Class[_ <: StateTable[K,V]]): Unit

    /**
      * Returns a [[StateTable]] instance for the specified object class,
      * table name, object identifier and optional table arguments.
      */
    @throws[ServiceUnavailableException]
    @throws[IllegalArgumentException]
    def getTable[K, V](clazz: Class[_], id: ObjId, name: String, args: Any*)
                      (implicit key: ClassTag[K], value: ClassTag[V]): StateTable[K, V]

    @throws[IllegalArgumentException]
    def tablePath(clazz: Class[_], id: ObjId, name: String, args: Any*): String

    def bridgeArpTable(id: UUID) = getTable[IPv4Addr, MAC](
            classOf[Topology.Network], id, MidonetBackend.Ip4MacTable)

    def routerPortPeeringTable(id: UUID) = getTable[IPv4Addr, MAC](
            classOf[Topology.Port], id, MidonetBackend.PeeringTable)

    def bridgeArpTablePath(id: UUID) = tablePath(
            classOf[Topology.Network], id, MidonetBackend.Ip4MacTable)

    def routerPortPeeringTablePath(id: UUID) = tablePath(
            classOf[Topology.Port], id, MidonetBackend.PeeringTable)
}

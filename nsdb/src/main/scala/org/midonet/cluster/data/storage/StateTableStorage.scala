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

import scala.collection.mutable
import scala.concurrent.Future
import scala.reflect.ClassTag

import org.midonet.cluster.data.ObjId
import org.midonet.cluster.data.storage.StateTableEncoder.{Fip64Encoder, Ip4ToMacEncoder, MacToIdEncoder, MacToIp4Encoder}
import org.midonet.cluster.data.storage.StateTableStorage.{ObjectTablesMap, TableInfo, TableProvider, TablesMap}
import org.midonet.cluster.data.storage.model.{ArpEntry, Fip64Entry}
import org.midonet.cluster.models.{Neutron, Topology}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.packets.{IPv4Addr, MAC}


object StateTableStorage {

    type TablesMap = Map[String, TableProvider]

    type ObjectTablesMap = Map[Class[_], TablesMap]

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
    private[storage] class TableInfo extends mutable.HashMap[String, TableProvider]

}

/**
  * A trait that complements the [[Storage]] trait with support for high
  * performance state tables.
  */
trait StateTableStorage extends Storage {

    private val mutex = new Object

    private val globalTableProviders = new mutable.HashMap[String, TableProvider]
    private val objectTableProviders = new mutable.HashMap[Class[_], TableInfo]

    @volatile private var currentGlobalTables: TablesMap = Map.empty
    @volatile private var currentObjectTables: ObjectTablesMap = Map.empty

    /**
      * Registers a global state table. The state table is identified by the
      * given key class, value class and name, and it associated with the
      * specified provider class. The method throws an [[IllegalStateException]]
      * if the storage was already built, and an [[IllegalArgumentException]]
      * if the specified object class was not previously registered, or if a
      * state table with same parameters was already registered.
      */
    @throws[IllegalStateException]
    final def registerTable[K, V](key: Class[K], value: Class[V], name: String,
                            provider: Class[_ <: StateTable[K,V]]): Unit = {
        if (!registerTable(globalTableProviders, key, value, name, provider)) {
            throw new IllegalArgumentException(
                s"Global table for key ${key.getSimpleName} " +
                s"value ${value.getSimpleName} name $name is already " +
                "registered to a different provider")
        }
    }

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
    def registerTable[K, V](clazz: Class[_], key: Class[K],
                            value: Class[V], name: String,
                            provider: Class[_ <: StateTable[K,V]]): Unit = {
        assertRegistered(clazz)
        if (!registerTable(objectTableProviders(clazz), key, value, name, provider)) {
            throw new IllegalArgumentException(
                s"Table for class ${clazz.getSimpleName} key ${key.getSimpleName} " +
                s"value ${value.getSimpleName} name $name is already " +
                "registered to a different provider")
        }
    }

    /**
      * Returns a [[StateTable]] instance for the specified object class,
      * table name, object identifier and optional table arguments.
      */
    @throws[ServiceUnavailableException]
    @throws[IllegalArgumentException]
    def getTable[K, V](clazz: Class[_], id: ObjId, name: String, args: Any*)
                      (implicit key: ClassTag[K], value: ClassTag[V]): StateTable[K, V]

    /**
      * Returns a [[StateTable]] instance for the specified global table name
      */
    @throws[ServiceUnavailableException]
    def getTable[K, V](name: String)
                      (implicit key: ClassTag[K], value: ClassTag[V]): StateTable[K, V]

    /**
      * Returns the storage path for the specified state table. The method
      * is compatible with the legacy paths, and will block while reading from
      * storage whether the legacy path exists.
      */
    @throws[IllegalArgumentException]
    def tablePath(clazz: Class[_], id: ObjId, name: String, args: Any*): String

    /**
      * Returns the storage path for the specified state table. The method
      * is compatible with the legacy paths, and will block while reading from
      * storage whether the legacy path exists.
      */
    @throws[IllegalArgumentException]
    def tablePath(name: String): String

    /**
      * Returns the list of arguments for the specified state table and prefixed
      * by the given arguments.
      */
    @throws[IllegalArgumentException]
    def tableArguments(clazz: Class[_], id: ObjId, name: String, args: Any*)
    : Future[Set[String]]

    def bridgeMacTable(id: UUID, vlanId: Short) = getTable[MAC, UUID](
            classOf[Topology.Network], id, MidonetBackend.MacTable, vlanId)

    def bridgeArpTable(id: UUID) = getTable[IPv4Addr, MAC](
            classOf[Topology.Network], id, MidonetBackend.Ip4MacTable)

    def routerArpTable(id: UUID) = getTable[IPv4Addr, ArpEntry](
            classOf[Topology.Router], id, MidonetBackend.ArpTable)

    def portPeeringTable(id: UUID) = getTable[MAC, IPv4Addr](
            classOf[Topology.Port], id, MidonetBackend.PeeringTable)

    def bridgeMacTablePath(id: UUID, vlanId: Short) =
        tablePath(classOf[Topology.Network], id, MidonetBackend.MacTable, vlanId)

    def bridgeArpTablePath(id: UUID) =
        tablePath(classOf[Topology.Network], id, MidonetBackend.Ip4MacTable)

    def portPeeringTablePath(id: UUID) =
        tablePath(classOf[Topology.Port], id, MidonetBackend.PeeringTable)

    def fip64TablePath(id: UUID) =
        tablePath(classOf[Neutron.NeutronNetwork], id, MidonetBackend.Fip64Table)

    def bridgeMacEntryPath(bridgeId: UUID, vlanId: Short, mac: MAC,
                           portId: UUID): String = {
        bridgeMacTablePath(bridgeId, vlanId) +
            MacToIdEncoder.encodePersistentPath(mac, portId)
    }

    def bridgeArpEntryPath(bridgeId: UUID, address: IPv4Addr, mac: MAC): String = {
        bridgeArpTablePath(bridgeId) +
            Ip4ToMacEncoder.encodePersistentPath(address, mac)
    }

    def portPeeringEntryPath(portId: UUID, mac: MAC, address: IPv4Addr): String = {
        portPeeringTablePath(portId) +
            MacToIp4Encoder.encodePersistentPath(mac, address)
    }

    def fip64EntryPath(networkId: UUID, entry: Fip64Entry): String = {
        fip64TablePath(networkId) +
            Fip64Encoder.encodePersistentPath(entry, null)
    }

    private def registerTable[K,V](map: mutable.Map[String, TableProvider],
                                   key: Class[K], value: Class[V], name: String,
                                   provider: Class[_ <: StateTable[K,V]])
    : Boolean = mutex.synchronized {
        if (isBuilt) {
            throw new IllegalStateException(
                "Cannot register a state table after building the storage")
        }
        if (!map.contains(name)) {
            map.put(name, TableProvider(key, value, provider))
            true
        } else {
            false
        }
    }

    /**
      * @return An immutable copy of the current global tables.
      */
    protected def globalTables: TablesMap = currentGlobalTables

    /**
      * @return An immutable copy of the current object tables.
      */
    protected def objectTables: ObjectTablesMap = currentObjectTables

    /**
      * @see [[Storage.onRegisterClass()]]
      */
    protected abstract override def onRegisterClass(clazz: Class[_])
    : Unit = {
        mutex.synchronized {
            objectTableProviders.put(clazz, new TableInfo)
        }
        super.onRegisterClass(clazz)
    }

    /**
      * @see [[Storage.onBuild()]]
      */
    protected abstract override def onBuild(): Unit = {
        mutex.synchronized {
            currentGlobalTables = globalTableProviders.toMap
            currentObjectTables = objectTableProviders.mapValues(_.toMap).toMap
        }
        super.onBuild()
    }

    /** Gets the table provider for the given object, key and value classes. */
    @throws[IllegalArgumentException]
    protected def getProvider(clazz: Class[_], key: Class[_], value: Class[_],
                              name: String): TableProvider = {
        val provider = objectTables
            .getOrElse(clazz, throw new IllegalArgumentException(
                s"Class ${clazz.getSimpleName} is not registered"))
            .getOrElse(name, throw new IllegalArgumentException(
                s"Table $name is not registered for class ${clazz.getSimpleName}"))

        if (provider.key != key) {
            throw new IllegalArgumentException(
                s"Table $name for class ${clazz.getSimpleName} has different " +
                s"key class ${provider.key.getSimpleName}")
        }
        if (provider.value != value) {
            throw new IllegalArgumentException(
                s"Table $name for class ${clazz.getSimpleName} has different " +
                s"value class ${provider.value.getSimpleName}")
        }
        provider
    }

    /** Gets the table provider for the given global table,
      * key and value classes. */
    @throws[IllegalArgumentException]
    protected def getProvider(key: Class[_], value: Class[_],
                            name: String): TableProvider = {
        val provider = globalTables
            .getOrElse(name, throw new IllegalArgumentException(
                s"Global table $name is not registered"))

        if (provider.key != key) {
            throw new IllegalArgumentException(
                s"Global table $name has different " +
                s"key class ${provider.key.getSimpleName} rather than " +
                s"expected ${key.getSimpleName}")
        }
        if (provider.value != value) {
            throw new IllegalArgumentException(
                s"Global table $name has different " +
                s"value class ${provider.value.getSimpleName} rather than " +
                s"expected ${value.getSimpleName}")
        }
        provider
    }

}

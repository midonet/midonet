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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{ConcurrentHashMap, ExecutorService}

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.reflect.ClassTag
import scala.util.control.NonFatal

import com.google.inject.{AbstractModule, Guice}

import org.apache.commons.lang3.StringUtils
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.utils.ZKPaths
import org.apache.zookeeper.KeeperException
import org.apache.zookeeper.KeeperException.NoNodeException
import org.apache.zookeeper.ZooDefs.Ids

import org.midonet.cluster.backend.Directory
import org.midonet.cluster.backend.zookeeper.{ZkConnection, ZkConnectionAwareWatcher, ZkDirectory}
import org.midonet.cluster.data._
import org.midonet.cluster.data.storage.TransactionManager._
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.util.eventloop.Reactor
import org.midonet.util.functors.makeRunnable

trait ZookeeperStateTable extends StateTableStorage with Storage {

    protected[storage] trait StateTableTransactionManager {

        protected val pathsToDelete = new ConcurrentHashMap[String, Void]

        protected def executorService: ExecutorService

        protected def version: Long

        protected def ops: mutable.LinkedHashMap[Key, TxOp]

        /**
          * Returns the operations needed to create the state tables paths for
          * all new objects that have at least one table. These are added to
          * the same transaction that creates a new object.
          */
        protected def createStateTableOps: Seq[(Key, TxOp)] = {
            val list = new ListBuffer[(Key, TxOp)]
            for ((Key(clazz, id), txOp) <- ops) txOp match {
                case TxCreate(_) if tableInfo(clazz).tables.nonEmpty =>
                    list += Key(null, tablesObjectPath(clazz, id, version)) ->
                            TxCreateNode()
                    for ((name, provider) <- tableInfo(clazz).tables) {
                        list += Key(null, tableRootPath(clazz, id, name, version)) ->
                                TxCreateNode()
                    }
                case _ =>
            }
            list
        }

        /**
          * Deletes asynchronously the state table paths for all deleted objects.
          * If the deletion of a path fails, it is added to the `pathsToDelete`
          * set, such that it can be deleted later.
          */
        protected def deleteStateTables(): Unit = {
            executorService.submit(makeRunnable {
                for ((Key(clazz, id), txOp) <- ops) txOp match {
                    case TxDelete(_) =>
                        val path = tablesObjectPath(clazz, id, version)
                        try {
                            ZKPaths.deleteChildren(
                                curator.getZookeeperClient.getZooKeeper, path,
                                true)
                        } catch {
                            case e: NoNodeException => // Path already deleted
                            case e: KeeperException =>
                                pathsToDelete.putIfAbsent(path, null)
                            case NonFatal(e) => // Ignore any other exception
                        }
                    case _ =>
                }
            })
        }

    }

    private val providers = new TrieMap[Key, Class[_ <: StateTable[_,_]]]

    protected def rootPath: String

    protected def curator: CuratorFramework

    protected def version: AtomicLong

    protected def reactor: Reactor

    protected def connection: ZkConnection

    protected def connectionWatcher: ZkConnectionAwareWatcher

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
    override def registerTable[K, V](clazz: Class[_], key: Class[K],
                                     value: Class[V], name: String,
                                     provider: Class[_ <: StateTable[K,V]])
    : Unit = {
        if (isBuilt) {
            throw new IllegalStateException(
                "Cannot register a state table after building the storage")
        }
        if (!isRegistered(clazz)) {
            throw new IllegalArgumentException(
                s"Class ${clazz.getSimpleName} is not registered")
        }
        val newProvider = TableProvider(key, value, provider)
        val oldProvider = tableInfo(clazz).tables.getOrElse(name, {
            tableInfo(clazz).tables.putIfAbsent(name, newProvider)
                                   .getOrElse(newProvider)
        })
        if (newProvider != oldProvider) {
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
    @throws[NoSuchMethodException]
    @throws[SecurityException]
    def getTable[K, V](clazz: Class[_], id: ObjId, name: String, args: Any*)
                      (implicit key: ClassTag[K], value: ClassTag[V])
    : StateTable[K, V] = {
        assertBuilt()
        val path = if (args.nonEmpty) {
            val p = tablePath(clazz, id, name, version.longValue(), args: _*)
            ZKPaths.mkdirs(curator.getZookeeperClient.getZooKeeper, p, true)
            p
        } else {
            tableRootPath(clazz, id, name)
        }

        // Get the provider for the state table name.
        val provider = getProvider(clazz, key.runtimeClass, value.runtimeClass,
                                   name)
        // Create a new directory for the state table path.
        val directory = new ZkDirectory(connection, path, Ids.OPEN_ACL_UNSAFE,
                                        reactor)

        // Get the constructor for the provider class and create a new instance.
        val constructor =
            provider.clazz.getConstructor(classOf[Directory],
                                          classOf[ZkConnectionAwareWatcher])
        constructor.newInstance(directory, connectionWatcher)
                   .asInstanceOf[StateTable[K, V]]
    }

    override def tablePath(clazz: Class[_], id: ObjId, name: String,
                           args: Any*): String = {
        tablePath(clazz, id, name, version.longValue(), args)
    }


    @inline
    private[storage] def tablesPath(version: Long = version.longValue())
    : String = {
        s"$rootPath/$version/tables"
    }

    @inline
    private[storage] def tablesClassPath(clazz: Class[_],
                                         version: Long = version.longValue())
    : String = {
        tablesPath(version) + "/" + clazz.getSimpleName
    }

    @inline
    private[storage] def tablesObjectPath(clazz: Class[_], id: ObjId,
                                          version: Long = version.longValue())
    : String = {
        tablesClassPath(clazz, version) + "/" + getIdString(clazz, id)
    }

    @inline
    private[storage] def tableRootPath(clazz: Class[_], id: ObjId, name: String,
                                       version: Long = version.longValue())
    : String = {
        tablesObjectPath(clazz, id, version) + "/" + name
    }

    @inline
    private[storage] def tablePath(clazz: Class[_], id: ObjId, name: String,
                                   version: Long, args: Any*): String = {
        tableRootPath(clazz, id, name, version) + "/" +
        StringUtils.join(args.asJava, '/')
    }
    /** Gets the table provider for the given object, key and value classes. */
    @throws[IllegalArgumentException]
    private def getProvider(clazz: Class[_], key: Class[_], value: Class[_],
                            name: String): TableProvider = {
        val provider = tableInfo.getOrElse(clazz, throw new IllegalArgumentException(
            s"Class ${clazz.getSimpleName} is not registered")).tables
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

}

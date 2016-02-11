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

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{ConcurrentHashMap, ExecutorService}

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.reflect.ClassTag
import scala.util.control.NonFatal

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.utils.ZKPaths
import org.apache.zookeeper.KeeperException
import org.apache.zookeeper.KeeperException.NoNodeException
import org.apache.zookeeper.ZooDefs.Ids

import org.midonet.cluster.backend.Directory
import org.midonet.cluster.backend.zookeeper.{ZkConnection, ZkConnectionAwareWatcher, ZkDirectory}
import org.midonet.cluster.data._
import org.midonet.cluster.data.storage.TransactionManager._
import org.midonet.util.collection.PathMap
import org.midonet.util.eventloop.Reactor
import org.midonet.util.functors.makeRunnable

trait StateTablePaths extends StateTableStorage {
    protected def version: AtomicLong
    protected def rootPath: String

    override def tablePath(clazz: Class[_], id: ObjId, name: String,
                           args: Any*): String = {
        tablePath(clazz, id, name, version.longValue(), args:_*)
    }

    @inline
    private[cluster] def tablesPath(version: Long = version.longValue()): String = {
        s"$rootPath/$version/tables"
    }

    @inline
    private[cluster] def tablesClassPath(clazz: Class[_],
                                         version: Long = version.longValue()): String = {
        tablesPath(version) + "/" + clazz.getSimpleName
    }

    @inline
    private[cluster] def tablesObjectPath(clazz: Class[_], id: ObjId,
                                          version: Long = version.longValue()): String = {
        tablesClassPath(clazz, version) + "/" + getIdString(clazz, id)
    }

    @inline
    private[cluster] def tableRootPath(clazz: Class[_], id: ObjId, name: String,
                                       version: Long = version.longValue()): String = {
        tablesObjectPath(clazz, id, version) + "/" + name
    }

    @inline
    private[cluster] def tablePath(clazz: Class[_], id: ObjId, name: String,
                                   version: Long, args: Any*): String = {
        val rootPath = tableRootPath(clazz, id, name, version)
        args.foldLeft(new StringBuilder(rootPath)) {
            (sb, s) => sb.append('/').append(s)
        }.toString()
    }
}

trait ZookeeperStateTable extends StateTableStorage with StateTablePaths with Storage {

    protected[storage] trait StateTableTransactionManager {

        protected val pathsToDelete = new ConcurrentHashMap[String, Void]

        protected def executorService: ExecutorService

        protected def version: Long

        protected def ops: mutable.LinkedHashMap[Key, TxOp]

        protected def nodeOps: PathMap[TxNodeOp]

        /**
          * Returns the operations needed to create the state tables paths for
          * all new objects that have at least one table. These are added to
          * the same transaction that creates a new object.
          */
        protected def createStateTableOps: Seq[(Key, TxOp)] = {
            def nodeExists(path: String) = nodeOps.get(path) match {
                case Some(TxCreateNode(_)) => true
                case Some(TxUpdateNode(_)) => true
                case Some(TxNodeExists) => true
                case _ => false
            }

            val list = new ListBuffer[(Key, TxOp)]
            for ((Key(clazz, id), txOp) <- ops) txOp match {
                case TxCreate(_) if tableInfo(clazz).tables.nonEmpty =>
                    val objPath = tablesObjectPath(clazz, id, version)
                    if (!nodeExists(objPath)) {
                        list += Key(null, objPath) -> TxCreateNode()
                    }

                    for ((name, provider) <- tableInfo(clazz).tables) {
                        val tablePath = tableRootPath(clazz, id, name, version)
                        if (!nodeExists(tablePath)) {
                            list += Key(null, tablePath) -> TxCreateNode()
                        }
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

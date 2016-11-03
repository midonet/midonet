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
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.{Future, Promise}
import scala.reflect.ClassTag
import scala.util.control.NonFatal

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.api.{BackgroundCallback, CuratorEvent}
import org.apache.curator.framework.state.ConnectionState
import org.apache.curator.utils.ZKPaths
import org.apache.zookeeper.KeeperException
import org.apache.zookeeper.KeeperException.{Code, NoNodeException}

import rx.Observable

import org.midonet.cluster.backend.Directory
import org.midonet.cluster.backend.zookeeper.ZkDirectory
import org.midonet.cluster.data._
import org.midonet.cluster.data.storage.TransactionManager._
import org.midonet.cluster.data.storage.metrics.StorageMetrics
import org.midonet.cluster.models.Commons
import org.midonet.cluster.services.state.client.StateTableClient
import org.midonet.cluster.services.state.client.StateTableClient.DisabledStateTableClient
import org.midonet.cluster.util.ConnectionObservable
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.util.collection.PathMap
import org.midonet.util.eventloop.Reactor
import org.midonet.util.functors.makeRunnable

trait StateTablePaths extends StateTableStorage with LegacyStateTableStorage {

    protected def version: AtomicLong
    protected def rootPath: String
    protected def zoomPath: String
    protected def pathExists(path: String): Boolean

    override def tablePath(clazz: Class[_], id: ObjId, name: String,
                           args: Any*): String = {
        legacyTablePath(clazz, id, name, args) match {
            case Some(path) if pathExists(path) => path
            case _ => tablePath(clazz, id, name, version.longValue(), args:_*)
        }
    }

    @inline
    private[cluster] def tablesPath(version: Long = version.longValue()): String = {
        s"$zoomPath/$version/tables"
    }

    @inline
    private[cluster] def tablesGlobalPath(version: Long = version.longValue())
    : String = {
        s"$zoomPath/$version/tables/global"
    }

    @inline
    private[cluster] def tablesClassPath(clazz: Class[_],
                                         version: Long = version.longValue()): String = {
        tablesPath(version) + "/" + clazz.getSimpleName
    }

    @inline
    private[cluster] def tablesObjectPath(clazz: Class[_], id: ObjId,
                                          version: Long = version.longValue()): String = {
        tablesClassPath(clazz, version) + "/" + getIdString(id)
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
        if (args.isEmpty) {
            rootPath
        } else {
            args.foldLeft(new StringBuilder(rootPath)) {
                (sb, s) => sb.append('/').append(s)
            }.toString()
        }
    }

    @inline
    override def tablePath(name: String): String =
        tablePath(name, version.longValue())

    @inline
    private[cluster] def tablePath(name: String,
                                   version: Long)
    : String = {
        tablesGlobalPath(version) + "/" + name
    }
}

trait ZookeeperStateTable extends StateTableStorage with StateTablePaths with Storage {

    protected val metrics: StorageMetrics

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
                        val hasLegacyPath =
                            legacyTablePath(clazz, id, name).exists(nodeExists)
                        val tablePath = tableRootPath(clazz, id, name, version)
                        if (!hasLegacyPath && !nodeExists(tablePath)) {
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

    protected def zoomPath: String

    protected def curator: CuratorFramework

    protected def stateTables: StateTableClient

    protected def version: AtomicLong

    protected def reactor: Reactor

    private val connection = ConnectionObservable.create(curator)

    /**
      * @see [[StateTableStorage.getTable()]]
      */
    @throws[ServiceUnavailableException]
    @throws[IllegalArgumentException]
    @throws[NoSuchMethodException]
    @throws[SecurityException]
    def getTable[K, V](clazz: Class[_], id: ObjId, name: String, args: Any*)
                      (implicit key: ClassTag[K], value: ClassTag[V])
    : StateTable[K, V] = {
        assertBuilt()

        val objectId = id match {
            case uuid: UUID => uuid
            case uuid: Commons.UUID => uuid.asJava
            case string: String => UUID.fromString(string)
            case _ =>
                throw new IllegalArgumentException(
                    s"Table object identifier $id not supported")
        }

        val path = tablePath(clazz, id, name, args: _*)
        if (args.nonEmpty) {
            ZKPaths.mkdirs(curator.getZookeeperClient.getZooKeeper, path, true)
        }

        // Get the provider for the state table name.
        val provider = getProvider(clazz, key.runtimeClass, value.runtimeClass,
                                   name)
        // Create a new directory for the state table path.
        val directory = new ZkDirectory(curator.getZookeeperClient.getZooKeeper,
                                        path, reactor)

        // Get the constructor for the provider class and create a new instance.
        val constructor =
            provider.clazz.getConstructor(classOf[StateTable.Key],
                                          classOf[Directory],
                                          classOf[StateTableClient],
                                          classOf[Observable[ConnectionState]],
                                          classOf[StorageMetrics])

        val tableKey = StateTable.Key(clazz, objectId, key.runtimeClass,
                                      value.runtimeClass, name, args)

        constructor.newInstance(tableKey, directory, stateTables, connection, metrics)
                   .asInstanceOf[StateTable[K, V]]
    }

    /**
      * @see [[StateTableStorage.getTable()]]
      */
    override def getTable[K, V](name: String)
                               (implicit key: ClassTag[K], value: ClassTag[V])
    : StateTable[K, V] = {
        assertBuilt()

        val path = tablePath(name, version.longValue)
        // Get the provider for the state table name.
        val provider = getProvider(key.runtimeClass, value.runtimeClass,
                                   name)
        // Create a new directory for the state table path.
        val directory = new ZkDirectory(curator.getZookeeperClient.getZooKeeper,
                                        path, reactor)

        // Get the constructor for the provider class and create a new instance.
        val constructor =
            provider.clazz.getConstructor(classOf[StateTable.Key],
                                          classOf[Directory],
                                          classOf[StateTableClient],
                                          classOf[Observable[ConnectionState]],
                                          classOf[StorageMetrics])

        val tableKey = StateTable.Key(null, null, key.runtimeClass,
                                      value.runtimeClass, name, Seq())

        constructor.newInstance(tableKey, directory, DisabledStateTableClient,
                                connection, metrics)
                   .asInstanceOf[StateTable[K, V]]
    }

    /**
      * @see [[StateTableStorage.tableArguments()]]
      */
    @throws[ServiceUnavailableException]
    @throws[IllegalArgumentException]
    def tableArguments(clazz: Class[_], id: ObjId, name: String, args: Any*)
    : Future[Set[String]] = {
        assertBuilt()
        val promise = Promise[Set[String]]()

        curator.getChildren.inBackground(new BackgroundCallback {
            override def processResult(client: CuratorFramework,
                                       event: CuratorEvent): Unit = {
                if (event.getResultCode == Code.OK.intValue()) {
                    promise trySuccess Set(event.getChildren.asScala: _*)
                } else if (event.getResultCode == Code.NONODE.intValue()) {
                    promise trySuccess Set.empty
                } else {
                    promise tryFailure KeeperException.create(
                        Code.get(event.getResultCode))
                }
            }
        }).forPath(tablePath(clazz, id, name, args: _*))

        promise.future
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

    /** Gets the table provider for the given global table,
      * key and value classes. */
    @throws[IllegalArgumentException]
    private def getProvider(key: Class[_], value: Class[_],
                            name: String): TableProvider = {
        val provider = tables.getOrElse(name, throw new IllegalArgumentException(
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

    /** Returns `true` if the specified path exists.
      */
    protected override def pathExists(path: String): Boolean = {
        curator.checkExists().forPath(path) ne null
    }

}

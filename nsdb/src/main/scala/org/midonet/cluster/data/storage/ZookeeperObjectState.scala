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

import java.util.concurrent.{ExecutorService, ConcurrentHashMap}
import java.util.concurrent.atomic.AtomicLong

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.{Promise, Future}
import scala.util.control.NonFatal

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.api.{BackgroundCallback, CuratorEvent}
import org.apache.curator.utils.ZKPaths
import org.apache.zookeeper.{KeeperException, CreateMode}
import org.apache.zookeeper.KeeperException.{NoNodeException, Code}

import rx.Observable

import org.midonet.cluster.data._
import org.midonet.cluster.data.storage.TransactionManager._
import org.midonet.cluster.data.storage.WritePolicy.WritePolicy
import org.midonet.cluster.data.storage.StateStorage.{NoOwnerId, StringEncoding}
import org.midonet.cluster.data.storage.ZookeeperObjectState.makeThrowable
import org.midonet.util.functors._

object ZookeeperObjectState {

    /** Creates the appropriate exception for the given result code. */
    private def makeThrowable(clazz: String, id: String, key: String,
                              value: String, result: Int)
    : Throwable = {
        if (result == Code.BADVERSION.intValue()) {
            new ConcurrentStateModificationException(clazz, id, key, value,
                                                     result)
        } else {
            new UnmodifiableStateException(clazz, id, key, value, result)
        }
    }

}

/**
 * Provides an implementation of the [[StateStorage]] using ZooKeeper as
 * backend database. This trait can be extended by a class implementing the
 * [[Storage]] trait (e.g. [[ZookeeperObjectMapper]]), which must implement the
 * trait's abstract methods below specifying the root path, Apache Curator
 * instance and version.
 *
 * The state for each object is stored in a ZooKeeper path as:
 *
 *  [root]/[class]/[object-id]/[key]/[value]
 *
 * The trait will add/remove the ZooKeeper nodes corresponding to the state
 * values. Therefore, a class extending this trait must ensure that upon adding
 * a new object, the following state paths are created:
 * - for single-value keys, the nodes up to the object identifier
 * - for multi-value keys, the nodes up to the key
 * This must be done for every new object added to the storage. It is also the
 * responsibility of the extending class to remove the state paths when an
 * object is deleted.
 *
 * This trait will store the state value in (i) the key nodes for single-value
 * keys, and (ii) as children of the key nodes for multi-value keys.
 */
trait ZookeeperObjectState extends StateStorage with Storage {

    protected[storage] trait StateTransactionManager {

        protected val pathsToDelete = new ConcurrentHashMap[String, Void]

        protected def executorService: ExecutorService

        protected def version: Long

        protected def ops: mutable.LinkedHashMap[Key, TxOp]

        /** Returns the operations needed to create the state paths for all
          * new objects. */
        protected def createStateOps(): Seq[(Key, TxOp)] = {
            val list = new ListBuffer[(Key, TxOp)]
            for ((Key(clazz, id), txOp) <- ops) txOp match {
                case TxCreate(_,_) =>
                    list += Key(null, getStateObjectPath(clazz, id, version)) ->
                            TxCreateNode()
                    for ((key, wp) <- stateInfo(clazz).keys if !wp.isSingle) {
                        list += Key(null, getKeyPath(clazz, id, key, version)) ->
                                TxCreateNode()
                    }
                case _ =>
            }
            list
        }

        /** Deletes asynchronously the state paths for all deleted objects.
          * If the deletion of a path fails, it is added to the `pathsToDelete`
          * set, such that it can be deleted later. */
        protected def deleteState(): Unit = {
            executorService.submit(makeRunnable {
                for ((Key(clazz, id), txOp) <- ops) txOp match {
                    case TxDelete(_,_) =>
                        val path = getStateObjectPath(clazz, id, version)
                        try {
                            ZKPaths.deleteChildren(
                                curator.getZookeeperClient.getZooKeeper, path,
                                true)
                        } catch {
                            case _: NoNodeException => // Path already deleted
                            case _: KeeperException =>
                                pathsToDelete.putIfAbsent(path, null)
                            case NonFatal(e) => // Ignore any other exception
                        }
                    case _ =>
                }
            })
        }

    }

    private def statePath(version: Long) = s"$rootPath/$version/state"

    protected def rootPath: String

    protected def curator: CuratorFramework

    protected def version: AtomicLong

    @throws[IllegalStateException]
    @throws[IllegalArgumentException]
    override def registerKey(clazz: Class[_], key: String,
                             writePolicy: WritePolicy): Unit = {
        if (isBuilt) {
            throw new IllegalStateException("Cannot register a key after the " +
                                            "building the storage")
        }

        if (!isRegistered(clazz)) {
            throw new IllegalArgumentException(s"Class ${clazz.getSimpleName}" +
                                               s" is not registered")
        }

        stateInfo.getOrElseUpdate(clazz, new StateInfo).keys +=
            key -> writePolicy
    }

    /**
     * Adding a value works as follows:
     * - With [[WritePolicy.SingleFirstWriteWins]] policy, the value is added
     *   as data to the key ZooKeeper node. The add should succeed if the value
     *   does not exist, or otherwise if the caller is the current value owner.
     *   If a value already exists and the caller is not the owner, the future
     *   will fail with a [[NotStateOwnerException]].
     * - With [[WritePolicy.SingleLastWriteWins]] policy, the value is added as
     *   data to the key ZooKeeper node. The add should always succeed. If the
     *   caller is the current owner of the value, then the method updates the
     *   key node data. Otherwise, it deletes the current key node and creates a
     *   new ephemeral in its place containing the value.
     * - With [[WritePolicy.Multiple]] policy, the value is added as a child
     *   node to the key ZooKeeper node. The add should always succeed. If the
     *   caller is the current owner of the value, there is no additional change
     *   in ZooKeeper and the future completes immediately. Otherwise, it
     *   deletes the current value node and creates a new ephemeral in its place
     *   corresponding to the new client owner.
     */
    @throws[ServiceUnavailableException]
    @throws[IllegalArgumentException]
    override def addValue(clazz: Class[_], id: ObjId, key: String,
                          value: String): Future[StateResult] = {
        assertBuilt()

        val policy = getWritePolicy(clazz, key)

        if (policy.isSingle) {
            if (policy.firstWins) {
                addSingleValueFirst(clazz, id, key, value)
            } else {
                addSingleValueLast(clazz, id, key, value)
            }
        } else {
            addMultiValue(clazz, id, key, value)
        }
    }

    /**
     * To remove a value, the caller must be the owner. The deletion is
     * idempotent, i.e. if the value does not exist the operation completes
     * successfully and the state result will have the ownership identifier
     * set to zero.
     * - With [[WritePolicy.SingleFirstWriteWins]] or
     *   [[WritePolicy.SingleLastWriteWins]] the method deletes the key
     *   ZooKeeper node.
     * - With [[WritePolicy.Multiple]] the method deletes the value ZooKeeper
     *   node.
     */
    @throws[ServiceUnavailableException]
    @throws[IllegalArgumentException]
    override def removeValue(clazz: Class[_], id: ObjId, key: String,
                             value: String): Future[StateResult] = {
        assertBuilt()

        if (getWritePolicy(clazz, key).isSingle) {
            removeValue(clazz, id, key, value, getKeyPath(clazz, id, key))
        } else {
            removeValue(clazz, id, key, value,
                        getValuePath(clazz, id, key, value))
        }
    }

    /**
     * Returns the value or values associated to a given key. The method returns
     * a future with either a [[SingleValueKey]] or [[MultiValueKey]], depending
     * on the write policy. If there are no values for the key, the returned
     * [[StateKey]] will contain an empty [[Option]] or an empty [[Set]].
     */
    @throws[ServiceUnavailableException]
    @throws[IllegalArgumentException]
    override def getKey(clazz: Class[_], id: ObjId, key: String)
    : Future[StateKey] = {
        assertBuilt()

        val promise = Promise[StateKey]()
        if (getWritePolicy(clazz, key).isSingle) {
            curator.getData.inBackground(makeBackground(event => {
                if (event.getResultCode == Code.OK.intValue()) {
                    val value = new String(event.getData, StringEncoding)
                    val ownerId = event.getStat.getEphemeralOwner
                    promise.success(SingleValueKey(key, Some(value), ownerId))
                } else if (event.getResultCode == Code.NONODE.intValue()) {
                    promise.success(SingleValueKey(key, None, NoOwnerId))
                } else {
                    promise.failure(makeThrowable(
                        clazz.getSimpleName, getIdString(clazz, id),
                        key, null, event.getResultCode))
                }
            })).forPath(getKeyPath(clazz, id, key))
        } else {
            curator.getChildren.inBackground(makeBackground(event => {
                if (event.getResultCode == Code.OK.intValue()) {
                    val values = event.getChildren.asScala.toSet
                    promise.success(MultiValueKey(key, values))
                } else if (event.getResultCode == Code.NONODE.intValue()) {
                    promise.success(MultiValueKey(key, Set()))
                } else {
                    promise.failure(makeThrowable(
                        clazz.getSimpleName, getIdString(clazz, id),
                        key, null, event.getResultCode))
                }
            })).forPath(getKeyPath(clazz, id, key))
        }
        promise.future
    }

    override def keyObservable(clazz: Class[_], id: ObjId, key: String)
    : Observable[StateKey] = {
        assertBuilt()
        // TODO
        ???
    }

    @inline
    private[storage] def getStateClassPath(clazz: Class[_],
                                           version: Long = version.longValue())
    : String = {
        statePath(version) + "/" + clazz.getSimpleName
    }

    @inline
    private[storage] def getStateObjectPath(clazz: Class[_], id: ObjId,
                                            version: Long = version.longValue())
    : String = {
        getStateClassPath(clazz, version) + "/" + getIdString(clazz, id)
    }

    @inline
    private[storage] def getKeyPath(clazz: Class[_], id: ObjId, key: String,
                                    version: Long = version.longValue())
    : String = {
        getStateObjectPath(clazz, id, version) + "/" + key
    }

    @inline
    private[storage] def getValuePath(clazz: Class[_], id: ObjId, key: String,
                                      value: String,
                                      version: Long = version.longValue())
    : String = {
        getKeyPath(clazz, id, key, version) + "/" + value
    }

    protected def makeBackground(callback: (CuratorEvent) => Unit)
    : BackgroundCallback = {
        new BackgroundCallback {
            override def processResult(client: CuratorFramework,
                                       event: CuratorEvent): Unit = {
                callback(event)
            }
        }
    }

    /** Adds a value for the single first write wins policy. */
    private def addSingleValueFirst(clazz: Class[_], id: ObjId, key: String,
                                    value: String): Future[StateResult] = {
        val promise = Promise[StateResult]()
        val ownerId = curator.getZookeeperClient.getZooKeeper.getSessionId
        val path = getKeyPath(clazz, id, key)

        // Check the key node existence.
        curator.checkExists().inBackground(makeBackground(event => {
            if (event.getResultCode == Code.OK.intValue()) {
                if (event.getStat.getEphemeralOwner == ownerId) {
                    // The node exists and the caller is the owner: set value
                    // as the key data.
                    curator.setData().withVersion(event.getStat.getVersion)
                        .inBackground(complete(promise, clazz, id, key, value,
                                               ownerId))
                        .forPath(path, value.getBytes(StringEncoding))
                } else {
                    // The value has a different owner.
                    promise.failure(new NotStateOwnerException(
                        clazz.getSimpleName, getIdString(clazz, id), key, value,
                        event.getStat.getEphemeralOwner))
                }
            } else if (event.getResultCode == Code.NONODE.intValue()) {
                // The node does not exist: create the key node with the value
                // as data.
                curator.create().withMode(CreateMode.EPHEMERAL)
                    .inBackground(complete(promise, clazz, id, key, value,
                                           ownerId))
                    .forPath(path, value.getBytes(StringEncoding))
            } else {
                // A different error occurred.
                promise.failure(makeThrowable(
                    clazz.getSimpleName, getIdString(clazz, id), key, value,
                    event.getResultCode))
            }
        })).forPath(path)
        promise.future
    }

    /** Adds a value for the single last write wins policy. */
    private def addSingleValueLast(clazz: Class[_], id: ObjId, key: String,
                                   value: String): Future[StateResult] = {
        val promise = Promise[StateResult]()
        val ownerId = curator.getZookeeperClient.getZooKeeper.getSessionId
        val path = getKeyPath(clazz, id, key)

        // Check the key node existence.
        curator.checkExists().inBackground(makeBackground(event => {
            if (event.getResultCode == Code.OK.intValue()) {
                if (event.getStat.getEphemeralOwner == ownerId) {
                    // The node exists and the caller is the owner: set value
                    // as the key data.
                    curator.setData().withVersion(event.getStat.getVersion)
                        .inBackground(complete(promise, clazz, id, key, value,
                                               ownerId))
                        .forPath(path, value.getBytes(StringEncoding))
                } else {
                    // The value has a different owner: delete the current
                    // key node.
                    curator.delete().withVersion(event.getStat.getVersion)
                           .inBackground(makeBackground(event => {
                        if (event.getResultCode == Code.OK.intValue()) {
                            // The deletion completed: create a new key node
                            // with the value as the node data.
                            curator.create().withMode(CreateMode.EPHEMERAL)
                                .inBackground(complete(
                                    promise, clazz, id, key, value, ownerId))
                                .forPath(path, value.getBytes(StringEncoding))
                        } else {
                            // Deletion failed.
                            promise.failure(makeThrowable(
                                clazz.getSimpleName, getIdString(clazz, id),
                                key, value, event.getResultCode))
                        }
                    })).forPath(path)
                }
            } else if (event.getResultCode == Code.NONODE.intValue()) {
                // The node does not exist: create the key node with the value
                // as data.
                curator.create().withMode(CreateMode.EPHEMERAL)
                    .inBackground(complete(promise, clazz, id, key, value,
                                           ownerId))
                    .forPath(path, value.getBytes(StringEncoding))
            } else {
                // A different error occurred.
                promise.failure(makeThrowable(
                    clazz.getSimpleName, getIdString(clazz, id), key, value,
                    event.getResultCode))
            }
        })).forPath(path)
        promise.future
    }

    /** Adds a value for the multi value policy. */
    private def addMultiValue(clazz: Class[_], id: ObjId, key: String,
                              value: String): Future[StateResult] = {
        val promise = Promise[StateResult]()
        val ownerId = curator.getZookeeperClient.getZooKeeper.getSessionId
        val path = getValuePath(clazz, id, key, value)

        // Check the value node existence.
        curator.checkExists().inBackground(makeBackground(event => {
            if (event.getResultCode == Code.OK.intValue()) {
                if (event.getStat.getEphemeralOwner == ownerId) {
                    // The value exists and the caller is the owner: complete
                    // the promise immediately.
                    promise.success(StateResult(ownerId))
                } else {
                    // The value has a different owner: delete the current
                    // value node.
                    curator.delete().withVersion(event.getStat.getVersion)
                           .inBackground(makeBackground(event => {
                        if (event.getResultCode == Code.OK.intValue()) {
                            // The deletion completed: create a new value node
                            // with the value as the node name.
                            curator.create().withMode(CreateMode.EPHEMERAL)
                                .inBackground(complete(promise, clazz, id, key,
                                                       value, ownerId))
                                .forPath(path)
                        } else {
                            // Deletion failed.
                            promise.failure(makeThrowable(
                                clazz.getSimpleName, getIdString(clazz, id),
                                key, value, event.getResultCode))
                        }
                    })).forPath(path)
                }
            } else if (event.getResultCode == Code.NONODE.intValue()) {
                // The node does not exist: create the value node with the value
                // as name.
                curator.create().withMode(CreateMode.EPHEMERAL)
                    .inBackground(complete(promise, clazz, id, key, value,
                                           ownerId))
                    .forPath(path)
            } else {
                // A different error occurred.
                promise.failure(makeThrowable(
                    clazz.getSimpleName, getIdString(clazz, id), key, value,
                    event.getResultCode))
            }
        })).forPath(path)
        promise.future
    }

    /** Removes a value for the multi value policy. */
    private def removeValue(clazz: Class[_], id: ObjId, key: String,
                            value: String, getPath: => String)
    : Future[StateResult] = {
        val promise = Promise[StateResult]()
        val ownerId = curator.getZookeeperClient.getZooKeeper.getSessionId
        val path = getPath

        // Check the node existence.
        curator.checkExists().inBackground(makeBackground(event => {
            if (event.getResultCode == Code.OK.intValue()) {
                if (event.getStat.getEphemeralOwner == ownerId) {
                    // The node exists and the caller is the owner: delete the
                    // key node.
                    curator.delete().withVersion(event.getStat.getVersion)
                        .inBackground(complete(promise, clazz, id, key, null,
                                               ownerId))
                        .forPath(path)
                } else {
                    // The value has a different owner.
                    promise.failure(new NotStateOwnerException(
                        clazz.getSimpleName, getIdString(clazz, id), key, value,
                        event.getStat.getEphemeralOwner))
                }
            } else if (event.getResultCode == Code.NONODE.intValue()) {
                // The node does not exist: complete immediately.
                promise.success(StateResult(NoOwnerId))
            } else {
                // A different error occurred.
                promise.failure(makeThrowable(
                    clazz.getSimpleName, getIdString(clazz, id), key, null,
                    event.getResultCode))
            }
        })).forPath(path)

        promise.future
    }

    @inline
    private def complete(promise: Promise[StateResult], clazz: Class[_],
                                id: ObjId, key: String, value: String,
                                ownerId: Long): BackgroundCallback = {
        makeBackground(event => {
            // Complete the promise.
            if (event.getResultCode == Code.OK.intValue()) {
                promise.success(StateResult(ownerId))
            } else {
                promise.failure(makeThrowable(
                    clazz.getSimpleName, getIdString(clazz, id), key, value,
                    event.getResultCode))
            }
        })
    }

}

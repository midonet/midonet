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

import scala.collection.JavaConverters._
import scala.concurrent.{Promise, Future}

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.api.{BackgroundCallback, CuratorEvent}
import org.apache.zookeeper.CreateMode
import org.apache.zookeeper.KeeperException.Code

import rx.Observable

import org.midonet.cluster.data._
import org.midonet.cluster.data.storage.TransactionManager._
import org.midonet.cluster.data.storage.WritePolicy.WritePolicy
import org.midonet.cluster.data.storage.StorageWithState.{NoOwnerId, StringEncoding}
import org.midonet.cluster.data.storage.ZookeeperObjectState.makeThrowable

object ZookeeperObjectState {

    /** Creates the appropriate exception for the given result code. */
    private def makeThrowable(clazz: String, id: String, key: String,
                              value: String, result: Int)
    : Throwable = {
        if (result == Code.BADVERSION.intValue()) {
            new StateConcurrencyException(clazz, id, key, value, result)
        } else {
            new StateException(clazz, id, key, value, result)
        }
    }

}

/**
 * Provides an implementation of the [[StorageWithState]] using ZooKeeper as
 * backend database.
 */
trait ZookeeperObjectState extends StorageWithState with Storage {

    private def statePath(version: Long) = s"$rootPath/$version/state"

    protected def rootPath: String

    protected def curator: CuratorFramework

    protected def version: AtomicLong

    @throws[IllegalStateException]
    @throws[IllegalArgumentException]
    override def registerKey(clazz: Class[_], key: String,
                             writePolicy: WritePolicy): Unit = {
        if(isBuilt) {
            throw new IllegalStateException("Cannot register a key after the " +
                                            "building the storage")
        }

        val name = clazz.getSimpleName
        if (!isRegistered(clazz)) {
            throw new IllegalArgumentException(s"Class $name is not registered")
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
     *   will fail with a [[StateOwnershipException]].
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
     *   corresponding to the new client session.
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
                           .inBackground(makeBackground(event => {
                        // Complete the promise.
                        if (event.getResultCode == Code.OK.intValue()) {
                            promise.success(StateResult(ownerId))
                        } else {
                            promise.failure(makeThrowable(
                                clazz.getSimpleName, getIdString(clazz, id),
                                key, value, event.getResultCode))
                        }
                    })).forPath(path, value.getBytes(StringEncoding))
                } else {
                    // The value has a different owner.
                    promise.failure(new StateOwnershipException(
                        clazz.getSimpleName, getIdString(clazz, id), key, value,
                        event.getStat.getEphemeralOwner))
                }
            } else if (event.getResultCode == Code.NONODE.intValue()) {
                // The node does not exist: create the key node with the value
                // as data.
                curator.create().withMode(CreateMode.EPHEMERAL)
                       .inBackground(makeBackground(event => {
                    // Complete the promise.
                    if (event.getResultCode == Code.OK.intValue()) {
                        promise.success(StateResult(ownerId))
                    } else {
                        promise.failure(makeThrowable(
                            clazz.getSimpleName, getIdString(clazz, id), key,
                            value, event.getResultCode))
                    }
                })).forPath(path, value.getBytes(StringEncoding))
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
                           .inBackground(makeBackground(event => {
                        // Complete the promise.
                        if (event.getResultCode == Code.OK.intValue()) {
                            promise.success(StateResult(ownerId))
                        } else {
                            promise.failure(makeThrowable(
                                clazz.getSimpleName, getIdString(clazz, id),
                                key, value, event.getResultCode))
                        }
                    })).forPath(path, value.getBytes(StringEncoding))
                } else {
                    // The value has a different owner: delete the current
                    // key node.
                    curator.delete().withVersion(event.getStat.getVersion)
                           .inBackground(makeBackground(event => {
                        if (event.getResultCode == Code.OK.intValue()) {
                            // The deletion completed: create a new key node
                            // with the value as the node data.
                            curator.create().withMode(CreateMode.EPHEMERAL)
                                   .inBackground(makeBackground(event => {
                                // Complete the promise.
                                if (event.getResultCode == Code.OK.intValue()) {
                                    promise.success(StateResult(ownerId))
                                } else {
                                    promise.failure(makeThrowable(
                                        clazz.getSimpleName,
                                        getIdString(clazz, id), key, value,
                                        event.getResultCode))
                                }
                            })).forPath(path, value.getBytes(StringEncoding))
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
                       .inBackground(makeBackground(event => {
                    // Complete the promise.
                    if (event.getResultCode == Code.OK.intValue()) {
                        promise.success(StateResult(ownerId))
                    } else {
                        promise.failure(makeThrowable(
                            clazz.getSimpleName, getIdString(clazz, id), key,
                            value, event.getResultCode))
                    }
                })).forPath(path, value.getBytes(StringEncoding))
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
                                   .inBackground(makeBackground(event => {
                                // Complete the promise.
                                if (event.getResultCode == Code.OK.intValue()) {
                                    promise.success(StateResult(ownerId))
                                } else {
                                    promise.failure(makeThrowable(
                                        clazz.getSimpleName,
                                        getIdString(clazz, id), key, value,
                                        event.getResultCode))
                                }
                            })).forPath(path)
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
                       .inBackground(makeBackground(event => {
                    // Complete the promise.
                    if (event.getResultCode == Code.OK.intValue()) {
                        promise.success(StateResult(ownerId))
                    } else {
                        promise.failure(makeThrowable(
                            clazz.getSimpleName, getIdString(clazz, id), key,
                            value, event.getResultCode))
                    }
                })).forPath(path)
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
                        .inBackground(makeBackground(event => {
                        // Complete the promise.
                        if (event.getResultCode == Code.OK.intValue()) {
                            promise.success(StateResult(ownerId))
                        } else {
                            promise.failure(makeThrowable(
                                clazz.getSimpleName, getIdString(clazz, id),
                                key, null, event.getResultCode))
                        }
                    })).forPath(path)
                } else {
                    // The value has a different owner.
                    promise.failure(new StateOwnershipException(
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

}

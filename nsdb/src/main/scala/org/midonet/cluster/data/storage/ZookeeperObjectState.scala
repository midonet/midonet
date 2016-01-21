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

package org.midonet.cluster.data.storage

import java.util.concurrent.atomic.AtomicLong

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.api.CuratorEvent
import org.apache.curator.framework.recipes.cache.ChildData
import org.apache.zookeeper.CreateMode
import org.apache.zookeeper.KeeperException.{NoNodeException, Code}

import rx.functions.Func1
import rx.{Notification, Observable}

import org.midonet.cluster.data._
import org.midonet.cluster.data.storage.CuratorUtil.asObservable
import org.midonet.cluster.data.storage.KeyType.KeyType
import org.midonet.cluster.data.storage.StateStorage.{NoOwnerId, StringEncoding}
import org.midonet.cluster.data.storage.TransactionManager._
import org.midonet.cluster.data.storage.ZookeeperObjectState.{KeyIndex, makeThrowable}
import org.midonet.cluster.util.{DirectoryObservableClosedException, NodeObservable, NodeObservableClosedException, PathDirectoryObservable}
import org.midonet.util.functors._

object ZookeeperObjectState {

    /** Creates the appropriate exception for the given ZooKeeper result
      * code. */
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

    /** A unique index for an object key. It is used to index state key
      * observables in a map. */
    private case class KeyIndex(namespace: String, clazz: Class[_], id: String,
                                key: String)

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

    implicit private[storage] val metrics: ZoomMetrics

    private val objectObservables =
        new TrieMap[Key, NodeObservable]
    private val singleObservables =
        new TrieMap[KeyIndex, NodeObservable]
    private val multiObservables =
        new TrieMap[KeyIndex, PathDirectoryObservable]

    private val stateKeyMap: Func1[ChildData, StateKey] = makeFunc1(_ => null)

    protected def objectPath(clazz: Class[_], id: ObjId, version: Long): String

    protected def rootPath: String

    protected def curator: CuratorFramework

    protected def failFastCurator: CuratorFramework

    protected def version: AtomicLong

    @throws[IllegalStateException]
    @throws[IllegalArgumentException]
    override def registerKey(clazz: Class[_], key: String, keyType: KeyType)
    : Unit = {
        if (isBuilt) {
            throw new IllegalStateException("Cannot register a key after " +
                                            "building the storage")
        }

        if (!isRegistered(clazz)) {
            throw new IllegalArgumentException(s"Class ${clazz.getSimpleName}" +
                                               s" is not registered")
        }

        stateInfo.getOrElse(clazz, {
            val info = new StateInfo
            stateInfo.putIfAbsent(clazz, info).getOrElse(info)
        }).keys += key -> keyType
    }

    /**
     * Adding a value works as follows:
     * - For [[KeyType.SingleFirstWriteWins]] key types, the value is added
     *   as data to the key ZooKeeper node. The add should succeed if the value
     *   does not exist, or otherwise if the caller is the creator of the
     *   current value. Otherwise, if a value already exists and the caller is
     *   not the value creator, the future will fail with a
     *   [[NotStateOwnerException]].
     * - For [[KeyType.SingleLastWriteWins]] key types, the value is added as
     *   data to the key ZooKeeper node. The add should always succeed. If the
     *   caller is the current creator of the value, then the method updates the
     *   key node data. Otherwise, it deletes the current key node and creates a
     *   new ephemeral in its place containing the value.
     * - For [[KeyType.Multiple]] key types, the value is added as a child
     *   node to the key ZooKeeper node. The add should always succeed. If the
     *   caller is the current creator of the value, there is no additional
     *   change in ZooKeeper and the future completes immediately. Otherwise, it
     *   deletes the current value node and creates a new ephemeral in its place
     *   corresponding to the new client session.
     */
    @throws[ServiceUnavailableException]
    @throws[IllegalArgumentException]
    override def addValue(clazz: Class[_], id: ObjId, key: String,
                          value: String): Observable[StateResult] = {
        assertBuilt()

        val keyType = getKeyType(clazz, key)

        if (keyType.isSingle) {
            if (keyType.firstWins) {
                addSingleValueFirst(clazz, id, key, value)
            } else {
                addSingleValueLast(clazz, id, key, value, keyType.failFast)
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
     * - With [[KeyType.SingleFirstWriteWins]] or
     *   [[KeyType.SingleLastWriteWins]] the method deletes the key
     *   ZooKeeper node.
     * - With [[KeyType.Multiple]] the method deletes the value ZooKeeper
     *   node.
     */
    @throws[ServiceUnavailableException]
    @throws[IllegalArgumentException]
    override def removeValue(clazz: Class[_], id: ObjId, key: String,
                             value: String): Observable[StateResult] = {
        assertBuilt()

        val keyType = getKeyType(clazz, key)
        if (keyType.isSingle) {
            removeValue(clazz, id, key, value, keyPath(namespace, clazz, id,
                                                       key), keyType.failFast)
        } else {
            removeValue(clazz, id, key, value,
                        valuePath(namespace, clazz, id, key, value),
                        failFast = false)
        }
    }

    /**
     * Returns the value or values associated to a given key for the current
     * namespace. The method returns an observable that emits either a
     * [[SingleValueKey]] or [[MultiValueKey]] depending on the key type.
     * If there are no values for the key, the returned [[StateKey]] will
     * contain an empty [[Option]] or an empty [[Set]].
     */
    @throws[ServiceUnavailableException]
    @throws[IllegalArgumentException]
    override def getKey(clazz: Class[_], id: ObjId, key: String)
    : Observable[StateKey] = {
        getKey(namespace, clazz, id, key)
    }

    /**
     * Returns the value or values associated to a given key for the specified
     * namespace. The method returns an observable that emits either a
     * [[SingleValueKey]] or [[MultiValueKey]] depending on the key type.
     * If there are no values for the key, the returned [[StateKey]] will
     * contain an empty [[Option]] or an empty [[Set]].
     *
     * If the `namespace` is null, the returned observable completes immediately
     * with an empty state, the same as if the state value does not exist.
     */
    @throws[ServiceUnavailableException]
    @throws[IllegalArgumentException]
    override def getKey(namespace: String, clazz: Class[_], id: ObjId,
                        key: String): Observable[StateKey] = {
        assertBuilt()

        val keyType = getKeyType(clazz, key)

        if (namespace eq null) {
            if (keyType.isSingle)
                return Observable.just(SingleValueKey(key, None, NoOwnerId))
            else
                return Observable.just(MultiValueKey(key, Set()))
        }

        if (keyType.isSingle) {
            asObservable {
                curator.getData.inBackground(_)
                    .forPath(keyPath(namespace, clazz, id, key))
            }.map[Notification[StateKey]](makeFunc1 { event =>
                if (event.getResultCode == Code.OK.intValue()) {
                    val value = new String(event.getData, StringEncoding)
                    val ownerId = event.getStat.getEphemeralOwner
                    Notification.createOnNext[StateKey](
                        SingleValueKey(key, Some(value), ownerId))
                } else if (event.getResultCode == Code.NONODE.intValue()) {
                    metrics.zkNoNodeTriggered()
                    Notification.createOnNext[StateKey](
                        SingleValueKey(key, None, NoOwnerId))
                } else {
                    Notification.createOnError[StateKey](makeThrowable(
                        clazz.getSimpleName, getIdString(clazz, id),
                        key, null, event.getResultCode))
                }
            }).dematerialize[StateKey]
        } else {
            asObservable {
                curator.getChildren.inBackground(_)
                    .forPath(keyPath(namespace, clazz, id, key))
            }.map[Notification[StateKey]](makeFunc1 { event =>
                if (event.getResultCode == Code.OK.intValue()) {
                    val values = event.getChildren.asScala.toSet
                    Notification.createOnNext[StateKey](
                        MultiValueKey(key,values))
                } else if (event.getResultCode == Code.NONODE.intValue()) {
                    metrics.zkNoNodeTriggered()
                    Notification.createOnNext[StateKey](
                        MultiValueKey(key, Set()))
                } else {
                    Notification.createOnError[StateKey](makeThrowable(
                        clazz.getSimpleName, getIdString(clazz, id),
                        key, null, event.getResultCode))
                }
            }).dematerialize[StateKey]
        }
    }

    /**
     * Returns an observable that emits change notifications for the specified
     * object and state key in the current namespace. The observable will emit
     * an `onNext` notification whenever the value for the key has changed, for
     * both single and multiple valued keys.
     */
    @throws[ServiceUnavailableException]
    override def keyObservable(clazz: Class[_], id: ObjId, key: String)
    : Observable[StateKey] = {
        keyObservable(namespace, clazz, id, key)
    }

    /**
     * Returns an observable that emits change notifications for the specified
     * object and state key in the specified namespace. The observable will
     * emit an `onNext` notification whenever the value for the key has changed,
     * for both single and multiple valued keys.
     */
    @throws[ServiceUnavailableException]
    override def keyObservable(namespace: String, clazz: Class[_], id: ObjId,
                               key: String): Observable[StateKey] = {
        assertBuilt()
        val index = KeyIndex(namespace, clazz, id.toString, key)
        val ver = version.longValue()
        if (getKeyType(clazz, key).isSingle) {
            singleObservable(index, ver)
        } else {
            multiObservable(index, ver)
        }
    }

    @throws[ServiceUnavailableException]
    override def keyObservable(namespaces: Observable[String], clazz: Class[_],
                               id: ObjId, key: String): Observable[StateKey] = {
        assertBuilt()
        Observable.switchOnNext(namespaces map makeFunc1 { namespace =>
            keyObservable(namespace, clazz, id, key)
        })
    }

    /**
     * Returns the session identifier of the given curator instance.
     */
    @inline
    private def owner(zk: CuratorFramework) =
        zk.getZookeeperClient.getZooKeeper.getSessionId

    override def ownerId: Long = curator.getZookeeperClient.getZooKeeper
                                                           .getSessionId

    override def failFastOwnerId: Long = failFastCurator.getZookeeperClient
                                                        .getZooKeeper
                                                        .getSessionId

    @inline
    private[storage] def statePath(namespace: String, version: Long)
    : String = {
        s"$rootPath/$version/state/$namespace"
    }

    @inline
    private[storage] def stateClassPath(namespace: String, clazz: Class[_],
                                        version: Long = version.longValue())
    : String = {
        statePath(namespace, version) + "/" + clazz.getSimpleName
    }

    @inline
    private[storage] def stateObjectPath(namespace: String, clazz: Class[_],
                                         id: ObjId, version: Long)
    : String = {
        stateClassPath(namespace, clazz, version) + "/" + getIdString(clazz, id)
    }

    @inline
    private[storage] def keyPath(namespace: String, clazz: Class[_], id: ObjId,
                                 key: String,
                                 version: Long = version.longValue())
    : String = {
        stateObjectPath(namespace, clazz, id, version) + "/" + key
    }

    @inline
    private[storage] def valuePath(namespace: String, clazz: Class[_],
                                   id: ObjId, key: String, value: String,
                                   version: Long = version.longValue())
    : String = {
        keyPath(namespace, clazz, id, key, version) + "/" + value
    }

    /** Returns a function that converts a [[CuratorEvent]] into a
      * notification. */
    protected def asResult(clazz: Class[_], id: ObjId, key: String,
                           value: String, ownerId: Long)
    : Func1[CuratorEvent, Notification[StateResult]] = makeFunc1(event => {
        if (event.getResultCode == Code.OK.intValue()) {
            Notification.createOnNext(StateResult(ownerId))
        } else {
            Notification.createOnError(makeThrowable(
                clazz.getSimpleName, getIdString(clazz, id), key, value,
                event.getResultCode))
        }
    })

    /** Adds a value for the single first write wins policy. */
    private def addSingleValueFirst(clazz: Class[_], id: ObjId, key: String,
                                    value: String): Observable[StateResult] = {

        val path = keyPath(namespace, clazz, id, key)
        val ownerId = curator.getZookeeperClient.getZooKeeper.getSessionId

        onObjectAndStateExist(clazz, id, key, value, path) flatMap makeFunc1 { event =>
            if (event.getResultCode == Code.OK.intValue()) {
                if (event.getStat.getEphemeralOwner == ownerId) {
                    // The node exists and the caller is the owner: set value
                    // as the key data.
                    asObservable {
                        curator.setData().withVersion(event.getStat.getVersion)
                            .inBackground(_)
                            .forPath(path, value.getBytes(StringEncoding))
                    }.map[Notification[StateResult]] {
                        asResult(clazz, id, key, value, ownerId)
                    }.dematerialize[StateResult]
                } else {
                    // The value has a different owner.
                    Observable.error[StateResult](new NotStateOwnerException(
                        clazz.getSimpleName, getIdString(clazz, id), key, value,
                        event.getStat.getEphemeralOwner))
                }
            } else {
                addValueOnResult(path, event, clazz, id, key, value, curator)
            }
        }
    }

    /** Adds a value for the single last write wins policy. */
    private def addSingleValueLast(clazz: Class[_], id: ObjId, key: String,
                                   value: String, failFast: Boolean)
    : Observable[StateResult] = {

        val zk = if (failFast) failFastCurator else curator
        val ownerId = zk.getZookeeperClient.getZooKeeper.getSessionId
        val path = keyPath(namespace, clazz, id, key)

        onObjectAndStateExist(clazz, id, key, value, path,
                              zk) flatMap makeFunc1 { event =>
            if (event.getResultCode == Code.OK.intValue()) {
                if (event.getStat.getEphemeralOwner == ownerId) {
                    // The node exists and the caller is the owner: set value
                    // as the key data.
                    asObservable {
                        zk.setData().withVersion(event.getStat.getVersion)
                          .inBackground(_)
                          .forPath(path, value.getBytes(StringEncoding))
                    }.map[Notification[StateResult]] {
                        asResult(clazz, id, key, value, ownerId)
                    }.dematerialize[StateResult]
                } else {
                    // The value has a different owner: delete the current key
                    // node.
                    asObservable {
                        zk.delete().withVersion(event.getStat.getVersion)
                          .inBackground(_).forPath(path)
                    } flatMap makeFunc1 { event =>
                        if (event.getResultCode == Code.OK.intValue()) {
                            asObservable {
                                zk.create()
                                  .withMode(CreateMode.EPHEMERAL)
                                  .inBackground(_)
                                  .forPath(path, value.getBytes(StringEncoding))
                            }.map[Notification[StateResult]] {
                                asResult(clazz, id, key, value, ownerId)
                            }.dematerialize[StateResult]
                        } else {
                            // Deletion failed.
                            Observable.error[StateResult](makeThrowable(
                                clazz.getSimpleName, getIdString(clazz, id),
                                key, value, event.getResultCode))
                        }
                    }
                }
            } else {
                addValueOnResult(path, event, clazz, id, key, value, zk)
            }
        }
    }

    /** Adds a value for the multi value policy. */
    private def addMultiValue(clazz: Class[_], id: ObjId, key: String,
                              value: String): Observable[StateResult] = {
        val ownerId = curator.getZookeeperClient.getZooKeeper.getSessionId
        val path = valuePath(namespace, clazz, id, key, value)

        onObjectAndStateExist(clazz, id, key, value, path) flatMap makeFunc1 { event =>
            if (event.getResultCode == Code.OK.intValue()) {
                if (event.getStat.getEphemeralOwner == ownerId) {
                    // The value exists and the caller is the owner: complete
                    // the observable immediately.
                    Observable.just(StateResult(ownerId))
                } else {
                    // The value has a different owner: delete the current value
                    // node.
                    asObservable {
                        curator.delete().withVersion(event.getStat.getVersion)
                            .inBackground(_).forPath(path)
                    } flatMap makeFunc1 { event =>
                        if (event.getResultCode == Code.OK.intValue()) {
                            asObservable {
                                curator.create().withMode(CreateMode.EPHEMERAL)
                                    .inBackground(_)
                                    .forPath(path)
                            }.map[Notification[StateResult]] {
                                asResult(clazz, id, key, value, ownerId)
                            }.dematerialize[StateResult]
                        } else {
                            // Deletion failed.
                            Observable.error[StateResult](makeThrowable(
                                clazz.getSimpleName, getIdString(clazz, id),
                                key, value, event.getResultCode))
                        }
                    }
                }
            } else {
                addValueOnResult(path, event, clazz, id, key, value, curator)
            }
        }
    }

    private def addValueOnResult(path: String, event: CuratorEvent,
                                 clazz: Class[_], id: ObjId, key: String,
                                 value: String, zk: CuratorFramework)
    : Observable[StateResult] = {
        val owner = zk.getZookeeperClient.getZooKeeper.getSessionId
        if (event.getResultCode == Code.NONODE.intValue()) {
            asObservable {
                zk.create()
                    .creatingParentsIfNeeded()
                    .withMode(CreateMode.EPHEMERAL)
                    .inBackground(_)
                    .forPath(path, value.getBytes(StringEncoding))
            }.map[Notification[StateResult]] {
                asResult(clazz, id, key, value, owner)
            }.dematerialize[StateResult]
        } else {
            Observable.error[StateResult](makeThrowable(
                clazz.getSimpleName, getIdString(clazz, id), key, value,
                event.getResultCode))
        }
    }

    /** Removes a value for the multi value policy. */
    private def removeValue(clazz: Class[_], id: ObjId, key: String,
                            value: String, getPath: => String,
                            failFast: Boolean): Observable[StateResult] = {
        val owner = if (failFast) failFastOwnerId
                    else ownerId
        val path = getPath

        asObservable {
            curator.checkExists().inBackground(_).forPath(path)
        } flatMap makeFunc1 { event =>
            if (event.getResultCode == Code.OK.intValue()) {
                if (event.getStat.getEphemeralOwner == owner) {
                    // The node exists and the caller is the owner: delete the
                    // key node.
                    asObservable {
                        curator.delete().withVersion(event.getStat.getVersion)
                               .inBackground(_)
                               .forPath(path)
                    }.map[Notification[StateResult]] {
                        asResult(clazz, id, key, value, owner)
                    }.dematerialize[StateResult]
                } else {
                    // The value has a different owner.
                    Observable.error[StateResult](new NotStateOwnerException(
                        clazz.getSimpleName, getIdString(clazz, id), key, value,
                        event.getStat.getEphemeralOwner))
                }
            } else if (event.getResultCode == Code.NONODE.intValue()) {
                metrics.zkNoNodeTriggered()
                // The node does not exist: complete immediately.
                Observable.just(StateResult(NoOwnerId))
            } else {
                // A different error occurred.
                Observable.error[StateResult](makeThrowable(
                    clazz.getSimpleName, getIdString(clazz, id), key,
                    value = null, event.getResultCode))
            }
        }
    }

    /** Returns a node observable for the state path of the given object.
      * This observable is used to detect when an object is deleted, in
      * order to complete single-value key observables. */
    private def objectObservable(clazz: Class[_], id: ObjId, version: Long)
    : Observable[StateKey] = {

        val key = Key(clazz, getIdString(clazz, id))
        val path = objectPath(clazz, id, version)

        objectObservables.getOrElse(key, {
            val observable = NodeObservable.create(curator, path,
                                                   completeOnDelete = true,
                                                   BlackHoleZoomMetrics)
            objectObservables.putIfAbsent(key, observable).getOrElse(observable)
        }).ignoreElements()
          .map[StateKey](stateKeyMap)
          .onErrorResumeNext(makeFunc1((t: Throwable) => t match {
            case e: NodeObservableClosedException =>
                objectObservables.remove(key)
                objectObservable(clazz, id, version)
            case e: NoNodeException => Observable.empty()
            case e: Throwable => Observable.error(e)
        }))
    }

    /** Returns an observable for a single-value key, using a [[NodeObservable]]
      * for the key path that ignores deletions. Completion of the observable
      * is ensured by filtering elements through the object observable using
      * `takeUntil`. */
    private def singleObservable(index: KeyIndex, version: Long)
    : Observable[StateKey] = {

        val namespace = index.namespace
        val id = index.id
        val clazz = index.clazz
        val key = index.key

        if (namespace eq null) {
            return Observable.just(SingleValueKey(index.key, None, NoOwnerId))
        }

        val path = keyPath(namespace, clazz,
                           getIdString(clazz, id), key, version)

        val obs = singleObservables.getOrElse(index, {
            val observable = NodeObservable.create(curator, path,
                                                   completeOnDelete = false,
                                                   metrics)
            singleObservables.putIfAbsent(index, observable)
                             .getOrElse(observable)
        })
        obs.map[StateKey](makeFunc1[ChildData, StateKey] { data =>
            if ((data.getData ne null) && (data.getStat ne null)) {
                val value = new String(data.getData, StringEncoding)
                val owner = data.getStat.getEphemeralOwner
                SingleValueKey(index.key, Some(value), owner)
            } else {
                SingleValueKey(index.key, None, NoOwnerId)
            }
        }).onErrorResumeNext(
            recover[StateKey, NodeObservable](index, version, obs,
                                              singleObservables,
                                              singleObservable)
        ).takeUntil(objectObservable(clazz, id, version))
    }

    /** Returns an observable for a multi-value key, using a
      * [[PathDirectoryObservable]] for the key path. */
    private def multiObservable(index: KeyIndex, version: Long)
    : Observable[StateKey] = {

        val namespace = index.namespace
        val id = index.id
        val clazz = index.clazz
        val key = index.key

        if (namespace eq null) {
            return Observable.just(MultiValueKey(index.key, Set()))
        }

        val path = keyPath(namespace, clazz, getIdString(clazz, id), key,
                           version)

        val obs = multiObservables.getOrElse(index, {
            val observable =
                PathDirectoryObservable.create(curator, path,
                                               completeOnDelete = false, metrics)
            multiObservables.putIfAbsent(index, observable)
                            .getOrElse(observable)
        })
        // Note: the distinctUntilChange will filter duplicate notifications,
        // which may occur from the directory observable when creating the
        // multi key node in ZooKeeper.
        obs.distinctUntilChanged()
           .map[StateKey](makeFunc1[Set[String], StateKey] {
            MultiValueKey(key, _)
        }).onErrorResumeNext(
            recover[StateKey, PathDirectoryObservable](index, version, obs,
                                                       multiObservables,
                                                       multiObservable)
        ).takeUntil(objectObservable(clazz, id, version))
    }

    /** Verifies that the specified object exists in the topology model.
      * The method returns an observable, which when subscribed to
      * asynchronously verifies that the object exists, and emits a
      * notification when the verification has completed. If the object does not
      * exist, the observable emits an [[UnmodifiableStateException]] error. */
    @inline
    private def onObjectExists(clazz: Class[_], id: ObjId, key: String,
                               value: String, zk: CuratorFramework)
    : Observable[StateResult] = {
        asObservable {
            zk.checkExists()
              .inBackground(_)
              .forPath(objectPath(clazz, id, version.get))
        }.map[Notification[StateResult]] {
            asResult(clazz, id, key, value, owner(zk))
        }.dematerialize[StateResult]
    }

    /** Verifies that the specified path exists. The method returns an
      * observable, which when subscribed to asynchronously verifies that the
      * path exists and emits a notification with the result. */
    @inline
    private def onPathExists(path: String, zk: CuratorFramework)
    : Observable[CuratorEvent] = {
        asObservable {
            zk.checkExists().inBackground(_).forPath(path)
        }
    }

    /** Verifies that the specified object and state path exist. The method
      * returns an observable, which when subscribed to, asynchronously verifies
      * that the object and path exists and emits a notification with the
      * result. */
    @inline
    private def onObjectAndStateExist(clazz: Class[_], id: ObjId, key: String,
                                      value: String, path: String,
                                      zk: CuratorFramework = curator)
    : Observable[CuratorEvent] = {
        onObjectExists(clazz, id, key, value, zk) flatMap makeFunc1 { _ =>
            onPathExists(path, zk)
        }
    }

    /* Utility function to unify the code written below. */
    private def recover[T, OBS](index: KeyIndex, version: Long,
                                obs: OBS, onMap: TrieMap[KeyIndex, OBS],
                                reAcquire: (KeyIndex, Long) => Observable[T]) = {
        makeFunc1[Throwable, Observable[T]] { t: Throwable =>
            metrics.count(t) match {
                case e: NodeObservableClosedException=>
                    onMap.remove(index, obs)
                    reAcquire(index, version)
                case e: DirectoryObservableClosedException =>
                    onMap.remove(index, obs)
                    reAcquire(index, version)
                case e: Throwable => Observable.error(e)
            }
        }
    }

}

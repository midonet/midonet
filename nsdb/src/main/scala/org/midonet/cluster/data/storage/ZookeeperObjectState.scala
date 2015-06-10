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

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.control.NonFatal

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.api.{BackgroundCallback, CuratorEvent}
import org.apache.curator.framework.recipes.cache.ChildData
import org.apache.curator.utils.ZKPaths
import org.apache.zookeeper.KeeperException.{Code, NoNodeException}
import org.apache.zookeeper.{CreateMode, KeeperException}

import rx.Observable.OnSubscribe
import rx.{Observable, Subscriber}
import rx.functions.Func1

import org.midonet.cluster.data._
import org.midonet.cluster.data.storage.KeyType.KeyType
import org.midonet.cluster.data.storage.StateStorage.{NoOwnerId, StringEncoding}
import org.midonet.cluster.data.storage.TransactionManager._
import org.midonet.cluster.data.storage.ZookeeperObjectState.{KeyIndex, makeThrowable}
import org.midonet.cluster.util.{DirectoryObservableClosedException, NodeObservable, NodeObservableClosedException, PathDirectoryObservable}
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

    /** A unique index for an object key. It is used to index state key
      * observables in a map. */
    private case class KeyIndex(clazz: Class[_], id: String, key: String)

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
        protected def createStateOps: Seq[(Key, TxOp)] = {
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

    private val objectObservables =
        new TrieMap[Key, NodeObservable]
    private val singleObservables =
        new TrieMap[KeyIndex, NodeObservable]
    private val multiObservables =
        new TrieMap[KeyIndex, PathDirectoryObservable]

    private val stateKeyMap: Func1[ChildData, StateKey] = makeFunc1(_ => null)

    private def statePath(version: Long) = s"$rootPath/$version/state"

    protected def rootPath: String

    protected def curator: CuratorFramework

    protected def version: AtomicLong

    @throws[IllegalStateException]
    @throws[IllegalArgumentException]
    override def registerKey(clazz: Class[_], key: String,
                             writePolicy: KeyType): Unit = {
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
        }).keys += key -> writePolicy
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

        if (getWritePolicy(clazz, key).isSingle) {
            removeValue(clazz, id, key, value, getKeyPath(clazz, id, key))
        } else {
            removeValue(clazz, id, key, value,
                        getValuePath(clazz, id, key, value))
        }
    }

    /**
     * Returns the value or values associated to a given key. The method returns
     * an observable that emits either a [[SingleValueKey]] or [[MultiValueKey]]
     * depending on the key type. If there are no values for the key, the
     * returned [[StateKey]] will contain an empty [[Option]] or an empty
     * [[Set]].
     */
    @throws[ServiceUnavailableException]
    @throws[IllegalArgumentException]
    override def getKey(clazz: Class[_], id: ObjId, key: String)
    : Observable[StateKey] = {
        assertBuilt()

        if (getWritePolicy(clazz, key).isSingle) {
            asObservable {
                curator.getData.inBackground(_)
                    .forPath(getKeyPath(clazz, id, key))
            } flatMap makeFunc1 { event =>
                if (event.getResultCode == Code.OK.intValue()) {
                    val value = new String(event.getData, StringEncoding)
                    val ownerId = event.getStat.getEphemeralOwner
                    Observable.just[StateKey](SingleValueKey(key, Some(value),
                                                             ownerId))
                } else if (event.getResultCode == Code.NONODE.intValue()) {
                    Observable.just[StateKey](SingleValueKey(key, None,
                                                             NoOwnerId))
                } else {
                    Observable.error[StateKey](makeThrowable(
                        clazz.getSimpleName, getIdString(clazz, id),
                        key, null, event.getResultCode))
                }
            }
        } else {
            asObservable {
                curator.getChildren.inBackground(_)
                    .forPath(getKeyPath(clazz, id, key))
            } flatMap makeFunc1 { event =>
                if (event.getResultCode == Code.OK.intValue()) {
                    val values = event.getChildren.asScala.toSet
                    Observable.just[StateKey](MultiValueKey(key, values))
                } else if (event.getResultCode == Code.NONODE.intValue()) {
                    Observable.just[StateKey](MultiValueKey(key, Set()))
                } else {
                    Observable.error[StateKey](makeThrowable(
                        clazz.getSimpleName, getIdString(clazz, id),
                        key, null, event.getResultCode))
                }
            }
        }
    }

    /**
     * Returns an observable that emits change notifications for the specified
     * object and state key. The observable will emit an `onNext` notification
     * whenever the value for the key has changed, for both single and multiple
     * valued keys.
     */
    @throws[ServiceUnavailableException]
    override def keyObservable(clazz: Class[_], id: ObjId, key: String)
    : Observable[StateKey] = {
        assertBuilt()

        if (getWritePolicy(clazz, key).isSingle) {
            singleObservable(clazz, id, key, version.longValue())
        } else {
            multiObservable(clazz, id, key, version.longValue())
        }
    }

    /**
     * Returns the session identifier.
     */
    override def sessionId = {
        curator.getZookeeperClient.getZooKeeper.getSessionId
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

    protected def asObservable(f: (BackgroundCallback) => Unit)
    : Observable[CuratorEvent] = {
        Observable.create(new OnSubscribe[CuratorEvent] {
            override def call(s: Subscriber[_ >: CuratorEvent]): Unit = {
                f(new BackgroundCallback {
                    override def processResult(client: CuratorFramework,
                                               event: CuratorEvent): Unit = {
                        s.onNext(event)
                        s.onCompleted()
                    }
                })
            }
        })
    }

    protected def asResult(clazz: Class[_], id: ObjId, key: String,
                           value: String, ownerId: Long)
    : Func1[CuratorEvent, Observable[StateResult]] = makeFunc1(event => {
        // Complete the promise.
        if (event.getResultCode == Code.OK.intValue()) {
            Observable.just(StateResult(ownerId))
        } else {
            Observable.error(makeThrowable(
                clazz.getSimpleName, getIdString(clazz, id), key, value,
                event.getResultCode))
        }
    })

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
                                    value: String): Observable[StateResult] = {

        val path = getKeyPath(clazz, id, key)
        val ownerId = curator.getZookeeperClient.getZooKeeper.getSessionId

        asObservable {
            curator.checkExists().inBackground(_).forPath(path)
        } flatMap makeFunc1 { event =>
            if (event.getResultCode == Code.OK.intValue()) {
                if (event.getStat.getEphemeralOwner == ownerId) {
                    // The node exists and the caller is the owner: set value
                    // as the key data.
                    asObservable {
                        curator.setData().withVersion(event.getStat.getVersion)
                            .inBackground(_)
                            .forPath(path, value.getBytes(StringEncoding))
                    } flatMap {
                        asResult(clazz, id, key, value, ownerId)
                    }
                } else {
                    // The value has a different owner.
                    Observable.error[StateResult](new NotStateOwnerException(
                        clazz.getSimpleName, getIdString(clazz, id), key, value,
                        event.getStat.getEphemeralOwner))
                }
            } else if (event.getResultCode == Code.NONODE.intValue()) {
                asObservable {
                    curator.create().withMode(CreateMode.EPHEMERAL)
                        .inBackground(_)
                        .forPath(path, value.getBytes(StringEncoding))
                } flatMap {
                    asResult(clazz, id, key,value, ownerId)
                }
            } else {
                Observable.error[StateResult](makeThrowable(
                    clazz.getSimpleName, getIdString(clazz, id), key, value,
                    event.getResultCode))
            }
        }
    }

    /** Adds a value for the single last write wins policy. */
    private def addSingleValueLast(clazz: Class[_], id: ObjId, key: String,
                                   value: String): Observable[StateResult] = {
        val ownerId = curator.getZookeeperClient.getZooKeeper.getSessionId
        val path = getKeyPath(clazz, id, key)

        asObservable {
            curator.checkExists().inBackground(_).forPath(path)
        } flatMap makeFunc1 { event =>
            if (event.getResultCode == Code.OK.intValue()) {
                if (event.getStat.getEphemeralOwner == ownerId) {
                    // The node exists and the caller is the owner: set value
                    // as the key data.
                    asObservable {
                        curator.setData().withVersion(event.getStat.getVersion)
                            .inBackground(_)
                            .forPath(path, value.getBytes(StringEncoding))
                    } flatMap {
                        asResult(clazz, id, key, value,ownerId)
                    }
                } else {
                    // The value has a different owner: delete the current key
                    // node.
                    asObservable {
                        curator.delete().withVersion(event.getStat.getVersion)
                            .inBackground(_).forPath(path)
                    } flatMap makeFunc1 { event =>
                        if (event.getResultCode == Code.OK.intValue()) {
                            asObservable {
                                curator.create().withMode(CreateMode.EPHEMERAL)
                                    .inBackground(_)
                                    .forPath(path, value.getBytes(StringEncoding))
                            } flatMap {
                                asResult(clazz, id, key,value, ownerId)
                            }
                        } else {
                            // Deletion failed.
                            Observable.error[StateResult](makeThrowable(
                                clazz.getSimpleName, getIdString(clazz, id),
                                key, value, event.getResultCode))
                        }
                    }
                }
            } else if (event.getResultCode == Code.NONODE.intValue()) {
                asObservable {
                    curator.create().withMode(CreateMode.EPHEMERAL)
                        .inBackground(_)
                        .forPath(path, value.getBytes(StringEncoding))
                } flatMap {
                    asResult(clazz, id, key, value,ownerId)
                }
            } else {
                Observable.error[StateResult](makeThrowable(
                    clazz.getSimpleName, getIdString(clazz, id), key, value,
                    event.getResultCode))
            }
        }
    }

    /** Adds a value for the multi value policy. */
    private def addMultiValue(clazz: Class[_], id: ObjId, key: String,
                              value: String): Observable[StateResult] = {
        val ownerId = curator.getZookeeperClient.getZooKeeper.getSessionId
        val path = getValuePath(clazz, id, key, value)

        asObservable {
            curator.checkExists().inBackground(_).forPath(path)
        } flatMap makeFunc1 { event =>
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
                            } flatMap {
                                asResult(clazz, id, key, value, ownerId)
                            }
                        } else {
                            // Deletion failed.
                            Observable.error[StateResult](makeThrowable(
                                clazz.getSimpleName, getIdString(clazz, id),
                                key, value, event.getResultCode))
                        }
                    }
                }
            } else if (event.getResultCode == Code.NONODE.intValue()) {
                asObservable {
                    curator.create().withMode(CreateMode.EPHEMERAL)
                        .inBackground(_).forPath(path)
                } flatMap {
                    asResult(clazz, id, key, value,ownerId)
                }
            } else {
                Observable.error[StateResult](makeThrowable(
                    clazz.getSimpleName, getIdString(clazz, id), key, value,
                    event.getResultCode))
            }
        }
    }

    /** Removes a value for the multi value policy. */
    private def removeValue(clazz: Class[_], id: ObjId, key: String,
                            value: String, getPath: => String)
    : Observable[StateResult] = {
        val ownerId = curator.getZookeeperClient.getZooKeeper.getSessionId
        val path = getPath

        asObservable {
            curator.checkExists().inBackground(_).forPath(path)
        } flatMap makeFunc1 { event =>
            if (event.getResultCode == Code.OK.intValue()) {
                if (event.getStat.getEphemeralOwner == ownerId) {
                    // The node exists and the caller is the owner: delete the
                    // key node.
                    asObservable {
                        curator.delete().withVersion(event.getStat.getVersion)
                            .inBackground(_)
                            .forPath(path)
                    } flatMap {
                        asResult(clazz, id, key, value,ownerId)
                    }
                } else {
                    // The value has a different owner.
                    Observable.error[StateResult](new NotStateOwnerException(
                        clazz.getSimpleName, getIdString(clazz, id), key, value,
                        event.getStat.getEphemeralOwner))
                }
            } else if (event.getResultCode == Code.NONODE.intValue()) {
                // The node does not exist: complete immediately.
                Observable.just(StateResult(NoOwnerId))
            } else {
                // A different error occurred.
                Observable.error[StateResult](makeThrowable(
                    clazz.getSimpleName, getIdString(clazz, id), key, null,
                    event.getResultCode))
            }
        }
    }

    /** Returns an node observable for the state path of the given object.
      * This observable is used to detect when an object is deleted, in
      * order to complete single-value key observables. */
    private def objectObservable(clazz: Class[_], id: ObjId, version: Long)
    : Observable[StateKey] = {

        val key = Key(clazz, getIdString(clazz, id))
        val path = getStateObjectPath(clazz, id, version)

        objectObservables.getOrElse(key, {
            val observable = NodeObservable.create(curator, path,
                                                   completeOnDelete = true)
            objectObservables.putIfAbsent(key, observable).getOrElse(observable)
        }).ignoreElements()
          .map[StateKey](stateKeyMap)
          .onErrorResumeNext(makeFunc1((t: Throwable) => t match {
            case e: NodeObservableClosedException =>
                objectObservables.remove(key)
                objectObservable(clazz, id, version)
            case e: Throwable => Observable.error(e)
        }))
    }

    /** Returns an observable for a single-value key, using a [[NodeObservable]]
      * for the key path that ignores deletions. Completion of the observable
      * is ensured by filtering elements through the object observable using
      * `takeUntil`. */
    private def singleObservable(clazz: Class[_], id: ObjId, key: String,
                                 version: Long): Observable[StateKey] = {

        val index = KeyIndex(clazz, getIdString(clazz, id), key)
        val path = getKeyPath(clazz, getIdString(clazz, id), key, version)

        singleObservables.getOrElse(index, {
            val observable = NodeObservable.create(curator, path,
                                                   completeOnDelete = false)
            singleObservables.putIfAbsent(index, observable)
                             .getOrElse(observable)
        }).map[StateKey](makeFunc1((data: ChildData) => {
            if ((data.getData ne null) && (data.getStat ne null)) {
                val value = new String(data.getData, StringEncoding)
                val owner = data.getStat.getEphemeralOwner
                SingleValueKey(key, Some(value), owner)
            } else {
                SingleValueKey(key, None, NoOwnerId)
            }
        })).onErrorResumeNext(makeFunc1((t: Throwable) => t match {
            case e: NodeObservableClosedException =>
                singleObservables.remove(index)
                singleObservable(clazz, id, key, version)
            case e: Throwable => Observable.error(e)
        })).takeUntil(objectObservable(clazz, id, version))
    }

    /** Returns an observable for a multi-value key, using a
      * [[PathDirectoryObservable]] for the key path.
      */
    private def multiObservable(clazz: Class[_], id: ObjId, key: String,
                                version: Long): Observable[StateKey] = {

        val index = KeyIndex(clazz, getIdString(clazz, id), key)
        val path = getKeyPath(clazz, getIdString(clazz, id), key, version)

        multiObservables.getOrElse(index, {
            val observable = PathDirectoryObservable.create(curator, path)
            multiObservables.putIfAbsent(index, observable)
                            .getOrElse(observable)
        }).map[StateKey](makeFunc1((values: Set[String]) => {
            MultiValueKey(key, values)
        })).onErrorResumeNext(makeFunc1((t: Throwable) => t match {
            case e: DirectoryObservableClosedException =>
                multiObservables.remove(index)
                multiObservable(clazz, id, key, version)
            case e: Throwable => Observable.error(e)
        }))
    }

}

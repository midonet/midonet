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

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong, AtomicReference}

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.Try
import scala.util.control.NonFatal

import com.codahale.metrics.MetricRegistry

import org.apache.curator.framework.state.ConnectionState
import org.apache.zookeeper.KeeperException.{BadVersionException, Code}

import rx.Observable.OnSubscribe
import rx._
import rx.subjects.PublishSubject

import org.midonet.cluster.backend.{Directory, MockDirectory}
import org.midonet.cluster.data.storage.InMemoryStorage._
import org.midonet.cluster.data.storage.KeyType.KeyType
import org.midonet.cluster.data.storage.StateStorage._
import org.midonet.cluster.data.storage.TransactionManager._
import org.midonet.cluster.data.storage.ZookeeperObjectMapper._
import org.midonet.cluster.data.storage.ZoomSerializer.{deserialize, serialize}
import org.midonet.cluster.data.storage.metrics.StorageMetrics
import org.midonet.cluster.data.{getIdString, Obj, ObjId}
import org.midonet.cluster.models.Commons
import org.midonet.cluster.rpc.State.ProxyResponse.Notify
import org.midonet.cluster.services.state.client.StateTableClient.ConnectionState.{ConnectionState => ProxyConnectionState}
import org.midonet.cluster.services.state.client.{StateSubscriptionKey, StateTableClient}
import org.midonet.cluster.util.ParentDeletedException
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.util.concurrent._
import org.midonet.util.functors._

/**
 * A simple in-memory implementation of the [[Storage]] trait, equivalent to
 * the [[ZookeeperObjectMapper]] to use within unit tests.
 */
class InMemoryStorage extends Storage with StateStorage with StateTableStorage with StateTablePaths {
    override protected val version = new AtomicLong(1)
    override protected val rootPath = "/inMemoryStorage"
    override protected val zoomPath = "/inMemoryStorage"

    private val executor = new SameThreadButAfterExecutorService()

    private class ClassNode[T](val clazz: Class[T]) {

        private val instances = new TrieMap[String, InstanceNode[T]]()
        private val stream = PublishSubject.create[Observable[T]]()

        private val streamUpdates = new mutable.LinkedHashSet[InstanceNode[T]]
        private val instanceUpdates = new mutable.LinkedHashSet[InstanceNode[T]]

        def ids = instances.keySet

        def clear(): Unit = {
            instances.clear()
            streamUpdates.clear()
            instanceUpdates.clear()
        }

        @throws[ObjectExistsException]
        def create(id: ObjId, obj: Obj): Unit = {
            val node = new InstanceNode(clazz, id, obj.asInstanceOf[T])
            instances.putIfAbsent(getIdString(id), node) match {
                case Some(n) => throw new ObjectExistsException(clazz, id)
                case None => streamUpdates += node
            }
        }

        @throws[ObjectExistsException]
        def validateCreate(id: ObjId): Unit = {
            if (instances.contains(id.toString))
                throw new ObjectExistsException(clazz, id)
        }

        /* Write synchronized by the transaction, call on IO thread */
        @throws[NotFoundException]
        @throws[BadVersionException]
        def update(id: ObjId, obj: Obj, version: Int)
        : Unit = {
            instances.get(getIdString(id)) match {
                case Some(node) =>
                    instanceUpdates += node
                    node.update(obj.asInstanceOf[T], version)
                case None => throw new NotFoundException(clazz, id)
            }
        }

        @throws[NotFoundException]
        @throws[BadVersionException]
        def validateUpdate(id: ObjId, version: Int)
        : Unit = {
            instances.get(getIdString(id)) match {
                case Some(node) => node.validateUpdate(version)
                case None => throw new NotFoundException(clazz, id)
            }
        }

        @throws[NotFoundException]
        @throws[BadVersionException]
        def delete(id: ObjId, version: Int): T = {
            instances.remove(getIdString(id)) match {
                case Some(node) =>
                    instanceUpdates += node
                    node.delete(version)
                case None => throw new NotFoundException(clazz, id)
            }
        }

        @throws[NotFoundException]
        @throws[BadVersionException]
        def validateDelete(id: ObjId, version: Int)
        : Unit = {
            instances.get(getIdString(id)) match {
                case Some(node) => node.validateDelete(version)
                case None => throw new NotFoundException(clazz, id)
            }
        }

        def apply(id: ObjId): Option[T] =
            instances.get(getIdString(id)).map(_.value)

        def get(id: ObjId): Future[T] = {
            instances.get(getIdString(id)) match {
                case Some(node) => node.get
                case None => Future.failed(new NotFoundException(clazz, id))
            }
        }

        def getAll(ids: Seq[_ <: ObjId]): Future[Seq[T]] =
            try {
                Future.successful(ids.map(get(_).await(5 seconds)))
            } catch {
                case e: Exception => Future.failed(e)
            }

        def getAll: Future[Seq[T]] =
            try {
                Future.successful(
                    instances.values.map(_.get.await(5 seconds)).to[Seq])
            } catch {
                case e: Exception => Future.failed(e)
            }

        def getSnapshot(id: ObjId): ObjSnapshot = {
            instances.getOrElse(getIdString(id),
                                throw new NotFoundException(clazz, id))
                     .getSnapshot
        }

        def exists(id: ObjId): Future[Boolean] = Future.successful(
            instances.containsKey(getIdString(id)))

        def observable(id: ObjId): Observable[T] = {
            instances.get(getIdString(id)) match {
                case Some(node) => node.observable
                case None => Observable.error(new NotFoundException(clazz, id))
            }
        }

        def observable: Observable[Observable[T]] = Observable.create(
            new OnSubscribe[Observable[T]] {
                override def call(sub: Subscriber[_ >: Observable[T]]): Unit = {
                    instances.values.foreach { i => sub.onNext(i.observable) }
                    sub.add(stream.unsafeSubscribe(sub))
                }
            }
        )

        def observableError(id: ObjId, e: Throwable): Unit = {
            instances.get(getIdString(id)) match {
                case Some(node) => node.observableError(e)
                case None =>
            }
        }

        /* Emits the updates generated during the last transaction */
        def emitUpdates(): Unit = executor.submit(makeRunnable({
            for (node <- streamUpdates) {
                stream.onNext(node.observable)
            }
            for (node <- instanceUpdates) {
                node.emitUpdates()
            }
            streamUpdates.clear()
            instanceUpdates.clear()
        }))

        def addValue(namespace: String, id: ObjId, key: String, value: String,
                     keyType: KeyType): Observable[StateResult] = {
            val idStr = getIdString(id)
            instances.get(idStr) match {
                case Some(instance) =>
                    instance.addValue(namespace, key, value, keyType)
                case None => asObservable {
                    throw new UnmodifiableStateException(
                        clazz.getSimpleName, idStr, key, value,
                        Code.NONODE.intValue)
                }
            }
        }

        def removeValue(namespace: String, id: ObjId, key: String, value: String,
                        keyType: KeyType, failFast: Boolean = false)
        : Observable[StateResult] = {
            val idStr = getIdString(id)
            instances.get(idStr) match {
                case Some(instance) =>
                    instance.removeValue(namespace, key, value, keyType)
                case None => asObservable {
                    StateResult(NoOwnerId)
                }
            }
        }

        def getKey(namespace: String, id: ObjId, key: String, keyType: KeyType)
        : Observable[StateKey] = {
            if (namespace eq null) {
                return emptyValueKey(key, keyType)
            }
            val idStr = getIdString(id)
            instances.get(idStr) match {
                case Some(instance) => instance.getKey(namespace, key, keyType)
                case None if keyType.isSingle =>
                    asObservable { SingleValueKey(key, None, NoOwnerId) }
                case None =>
                    asObservable { MultiValueKey(key, Set()) }
            }
        }

        def keyObservable(namespace: String, id: ObjId, key: String, keyType: KeyType)
        : Observable[StateKey] = {
            if (namespace eq null) {
                return emptyValueKey(key, keyType)
            }
            val idStr = getIdString(id)
            instances.get(idStr) match {
                case Some(instance) => instance.keyObservable(namespace, key, keyType)
                case None if keyType.isSingle =>
                    Observable.empty()
                case None =>
                    Observable.error(new ParentDeletedException(s"$clazz/$id"))
            }
        }

        def keyObservableError(namespace: String, id: ObjId, key: String,
                               e: Throwable): Unit = {
            instances.get(getIdString(id)) match {
                case Some(node) => node.keyObservableError(namespace, key, e)
                case None =>
            }
        }

        private def emptyValueKey(key: String, keyType: KeyType)
        : Observable[StateKey] = {
            if (keyType.isSingle)
                Observable.just(SingleValueKey(key, None, NoOwnerId))
            else
                Observable.just(MultiValueKey(key, Set()))
        }
    }

    private class InstanceNode[T](clazz: Class[T], id: ObjId, obj: T) {

        private val ref = new AtomicReference[T](copyObj(obj))
        private val ver = new AtomicInteger
        private val state = new TrieMap[String, StateNode]

        private val instanceSubject = new PrimedSubject(ref.get)
        private val instanceObs = Observable.create(instanceSubject)

        private var instanceUpdate: Notification[T] = null

        emitUpdates()

        def value = ref.get
        def version = ver.get

        @throws[BadVersionException]
        def update(value: T, version: Int): Unit = {
            if (!ver.compareAndSet(version, version + 1))
                throw new BadVersionException
            val newValue = copyObj(value)
            ref.set(newValue)
            instanceUpdate = Notification.createOnNext(newValue)
        }

        @throws[BadVersionException]
        def validateUpdate(version: Int): Unit = {
            if (ver.get != version) throw new BadVersionException
        }

        @throws[BadVersionException]
        def delete(version: Int): T = {
            if (!ver.compareAndSet(version, version + 1))
                throw new BadVersionException

            state.values.foreach(_.complete())
            instanceUpdate = Notification.createOnCompleted()
            value
        }

        @throws[BadVersionException]
        def validateDelete(version: Int): Unit = {
            if (ver.get != version) throw new BadVersionException
        }

        def observable = instanceObs

        def observableError(e: Throwable): Unit = {
            instanceSubject onError e
        }

        def get: Future[T] = Future.successful(value)

        def getSnapshot = ObjSnapshot(value.asInstanceOf[Obj], version)

        /* Emits the updates generated during the last transaction */
        def emitUpdates(): Unit = {
            if (instanceUpdate ne null) {
                instanceUpdate.accept(instanceSubject)
                instanceUpdate = null
            }
        }

        def addValue(namespace: String, key: String, value: String, keyType: KeyType)
        : Observable[StateResult] = {
            getOrCreateStateNode(namespace).addValue(key, value, keyType)
        }

        def removeValue(namespace: String, key: String, value: String, keyType: KeyType)
        : Observable[StateResult] = {
            getOrCreateStateNode(namespace).removeValue(key, value, keyType)
        }

        def getKey(namespace: String, key: String, keyType: KeyType): Observable[StateKey] = {
            getOrCreateStateNode(namespace).getKey(key, keyType)
        }


        def keyObservable(namespace: String, key: String, keyType: KeyType)
        : Observable[StateKey] = {
            getOrCreateStateNode(namespace).keyObservable(key, keyType)
        }

        def keyObservableError(namespace: String, key: String, e: Throwable)
        : Unit = {
            state.get(namespace) match {
                case Some(node) => node.keyObservableError(key, e)
                case None =>
            }
        }

        private def getOrCreateStateNode(namespace: String): StateNode = {
            state.getOrElse(namespace, {
                val node = new StateNode(namespace, clazz.getSimpleName,
                                         getIdString(id))
                state.putIfAbsent(namespace, node).getOrElse(node)
            })
        }
    }

    private class StateNode(namespace: String, clazz: String, id: String) {

        private val keys = new TrieMap[String, KeyNode]

        def addValue(key: String, value: String, keyType: KeyType)
        : Observable[StateResult] = {
            getOrCreateKeyNode(key, keyType).add(value)
        }

        def removeValue(key: String, value: String, keyType: KeyType)
        : Observable[StateResult] = {
            getOrCreateKeyNode(key, keyType).remove(value)
        }

        def getKey(key: String, keyType: KeyType): Observable[StateKey] = {
            getOrCreateKeyNode(key, keyType).get
        }


        def keyObservable(key: String, keyType: KeyType)
        : Observable[StateKey] = {
            getOrCreateKeyNode(key, keyType).observable
        }

        def keyObservableError(key: String, e: Throwable): Unit = {
            keys.get(key) match {
                case Some(node) => node error e
                case None =>
            }
        }

        def complete(): Unit = {
            keys.values.foreach(_.complete())
        }

        private def getOrCreateKeyNode(key: String, keyType: KeyType)
        : KeyNode = {
            keys.getOrElse(key, {
                val node = new KeyNode(clazz, id, key, keyType)
                keys.putIfAbsent(key, node).getOrElse(node)
            })
        }
    }

    private class KeyNode(clazz: String, id: String, key: String,
                          keyType: KeyType) {

        private val values = new TrieMap[String, String]
        private val subject = new PrimedSubject(values.readOnlySnapshot().toMap)

        def add(value: String): Observable[StateResult] = asObservable {
            if (keyType.isSingle) {
                values.put(key, value)
                notifyState()
            } else {
                values.putIfAbsent(value, null) match {
                    case Some(_) =>
                    case None => notifyState()
                }
            }
            StateResult(DefaultOwnerId)
        }

        def remove(value: String): Observable[StateResult] = asObservable {
            val toRemove = if (keyType.isSingle) key else value
            val ownerId = if (values.remove(toRemove).isEmpty) {
                              NoOwnerId
                          } else {
                              notifyState()
                              DefaultOwnerId
                          }
            StateResult(ownerId)
        }

        def get: Observable[StateKey] = asObservable {
            if (keyType.isSingle) {
                val v = values.get(key)
                SingleValueKey(key, v,
                               if (v.isEmpty) NoOwnerId else DefaultOwnerId)
            } else {
                MultiValueKey(key, values.readOnlySnapshot().keySet.toSet)
            }
        }

        def observable: Observable[StateKey] = {
            if (keyType.isSingle) {
                Observable.create(subject).map[StateKey](makeFunc1(map => {
                    map.headOption match {
                        case Some((_, value)) =>
                            SingleValueKey(key, Some(value), DefaultOwnerId)
                        case None =>
                            SingleValueKey(key, None, NoOwnerId)
                    }
                }))
            } else {
                Observable.create(subject).map[StateKey](makeFunc1(map => {
                    MultiValueKey(key, map.keySet)
                }))
            }
        }

        def error(e: Throwable): Unit = {
            subject onError e
        }

        def complete(): Unit = {
            subject.onCompleted()
        }

        private def notifyState(): Unit = {
            subject.onNext(values.readOnlySnapshot().toMap)
        }

    }


    private class InMemoryTransactionManager
            extends TransactionManager(classInfo.toMap, allBindings) {

        override def isRegistered(clazz: Class[_]): Boolean = {
            InMemoryStorage.this.isRegistered(clazz)
        }

        override def getSnapshot(clazz: Class[_], id: ObjId)
        : Observable[ObjSnapshot] = {
            Observable.just(classes(clazz).getSnapshot(id))
        }

        override def getIds(clazz: Class[_]): Observable[Seq[ObjId]] = {
            Observable.just(classes(clazz).ids.toSeq)
        }

        override def commit(): Unit = {
            // Validate the transaction ops.
            for ((key, op) <- ops) {
                val clazz = classes(key.clazz)
                op match {
                    case TxCreate(obj) =>
                        clazz.validateCreate(key.id)
                    case TxUpdate(obj, ver) =>
                        clazz.validateUpdate(key.id, ver)
                    case TxDelete(ver) =>
                        clazz.validateDelete(key.id, ver)
                    case _ => throw new NotImplementedError(op.toString)
                }
            }

            // Apply the transaction ops.
            for ((key, op) <- ops) {
                val clazz = classes(key.clazz)
                op match {
                    case TxCreate(obj) =>
                        clazz.create(key.id, obj)
                    case TxUpdate(obj, ver) =>
                        clazz.update(key.id, obj, ver)
                    case TxDelete(ver) =>
                        clazz.delete(key.id, ver)
                    case _ => throw new NotImplementedError(op.toString)
                }
            }

            // Emit notifications.
            for ((key, op) <- ops) {
                classes(key.clazz).emitUpdates()
            }
        }

        /** Query the backend store to determine if a node exists at the
          * specified path. */
        override protected def nodeExists(path: String): Boolean =
            throw new NotImplementedError()

        /** Query the backend store to get the fully-qualified paths of all
          * children of the specified node. */
        override protected def childrenOf(path: String): Seq[String] =
            throw new NotImplementedError()
    }

    private val classes = new ConcurrentHashMap[Class[_], ClassNode[_]]

    def clear(): Unit = {
        classes.foreach(_._2.clear())
    }

    override def namespace = namespaceId.toString

    override def get[T](clazz: Class[T], id: ObjId): Future[T] = {
        assertBuilt()
        assert(isRegistered(clazz))

        classes.get(clazz).asInstanceOf[ClassNode[T]].get(id)
    }

    override def getAll[T](clazz: Class[T],
                           ids: Seq[_ <: ObjId]): Future[Seq[T]] = {
        assertBuilt()
        assert(isRegistered(clazz))

        classes.get(clazz).asInstanceOf[ClassNode[T]].getAll(ids)
    }

    override def getAll[T](clazz: Class[T]): Future[Seq[T]] = {
        assertBuilt()
        assert(isRegistered(clazz))

        classes.get(clazz).asInstanceOf[ClassNode[T]].getAll
    }

    override def exists(clazz: Class[_], id: ObjId): Future[Boolean] = {
        assertBuilt()
        assert(isRegistered(clazz))

        classes.get(clazz).exists(id)
    }

    override def multi(ops: Seq[PersistenceOp]): Unit = {

        if (ops.isEmpty) return

        val manager = new InMemoryTransactionManager

        ops.foreach {
            case CreateOp(obj) =>
                manager.create(obj)
            case UpdateOp(obj, validator) =>
                manager.update(obj, validator)
            case DeleteOp(clazz, id, ignoresNeo) =>
                manager.delete(clazz, id, ignoresNeo)
            case op => throw new NotImplementedError(op.toString)
        }

        manager.commit()
    }

    override def transaction(): Transaction = {
        assertBuilt()
        new InMemoryTransactionManager
    }

    override def tryTransaction[R](f: (Transaction) => R): R = {
        val tx = transaction()
        val result = f(tx)
        tx.commit()
        result
    }

    /** Create an object without referential integrity protection. */
    def createRaw(obj: Obj): Unit = {
        val clazz = obj.getClass
        val id = classInfo(obj.getClass).idOf(obj)
        classes(clazz).validateCreate(id)
        classes(clazz).create(id, obj)
        classes(clazz).emitUpdates()
    }

    /** Update an object without referential integrity protection. */
    def updateRaw(obj: Obj): Unit = {
        val clazz = obj.getClass
        val id = classInfo(obj.getClass).idOf(obj)
        val snapshot = classes(clazz).getSnapshot(id)
        classes(clazz).validateUpdate(id, snapshot.version)
        classes(clazz).update(id, obj, snapshot.version)
        classes(clazz).emitUpdates()
    }

    /** Delete an object without referential integrity protection. */
    def deleteRaw(clazz: Class[_], id: ObjId): Unit = {
        val snapshot = classes(clazz).getSnapshot(id)
        classes(clazz).validateDelete(id, snapshot.version)
        classes(clazz).delete(id, snapshot.version)
        classes(clazz).emitUpdates()
    }

    override def observable[T](clazz: Class[T], id: ObjId): Observable[T] = {
        assertBuilt()
        assert(isRegistered(clazz))
        classes.get(clazz).asInstanceOf[ClassNode[T]].observable(id)
    }

    override def observable[T](clazz: Class[T]): Observable[Observable[T]] = {
        assertBuilt()
        assert(isRegistered(clazz))
        classes.get(clazz).asInstanceOf[ClassNode[T]].observable
    }

    def observableError(clazz: Class[_], id: ObjId, e: Throwable): Unit = {
        assertBuilt()
        assert(isRegistered(clazz))
        classes.get(clazz).observableError(id, e)
    }

    override def registerClass(clazz: Class[_]): Unit = {
        classes.putIfAbsent(clazz, new ClassNode(clazz)) match {
            case c: ClassNode[_] => throw new IllegalStateException(
                s"Class $clazz is already registered.")
            case _ =>
        }

        classInfo += clazz -> makeInfo(clazz)
    }

    override def isRegistered(clazz: Class[_]): Boolean = {
        classes.containsKey(clazz)
    }

    @throws[IllegalStateException]
    override def registerKey(clazz: Class[_], key: String,
                             keyType: KeyType): Unit = {
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

    @throws[ServiceUnavailableException]
    @throws[IllegalArgumentException]
    override def addValue(clazz: Class[_], id: ObjId, key: String,
                          value: String): Observable[StateResult] = {
        addValueAs(namespace, clazz, id, key, value)
    }

    @throws[ServiceUnavailableException]
    @throws[IllegalArgumentException]
    def addValueAs(namespace: String, clazz: Class[_], id: ObjId, key: String,
                   value: String): Observable[StateResult] = {
        assertBuilt()
        classes.get(clazz).addValue(namespace, id, key, value,
                                    getKeyType(clazz, key))
    }

    @throws[ServiceUnavailableException]
    @throws[IllegalArgumentException]
    override def removeValue(clazz: Class[_], id: ObjId, key: String,
                             value: String)
    : Observable[StateResult] = {
        removeValueAs(namespace, clazz, id, key, value)
    }

    @throws[ServiceUnavailableException]
    @throws[IllegalArgumentException]
    def removeValueAs(namespace: String, clazz: Class[_], id: ObjId, key: String,
                      value: String): Observable[StateResult] = {
        assertBuilt()
        classes.get(clazz).removeValue(namespace, id, key, value,
                                       getKeyType(clazz, key))
    }

    @throws[ServiceUnavailableException]
    @throws[IllegalArgumentException]
    override def getKey(clazz: Class[_], id: ObjId, key: String)
    : Observable[StateKey] = {
        getKey(namespace, clazz, id, key)
    }

    @throws[ServiceUnavailableException]
    @throws[IllegalArgumentException]
    override def getKey(namespace: String, clazz: Class[_], id: ObjId, key: String)
    : Observable[StateKey] = {
        assertBuilt()
        classes.get(clazz).getKey(namespace, id, key, getKeyType(clazz, key))
    }

    @throws[ServiceUnavailableException]
    override def keyObservable(clazz: Class[_], id: ObjId, key: String)
    : Observable[StateKey] = {
        keyObservable(namespace, clazz, id, key)
    }

    @throws[ServiceUnavailableException]
    override def keyObservable(namespace: String, clazz: Class[_], id: ObjId,
                               key: String): Observable[StateKey] = {
        assertBuilt()
        classes.get(clazz).keyObservable(namespace, id, key, getKeyType(clazz, key))
    }

    @throws[ServiceUnavailableException]
    override def keyObservable(namespace: Observable[String], clazz: Class[_],
                               id: ObjId, key: String): Observable[StateKey] = {
        assertBuilt()
        val noneObservable =
            if (getKeyType(clazz, key).isSingle)
                Observable.just[StateKey](SingleValueKey(key, None, NoOwnerId))
            else
                Observable.just[StateKey](MultiValueKey(key, Set()))

        Observable.switchOnNext(namespace map makeFunc1 { namespace =>
            if (namespace ne null) keyObservable(namespace, clazz, id, key)
            else noneObservable
        })
    }

    def keyObservableError(namespace: String, clazz: Class[_], id: ObjId,
                           key: String, e: Throwable): Unit = {
        assertBuilt()
        classes.get(clazz).keyObservableError(namespace, id, key, e)
    }

    override def ownerId = DefaultOwnerId
    override def failFastOwnerId = DefaultOwnerId

    val tablesDirectory = new MockDirectory()

    val stateTableClient = new StateTableClient {
        override def stop(): Boolean = { true }

        override def observable(table: StateSubscriptionKey)
        : Observable[Notify.Update] = Observable.never()

        override def connection: Observable[ProxyConnectionState] =
            Observable.never()

        override def start(): Unit = { }
    }

    /**
      * @see [[StateTableStorage.registerTable()]]
      */
    override def registerTable[K, V](clazz: Class[_], key: Class[K],
                                     value: Class[V], name: String,
                                     provider: Class[_ <: StateTable[K,V]])
    : Unit = {
        val newProvider = TableProvider(key, value, provider)
        tableInfo.putIfAbsent(clazz, new TableInfo)
        tableInfo(clazz).tables.getOrElse(name, {
            tableInfo(clazz).tables.putIfAbsent(name, newProvider)
                .getOrElse(newProvider)
        })
    }

    /**
      * @see [[StateTableStorage.getTable()]]
      */
    override def getTable[K, V](clazz: Class[_], id: ObjId, name: String,
                                args: Any*)
                               (implicit key: ClassTag[K], value: ClassTag[V])
    : StateTable[K, V] = {
        val path = "/" + (if (args.nonEmpty) {
            tablePath(clazz, id, name, version.longValue(), args: _*)
        } else {
            tableRootPath(clazz, id, name)
        }).replace('/', '_')

        val provider = getProvider(clazz, key.runtimeClass, value.runtimeClass, name)
        tablesDirectory.ensureHas(path, "".getBytes)
        val directory = tablesDirectory.getSubDirectory(path)
        val metrics = new StorageMetrics(new MetricRegistry)

        val constructor = provider.clazz.getConstructor(classOf[StateTable.Key],
                                                        classOf[Directory],
                                                        classOf[StateTableClient],
                                                        classOf[Observable[ConnectionState]],
                                                        classOf[StorageMetrics])
        val objectId = id match {
            case uuid: UUID => uuid
            case uuid: Commons.UUID => uuid.asJava
            case string: String => UUID.fromString(string)
            case _ => null
        }
        val tableKey =  StateTable.Key(clazz, objectId, key.runtimeClass,
                                       value.runtimeClass, name, args)
        constructor.newInstance(tableKey, directory, stateTableClient,
                                Observable.never(), metrics)
                   .asInstanceOf[StateTable[K, V]]
    }

    /**
      * A getter for global tables
      */
    override def getTable[K, V](name: String)
                               (implicit key: ClassTag[K], value: ClassTag[V])
    : StateTable[K, V] = {
        val path = tablePath(name)

        val provider = getProvider(key.runtimeClass, value.runtimeClass, name)
        tablesDirectory.ensureHas(path, "".getBytes)
        val directory = tablesDirectory.getSubDirectory(path)
        val metrics = new StorageMetrics(new MetricRegistry)

        val constructor = provider.clazz.
            getConstructor(classOf[StateTable.Key],
                           classOf[Directory],
                           classOf[StateTableClient],
                           classOf[Observable[ConnectionState]],
                           classOf[StorageMetrics])
        val tableKey =  StateTable.Key(null, null, key.runtimeClass,
                                       value.runtimeClass, name, Seq())
        constructor.newInstance(tableKey, directory, stateTableClient,
                                Observable.never(), metrics)
            .asInstanceOf[StateTable[K, V]]
    }

    /**
      * @see [[StateTableStorage.tableArguments()]]
      */
    override def tableArguments(clazz: Class[_], id: ObjId, name: String,
                                args: Any*): Future[Set[String]] = {
        val path = "/" + (if (args.nonEmpty) {
            tablePath(clazz, id, name, version.longValue(), args: _*)
        } else {
            tableRootPath(clazz, id, name)
        }).replace('/', '_')

        Future.fromTry(Try {
            tablesDirectory.getChildren(path, null).asScala.toSet
        })
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

    /** Gets the global table provider for the given key and value classes and
      * table name */
    @throws[IllegalArgumentException]
    private def getProvider(key: Class[_], value: Class[_],
                            name:String): TableProvider = {
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

    protected override def pathExists(path: String): Boolean = false
}

object InMemoryStorage {

    final val IOTimeout = 5 seconds
    final val DefaultOwnerId = 1L
    var namespaceId = new UUID(0L, 0L)

    private def copyObj[T](obj: T): T =
        deserialize(serialize(obj.asInstanceOf[Obj]), obj.getClass)

    private def asObservable[T](f: => T)
    : Observable[T] = {
        Observable.create(new OnSubscribe[T] {
            override def call(child: Subscriber[_ >: T]): Unit = {
                try {
                    Observable.just(f).subscribe(child)
                } catch {
                    case NonFatal(e) => child.onError(e)
                }
            }
        })
    }

    private class PrimedSubject[T](f: => T)
        extends OnSubscribe[T] with Observer[T] {

        private val subject = PublishSubject.create[T]
        private var emitting = true
        private var error: Throwable = null

        override def call(child: Subscriber[_ >: T]): Unit = {
            this.synchronized {
                if (emitting) {
                    subject unsafeSubscribe child
                    child onNext f
                } else if (error ne null) {
                    child onError error
                } else {
                    child.onCompleted()
                }
            }
        }

        override def onNext(value: T): Unit = this.synchronized {
            if (emitting) {
                subject onNext value
            }
        }

        override def onCompleted(): Unit = this.synchronized {
            if (emitting) {
                subject.onCompleted()
                emitting = false
            }
        }

        override def onError(e: Throwable) = this.synchronized {
            if (emitting) {
                subject onError e
                emitting = false
                error = e
            }
        }
    }
}

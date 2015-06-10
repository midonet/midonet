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

import java.util.concurrent.Executors.newSingleThreadExecutor
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.concurrent.{ExecutorService, ConcurrentHashMap, ThreadFactory}

import scala.async.Async.async
import scala.collection.JavaConversions._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.ExecutionContext.fromExecutorService
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NonFatal

import org.apache.zookeeper.KeeperException.{BadVersionException, Code}

import rx.Observable.OnSubscribe
import rx._
import rx.schedulers.Schedulers
import rx.subjects.{BehaviorSubject, PublishSubject}

import org.midonet.cluster.data.storage.InMemoryStorage.{asObservable, copyObj, DefaultOwnerId}
import org.midonet.cluster.data.storage.OwnershipType.OwnershipType
import org.midonet.cluster.data.storage.StateStorage.NoOwnerId
import org.midonet.cluster.data.storage.TransactionManager._
import org.midonet.cluster.data.storage.KeyType.KeyType
import org.midonet.cluster.data.storage.ZookeeperObjectMapper._
import org.midonet.cluster.data.{Obj, ObjId}
import org.midonet.cluster.util.ParentDeletedException
import org.midonet.util.concurrent.Locks.{withReadLock, withWriteLock}
import org.midonet.util.concurrent._
import org.midonet.util.functors._

/**
 * A simple in-memory implementation of the [[Storage]] trait, equivalent to
 * the [[ZookeeperObjectMapper]] to use within unit tests.
 */
class InMemoryStorage extends StorageWithOwnership with StateStorage {

    private class ClassNode[T](val clazz: Class[T]) {

        private val instances = new TrieMap[String, InstanceNode[T]]()
        private val stream = PublishSubject.create[Observable[T]]()
        private val obs = stream.observeOn(eventScheduler)

        private val streamUpdates = new mutable.LinkedHashSet[InstanceNode[T]]
        private val instanceUpdates = new mutable.LinkedHashSet[InstanceNode[T]]

        /* Write synchronized by the transaction, call on IO thread */
        @throws[ObjectExistsException]
        @throws[OwnershipConflictException]
        def create(id: ObjId, obj: Obj, ownerOps: Seq[TxOwnerOp]): Unit = {
            assertIoThread()
            val node = new InstanceNode(clazz, id, obj.asInstanceOf[T], ownerOps)
            instances.putIfAbsent(getIdString(clazz, id), node) match {
                case Some(n) => throw new ObjectExistsException(clazz, id)
                case None => streamUpdates += node
            }
        }

        /* Read synchronized by the transaction, call on IO thread */
        @throws[ObjectExistsException]
        @throws[OwnershipConflictException]
        def validateCreate(id: ObjId, ownerOps: Seq[TxOwnerOp]): Unit = {
            assertIoThread()
            if (instances.contains(id.toString))
                throw new ObjectExistsException(clazz, id)
            ownerOps.collectFirst {
                case TxDeleteOwner(owner) =>
                    throw new OwnershipConflictException(
                        clazz.getSimpleName, id.toString, owner)
            }
        }

        /* Write synchronized by the transaction, call on IO thread */
        @throws[NotFoundException]
        @throws[BadVersionException]
        @throws[OwnershipConflictException]
        def update(id: ObjId, obj: Obj, version: Int, ownerOps: Seq[TxOwnerOp])
        : Unit = {
            assertIoThread()
            instances.get(getIdString(clazz, id)) match {
                case Some(node) =>
                    instanceUpdates += node
                    node.update(obj.asInstanceOf[T], version, ownerOps)
                case None => throw new NotFoundException(clazz, id)
            }
        }

        /* Read synchronized by the transaction, call on IO thread */
        @throws[NotFoundException]
        @throws[BadVersionException]
        @throws[OwnershipConflictException]
        def validateUpdate(id: ObjId, version: Int, ownerOps: Seq[TxOwnerOp])
        : Unit = {
            assertIoThread()
            instances.get(getIdString(clazz, id)) match {
                case Some(node) => node.validateUpdate(version, ownerOps)
                case None => throw new NotFoundException(clazz, id)
            }
        }

        /* Write synchronized by the transaction, call on IO thread */
        @throws[NotFoundException]
        @throws[BadVersionException]
        @throws[OwnershipConflictException]
        def delete(id: ObjId, version: Int, ownerOps: Seq[TxOwnerOp]): T = {
            assertIoThread()
            instances.remove(getIdString(clazz, id)) match {
                case Some(node) =>
                    instanceUpdates += node
                    node.delete(version, ownerOps)
                case None => throw new NotFoundException(clazz, id)
            }
        }

        /* Read synchronized by the transaction, call on IO thread */
        @throws[NotFoundException]
        @throws[BadVersionException]
        @throws[OwnershipConflictException]
        def validateDelete(id: ObjId, version: Int, ownerOps: Seq[TxOwnerOp])
        : Unit = {
            assertIoThread()
            instances.get(getIdString(clazz, id)) match {
                case Some(node) => node.validateDelete(version, ownerOps)
                case None => throw new NotFoundException(clazz, id)
            }
        }

        /* Lock free read, synchronous completion */
        def apply(id: ObjId): Option[T] =
            instances.get(getIdString(clazz, id)).map(_.value)

        /* Lock free read, completion on the IO thread */
        def get(id: ObjId): Future[T] = {
            instances.get(getIdString(clazz, id)) match {
                case Some(node) => node.get
                case None => async[T] {
                    assertIoThread()
                    throw new NotFoundException(clazz, id)
                }
            }
        }

        /* Lock free read, synchronous completion */
        def getAll(ids: Seq[_ <: ObjId]): Future[Seq[T]] =
            Future.sequence(ids.map(get))

        /* Lock free read, completion on the implicit exec. ctx.: IO thread */
        def getAll: Future[Seq[T]] = {
            Future.sequence(instances.values.map(_.get).to[Seq])
        }
        /* Lock free read, completion on the implicit exec. ctx.: IO thread */
        def getOwners(id: ObjId): Future[Set[String]] = {
            instances.get(getIdString(clazz, id)) match {
                case Some(node) => node.getOwners
                case None => async {
                    assertIoThread()
                    throw new NotFoundException(clazz, id)
                }
            }
        }

        /* Uses read lock, synchronous completion */
        def getSnapshot(id: ObjId): ObjSnapshot = {
            instances.getOrElse(getIdString(clazz, id),
                                throw new NotFoundException(clazz, id))
                     .getSnapshot
        }

        /* Lock free read, completion on the IO thread */
        def exists(id: ObjId): Future[Boolean] = async {
            assertIoThread()
            instances.containsKey(getIdString(clazz, id))
        }

        /* Lock free read, synchronous completion */
        def observable(id: ObjId): Observable[T] = {
            instances.get(getIdString(clazz, id)) match {
                case Some(node) => node.observable
                case None => Observable.error(new NotFoundException(clazz, id))
                                       .observeOn(eventScheduler)
            }
        }

        /* Read lock while emitting current objects, synchronous completion */
        def observable: Observable[Observable[T]] = Observable.create(
            new OnSubscribe[Observable[T]] {
                override def call(sub: Subscriber[_ >: Observable[T]]): Unit =
                    async(readLock {
                        instances.values.foreach { i => sub.onNext(i.observable) }
                        sub.add(obs.unsafeSubscribe(sub))
                    })(eventExecutionContext)
            }
        )

        /* Lock free read, synchronous completion */
        def ownersObservable(id: ObjId): Observable[Set[String]] = {
            instances.get(getIdString(clazz, id)) match {
                case Some(node) => node.ownersObservable
                case None => Observable.error(new ParentDeletedException(
                    s"${clazz.getSimpleName}/${id.toString}"))
                                       .observeOn(eventScheduler)
            }
        }

        /* Emits the updates generated during the last transaction */
        def emitUpdates(): Unit = {
            for (node <- streamUpdates) {
                stream.onNext(node.observable)
            }
            for (node <- instanceUpdates) {
                node.emitUpdates()
            }
            streamUpdates.clear()
            instanceUpdates.clear()
        }

        /* Object state is not synchronized. */
        def addValue(id: ObjId, key: String, value: String, keyType: KeyType)
        : Observable[StateResult] = {
            val idStr = getIdString(clazz, id)
            instances.get(idStr) match {
                case Some(instance) => instance.addValue(key, value, keyType)
                case None => asObservable {
                    assertIoThread()
                    throw new UnmodifiableStateException(
                        clazz.getSimpleName, idStr, key, value,
                        Code.NONODE.intValue)
                }
            }
        }

        /* Object state is not synchronized. */
        def removeValue(id: ObjId, key: String, value: String,
                        keyType: KeyType): Observable[StateResult] = {
            val idStr = getIdString(clazz, id)
            instances.get(idStr) match {
                case Some(instance) => instance.removeValue(key, value, keyType)
                case None => asObservable {
                    assertIoThread(); StateResult(NoOwnerId)
                }
            }
        }

        /* Object state is not synchronized. */
        def getKey(id: ObjId, key: String, keyType: KeyType)
        : Observable[StateKey] = {
            val idStr = getIdString(clazz, id)
            instances.get(idStr) match {
                case Some(instance) => instance.getKey(key, keyType)
                case None if keyType.isSingle =>
                    asObservable { SingleValueKey(key, None, NoOwnerId) }
                case None =>
                    asObservable { MultiValueKey(key, Set()) }
            }
        }

        def keyObservable(id: ObjId, key: String, keyType: KeyType)
        : Observable[StateKey] = {
            val idStr = getIdString(clazz, id)
            instances.get(idStr) match {
                case Some(instance) => instance.keyObservable(key, keyType)
                case None if keyType.isSingle =>
                    Observable.empty().observeOn(eventScheduler)
                case None =>
                    Observable.error(new ParentDeletedException(s"$clazz/$id"))
                        .observeOn(eventScheduler)
            }
        }

    }

    private class InstanceNode[T](clazz: Class[T], id: ObjId, obj: T,
                                  initOwnerOps: Seq[TxOwnerOp]) {

        private val ref = new AtomicReference[T](copyObj(obj))
        private val ver = new AtomicInteger
        private val owners = new TrieMap[String, Unit]
        private val keys = new TrieMap[String, KeyNode]

        private val streamInstance = BehaviorSubject.create[T](value)
        private val streamOwners =
            BehaviorSubject.create[Set[String]](Set.empty[String])
        private val obsInstance = streamInstance.observeOn(eventScheduler)
        private val obsOwners = streamOwners.observeOn(eventScheduler)

        private var instanceUpdate: Notification[T] = null
        private var ownersUpdate: Notification[Set[String]] = null

        updateOwners(initOwnerOps)
        emitUpdates()

        def value = ref.get
        def version = ver.get

        /* Write synchronized by transaction */
        @throws[BadVersionException]
        def update(value: T, version: Int, ownerOps: Seq[TxOwnerOp]): Unit = {
            assertIoThread()
            if (!ver.compareAndSet(version, version + 1))
                throw new BadVersionException
            val newValue = copyObj(value)
            ref.set(newValue)
            instanceUpdate = Notification.createOnNext(newValue)
            updateOwners(ownerOps)
        }

        /* Read synchronized by transaction */
        @throws[BadVersionException]
        @throws[OwnershipConflictException]
        def validateUpdate(version: Int, ownerOps: Seq[TxOwnerOp]): Unit = {
            assertIoThread()
            if (ver.get != version)
                throw new BadVersionException

            val set = mutable.Set[String](owners.keySet.toSeq:_*)
            for (op <- ownerOps) op match {
                case TxCreateOwner(o) if set.contains(o) =>
                    throw new OwnershipConflictException(clazz.toString,
                                                         id.toString, o)
                case TxCreateOwner(o) => set += o
                case TxDeleteOwner(o) if !set.contains(o) =>
                    throw new OwnershipConflictException(clazz.toString,
                                                         id.toString, o)
                case TxDeleteOwner(o) => set -= o
            }
        }

        /* Write synchronized by transaction */
        @throws[BadVersionException]
        def delete(version: Int, ownerOps: Seq[TxOwnerOp]): T = {
            assertIoThread()
            if (!ver.compareAndSet(version, version + 1))
                throw new BadVersionException

            for (keyNode <- keys.values) {
                keyNode.complete()
            }

            updateOwners(ownerOps)
            instanceUpdate = Notification.createOnCompleted()
            ownersUpdate = Notification.createOnError(new ParentDeletedException(
                s"${clazz.getSimpleName}/${id.toString}"))
            value
        }

        /* Read synchronized by transaction */
        @throws[BadVersionException]
        @throws[OwnershipConflictException]
        def validateDelete(version: Int, ownerOps: Seq[TxOwnerOp]): Unit = {
            assertIoThread()
            if (ver.get != version)
                throw new BadVersionException
            val delOwners = ownerOps
                .collect{ case op: TxDeleteOwner => op.owner }.toSet
            val delDiff = delOwners.diff(owners.keySet)
            if (delDiff.nonEmpty) {
                throw new OwnershipConflictException(
                    clazz.toString, id.toString, delDiff.head)
            }
        }

        def observable = obsInstance

        def ownersObservable = obsOwners

        /* Lock free read, completion on the IO thread */
        def get: Future[T] = async { assertIoThread(); value }

        /* Lock free read, completion on the IO thread */
        def getOwners: Future[Set[String]] = async {
            assertIoThread()
            owners.keySet.toSet
        }

        /* Requires read lock, synchronous completion */
        def getSnapshot = readLock {
            ObjSnapshot(value.asInstanceOf[Obj], version, owners.keySet.toSet)
        }

        /* Emits the updates generated during the last transaction */
        def emitUpdates(): Unit = {
            if (instanceUpdate ne null) {
                instanceUpdate.accept(streamInstance)
                instanceUpdate = null
            }
            if (ownersUpdate ne null) {
                ownersUpdate.accept(streamOwners)
                ownersUpdate = null
            }
        }

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

        /* Write synchronized by transaction */
        private def updateOwners(ops: Seq[TxOwnerOp]): Unit = {
            assertIoThread()
            if (ops.nonEmpty) {
                for (op <- ops) op match {
                    case TxCreateOwner(o) if owners.contains(o) =>
                        throw new OwnershipConflictException(
                            clazz.toString, id.toString, o)
                    case TxCreateOwner(o) => owners += o -> {}
                    case TxDeleteOwner(o) if !owners.contains(o) =>
                        throw new OwnershipConflictException(
                            clazz.toString, id.toString, o)
                    case TxDeleteOwner(o) => owners -= o
                }
                ownersUpdate = Notification.createOnNext(owners.keySet.toSet)
            }
        }

        private def getOrCreateKeyNode(key: String, keyType: KeyType)
        : KeyNode = {
            keys.getOrElse(key, {
                val node = new KeyNode(clazz.getSimpleName,
                                       getIdString(clazz, id), key, keyType)
                keys.putIfAbsent(key, node).getOrElse(node)
            })
        }

    }

    private class KeyNode(clazz: String, id: String, key: String,
                          keyType: KeyType) {

        private val values = new TrieMap[String, String]
        private val subject =
            BehaviorSubject.create[Map[String, String]](Map[String, String]())

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
                subject.map[StateKey](makeFunc1(map => {
                    map.headOption match {
                        case Some((_, value)) =>
                            SingleValueKey(key, Some(value), DefaultOwnerId)
                        case None =>
                            SingleValueKey(key, None, NoOwnerId)
                    }
                })).observeOn(eventScheduler)
            } else {
                subject.map[StateKey](makeFunc1(map => {
                    MultiValueKey(key, map.keySet)
                }))
            }
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

        override def getSnapshot(clazz: Class[_], id: ObjId): ObjSnapshot = {
            classes(clazz).getSnapshot(id)
        }

        /* Write lock, execution on the IO thread */
        override def commit(): Unit = async[Unit](writeLock {
            // This write lock is used to synchronize atomic writes with
            // concurrent reads of object snapshots, which cannot be lock-free.

            // Validate the transaction ops.
            for ((key, op) <- ops) {
                val clazz = classes(key.clazz)
                op match {
                    case TxCreate(obj, ownerOps) =>
                        clazz.validateCreate(key.id, ownerOps)
                    case TxUpdate(obj, ver, ownerOps) =>
                        clazz.validateUpdate(key.id, ver, ownerOps)
                    case TxDelete(ver, ownerOps) =>
                        clazz.validateDelete(key.id, ver, ownerOps)
                    case _ => throw new NotImplementedError(op.toString)
                }
            }

            // Apply the transaction ops.
            for ((key, op) <- ops) {
                val clazz = classes(key.clazz)
                op match {
                    case TxCreate(obj, ownerOps) =>
                        clazz.create(key.id, obj, ownerOps)
                    case TxUpdate(obj, ver, ownerOps) =>
                        clazz.update(key.id, obj, ver, ownerOps)
                    case TxDelete(ver, ownerOps) =>
                        clazz.delete(key.id, ver, ownerOps)
                    case _ => throw new NotImplementedError(op.toString)
                }
            }

            // Emit notifications.
            for ((key, op) <- ops) {
                classes(key.clazz).emitUpdates()
            }
        }).await(InMemoryStorage.IOTimeout)

        /** Query the backend store to determine if a node exists at the
          * specified path. */
        override protected def nodeExists(path: String): Boolean =
            throw new NotImplementedError()

        /** Query the backend store to get the fully-qualified paths of all
          * children of the specified node. */
        override protected def childrenOf(path: String): Seq[String] =
            throw new NotImplementedError()
    }

    @volatile private var ioThreadId: Long = -1L
    @volatile private var eventThreadId: Long = -1L
    private val ioExecutor = newSingleThreadExecutor(
        new ThreadFactory {
            override def newThread(r: Runnable): Thread = {
                val thread = new Thread(r, "storage-io")
                ioThreadId = thread.getId
                thread
            }
        })
    private val eventExecutor = newSingleThreadExecutor(
        new ThreadFactory {
            override def newThread(r: Runnable): Thread = {
                val thread = new Thread(r, "storage-event")
                eventThreadId = thread.getId
                thread
            }
        })
    private implicit val ioExecutionContext = fromExecutorService(ioExecutor)
    private val eventExecutionContext = fromExecutorService(eventExecutor)
    private val eventScheduler = Schedulers.from(eventExecutor)

    private val classes = new ConcurrentHashMap[Class[_], ClassNode[_]]
    private val lock = new ReentrantReadWriteLock()

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

    override def getOwners(clazz: Class[_], id: ObjId): Future[Set[String]] = {
        assertBuilt()
        assert(isRegistered(clazz))

        classes.get(clazz).getOwners(id)
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
            case CreateOp(obj) => manager.create(obj)
            case CreateWithOwnerOp(obj, owner) => manager.create(obj, owner)
            case UpdateOp(obj, validator) => manager.update(obj, validator)
            case UpdateWithOwnerOp(obj, owner, validator) =>
                manager.update(obj, owner, validator)
            case UpdateOwnerOp(clazz, id, owner, throwIfExists) =>
                manager.updateOwner(clazz, id, owner, throwIfExists)
            case DeleteOp(clazz, id, ignoresNeo) =>
                manager.delete(clazz, id, ignoresNeo, None)
            case DeleteWithOwnerOp(clazz, id, owner) =>
                manager.delete(clazz, id, ignoresNeo = false, Some(owner))
            case DeleteOwnerOp(clazz, id, owner) =>
                manager.deleteOwner(clazz, id, owner)
            case op => throw new NotImplementedError(op.toString)
        }

        manager.commit()
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

    override def ownersObservable(clazz: Class[_], id: ObjId)
    : Observable[Set[String]] = {
        assertBuilt()
        assert(isRegistered(clazz))
        classes.get(clazz).ownersObservable(id)
    }

    override def registerClass(clazz: Class[_]): Unit = {
        registerClassInternal(clazz.asInstanceOf[Class[_ <: Obj]],
                              OwnershipType.Shared)
    }

    override def registerClass(clazz: Class[_], ownershipType: OwnershipType)
    : Unit = {
        registerClassInternal(clazz.asInstanceOf[Class[_ <: Obj]],
                              ownershipType)
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
        assertBuilt()
        classes.get(clazz).addValue(id, key, value, getKeyType(clazz, key))
    }

    @throws[ServiceUnavailableException]
    @throws[IllegalArgumentException]
    override def removeValue(clazz: Class[_], id: ObjId, key: String,
                             value: String): Observable[StateResult] = {
        assertBuilt()
        classes.get(clazz).removeValue(id, key, value, getKeyType(clazz, key))
    }

    @throws[ServiceUnavailableException]
    @throws[IllegalArgumentException]
    override def getKey(clazz: Class[_], id: ObjId, key: String)
    : Observable[StateKey] = {
        assertBuilt()
        classes.get(clazz).getKey(id, key, getKeyType(clazz, key))
    }

    @throws[ServiceUnavailableException]
    override def keyObservable(clazz: Class[_], id: ObjId, key: String)
    : Observable[StateKey] = {
        assertBuilt()
        classes.get(clazz).keyObservable(id, key, getKeyType(clazz, key))
    }

    override def sessionId = DefaultOwnerId

    private def registerClassInternal(clazz: Class[_ <: Obj],
                                      ownershipType: OwnershipType): Unit = {
        classes.putIfAbsent(clazz, new ClassNode(clazz)) match {
            case c: ClassNode[_] => throw new IllegalStateException(
                s"Class $clazz is already registered.")
            case _ =>
        }

        classInfo += clazz -> makeInfo(clazz, ownershipType)
    }

    @inline
    private[storage] def assertIoThread(): Unit = {
        assert(ioThreadId == Thread.currentThread.getId)
    }

    @inline
    private[storage] def assertEventThread(): Unit = {
        assert(eventThreadId == Thread.currentThread.getId)
    }

    private def readLock[T](fn: => T) = withReadLock[T](lock)(fn)
    private def writeLock[T](fn: => T) = withWriteLock[T](lock)(fn)
}

object InMemoryStorage {

    final val IOTimeout = 5 seconds
    final val DefaultOwnerId = 1L

    private def copyObj[T](obj: T): T =
        deserialize(serialize(obj.asInstanceOf[Obj]), obj.getClass)

    private def asObservable[T](f: => T)(implicit ec: ExecutorService)
    : Observable[T] = {
        Observable.create(new OnSubscribe[T] {
            override def call(child: Subscriber[_ >: T]): Unit = {
                ec.submit(makeRunnable {
                    try {
                        Observable.just(f).subscribe(child)
                    } catch {
                        case NonFatal(e) => child.onError(e)
                    }
                })
            }
        })
    }
}

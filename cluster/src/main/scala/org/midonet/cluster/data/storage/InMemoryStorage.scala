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

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.concurrent.{ThreadFactory, Executors, ConcurrentHashMap}
import java.util.{List => JList}

import scala.async.Async.async
import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

import com.google.common.collect.ArrayListMultimap
import com.google.protobuf.Message
import org.apache.zookeeper.KeeperException.BadVersionException

import rx.Observable.OnSubscribe
import rx._
import rx.schedulers.Schedulers
import rx.subjects.{BehaviorSubject, PublishSubject}

import org.midonet.cluster.data.storage.FieldBinding.DeleteAction
import org.midonet.cluster.data.storage.InMemoryStorage.copyObj
import org.midonet.cluster.data.storage.OwnershipType.OwnershipType
import org.midonet.cluster.data.storage.TransactionManager._
import org.midonet.cluster.data.storage.ZookeeperObjectMapper._
import org.midonet.cluster.data.{Obj, ObjId}
import org.midonet.cluster.util.ParentDeletedException
import org.midonet.util.concurrent._
import org.midonet.util.concurrent.Locks.{withReadLock, withWriteLock}

/**
 * A simple in-memory implementation of the [[Storage]] trait, equivalent to
 * the [[ZookeeperObjectMapper]] to use within unit tests.
 */
class InMemoryStorage extends StorageWithOwnership {

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
        def getAll(ids: Seq[_ <: ObjId]): Seq[Future[T]] = ids.map(get)

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

        /** Lock free read, synchronous completion */
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
    }

    private class InstanceNode[T](val clazz: Class[T], id: ObjId, obj: T,
                                  initOwnerOps: Seq[TxOwnerOp]) {

        private val ref = new AtomicReference[T](copyObj(obj))
        private val ver = new AtomicInteger
        private val owners = new TrieMap[String, Unit]

        private val streamInstance = BehaviorSubject.create[T](value)
        private val streamOwners =
            BehaviorSubject.create[Set[String]](Set.empty[String])
        private val obsInstance = streamInstance.observeOn(eventScheduler)
        private val obsOwners = streamOwners.observeOn(eventScheduler)

        private var instanceUpdate: Notification[T] = null
        private var ownersUpdate: Notification[Set[String]] = null

        updateOwners(initOwnerOps)

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
                    throw new OwnershipConflictException(
                        clazz.toString, id.toString, o)
                case TxCreateOwner(o) => set += o
                case TxDeleteOwner(o) if !set.contains(o) =>
                    throw new OwnershipConflictException(
                        clazz.toString, id.toString, o)
                case TxDeleteOwner(o) => set -= o
            }
        }

        /* Write synchronized by transaction */
        @throws[BadVersionException]
        def delete(version: Int, ownerOps: Seq[TxOwnerOp]): T = {
            assertIoThread()
            if (!ver.compareAndSet(version, version + 1))
                throw new BadVersionException

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
                streamOwners.onNext(owners.keySet.toSet)
            }
        }
    }

    private class InMemoryTransactionManager
            extends TransactionManager(classInfo.toMap, bindings) {

        override def isRegistered(clazz: Class[_]): Boolean = {
            InMemoryStorage.this.isRegistered(clazz)
        }

        override def getSnapshot(clazz: Class[_], id: ObjId): ObjSnapshot = {
            classes(clazz).getSnapshot(id)
        }

        /* Write lock, execution on the IO thread */
        override def commit(): Unit =  async[Unit](writeLock {
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
                }
            }

            // Emit notifications.
            for ((key, op) <- ops) {
                classes(key.clazz).emitUpdates()
            }
        }).await(InMemoryStorage.IOTimeout)
    }

    @volatile private var ioThreadId: Long = -1L
    @volatile private var eventThreadId: Long = -1L
    private val ioExecutor = Executors.newSingleThreadExecutor(
        new ThreadFactory {
            override def newThread(r: Runnable): Thread = {
                val thread = new Thread(r, "storage-io")
                ioThreadId = thread.getId
                thread
            }
        })
    private val eventExecutor = Executors.newSingleThreadExecutor(
        new ThreadFactory {
            override def newThread(r: Runnable): Thread = {
                val thread = new Thread(r, "storage-event")
                eventThreadId = thread.getId
                thread
            }
        })
    private implicit val ioExecutionContext =
        ExecutionContext.fromExecutorService(ioExecutor)
    private val eventExecutionContext =
        ExecutionContext.fromExecutorService(eventExecutor)
    private val eventScheduler = Schedulers.from(eventExecutor)

    @volatile private var built = false
    private val classes = new ConcurrentHashMap[Class[_], ClassNode[_]]
    private val bindings = ArrayListMultimap.create[Class[_], FieldBinding]()
    private val lock = new ReentrantReadWriteLock()

    private val classInfo = new mutable.HashMap[Class[_], ClassInfo]()

    override def get[T](clazz: Class[T], id: ObjId): Future[T] = {
        assertBuilt()
        assert(isRegistered(clazz))

        classes.get(clazz).asInstanceOf[ClassNode[T]].get(id)
    }

    override def getAll[T](clazz: Class[T],
                           ids: Seq[_ <: ObjId]): Seq[Future[T]] = {
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
        }

        manager.commit()
    }

    override def multi(ops: JList[PersistenceOp]): Unit = multi(ops.asScala)

    override def flush(): Unit = throw new UnsupportedOperationException

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

    override def declareBinding(leftClass: Class[_], leftFieldName: String,
                                onDeleteLeft: DeleteAction,
                                rightClass: Class[_], rightFieldName: String,
                                onDeleteRight: DeleteAction): Unit = {
        assert(!built)
        assert(isRegistered(leftClass))
        assert(isRegistered(rightClass))

        val leftIsMessage = classOf[Message].isAssignableFrom(leftClass)
        val rightIsMessage = classOf[Message].isAssignableFrom(rightClass)
        if (leftIsMessage != rightIsMessage) {
            throw new IllegalArgumentException("Incompatible types")
        }

        val bdgs = if (leftIsMessage) {
            ProtoFieldBinding.createBindings(
                leftClass, leftFieldName, onDeleteLeft,
                rightClass, rightFieldName, onDeleteRight)
        } else {
            PojoFieldBinding.createBindings(
                leftClass, leftFieldName, onDeleteLeft,
                rightClass, rightFieldName, onDeleteRight)
        }

        for (entry <- bdgs.entries.asScala) {
            bindings.put(entry.getKey, entry.getValue)
        }
    }

    override def build(): Unit = {
        assert(!built)
        built = true
    }

    override def isBuilt = built

    private def assertBuilt() {
        if (!built) throw new ServiceUnavailableException(
            "Data operation received before call to build().")
    }

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

    private def copyObj[T](obj: T): T =
        deserialize(serialize(obj.asInstanceOf[Obj]), obj.getClass)
}

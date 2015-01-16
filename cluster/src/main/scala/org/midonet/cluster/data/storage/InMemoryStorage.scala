/*
 * Copyright 2014 Midokura SARL
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
import java.util.concurrent.{ConcurrentHashMap, TimeUnit}
import java.util.{List => JList}

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.{Future, Promise}

import com.google.common.collect.ArrayListMultimap
import com.google.inject.Inject
import com.google.protobuf.Message
import org.apache.zookeeper.KeeperException.BadVersionException

import rx.Observable.OnSubscribe
import rx.Scheduler.Worker
import rx._
import rx.functions.Action0
import rx.subjects.{BehaviorSubject, PublishSubject}

import org.midonet.cluster.data.storage.FieldBinding.DeleteAction
import org.midonet.cluster.data.storage.InMemoryStorage.copyObj
import org.midonet.cluster.data.storage.OwnershipType.OwnershipType
import org.midonet.cluster.data.storage.TransactionManager._
import org.midonet.cluster.data.storage.ZookeeperObjectMapper._
import org.midonet.cluster.data.{Obj, ObjId}
import org.midonet.cluster.util.ParentDeletedException
import org.midonet.util.concurrent.Locks.{withReadLock, withWriteLock}
import org.midonet.util.eventloop.Reactor

/**
 * A simple in-memory implementation of the [[Storage]] trait, equivalent to
 * the [[ZookeeperObjectMapper]] to use within unit tests.
 */
@Inject
class InMemoryStorage(reactor: Reactor) extends StorageWithOwnership {

    private class ReactorScheduler extends Scheduler {
        private val worker = new ReactorWorker()

        override def createWorker = worker
    }

    private class ReactorWorker extends Worker {
        override def schedule(action: Action0): Subscription = {
            val runnable = new ReactorRunnable(action)
            reactor.submit(runnable)
            runnable
        }
        override def schedule(action: Action0, delay: Long,
                              unit: TimeUnit): Subscription = {
            val runnable = new ReactorRunnable(action)
            reactor.schedule(runnable, delay, unit)
            runnable
        }
        override def unsubscribe(): Unit = { }
        override def isUnsubscribed: Boolean = false
    }

    private class ReactorRunnable(action: Action0) extends Runnable
                                                   with Subscription {
        @volatile var unsubscribed: Boolean = false
        override def run(): Unit = if (!unsubscribed) {
            action.call()
        }
        override def unsubscribe(): Unit = {
            unsubscribed = true
        }
        override def isUnsubscribed: Boolean = unsubscribed
    }

    private class ClassNode[T](val clazz: Class[T]) {

        private val instances = new TrieMap[String, InstanceNode[T]]()
        private val stream = PublishSubject.create[Observable[T]]()

        /** Write synchronized by transaction */
        @throws[ObjectExistsException]
        @throws[OwnershipConflictException]
        def create(id: ObjId, obj: Obj, ownerOps: Seq[TxOwnerOp]): Unit = {
            val node = new InstanceNode(clazz, id, obj.asInstanceOf[T], ownerOps)
            instances.putIfAbsent(getIdString(clazz, id), node) match {
                case Some(n) => throw new ObjectExistsException(clazz, id)
                case None => stream.onNext(node.observable)
            }
        }

        /** Read synchronized by transaction */
        @throws[ObjectExistsException]
        @throws[OwnershipConflictException]
        def validateCreate(id: ObjId, ownerOps: Seq[TxOwnerOp]): Unit = {
            if(instances.contains(id.toString))
                throw new ObjectExistsException(clazz, id)
            ownerOps.collectFirst {
                case TxDeleteOwner(owner) =>
                    throw new OwnershipConflictException(
                        clazz.getSimpleName, id.toString, owner)
            }
        }

        /** Write synchronized by transaction */
        @throws[NotFoundException]
        @throws[BadVersionException]
        @throws[OwnershipConflictException]
        def update(id: ObjId, obj: Obj, version: Int, ownerOps: Seq[TxOwnerOp])
        : Unit = {
            instances.get(getIdString(clazz, id)) match {
                case Some(node) =>
                    node.update(obj.asInstanceOf[T], version, ownerOps)
                case None => throw new NotFoundException(clazz, id)
            }
        }

        /** Read synchronized by transaction */
        @throws[NotFoundException]
        @throws[BadVersionException]
        @throws[OwnershipConflictException]
        def validateUpdate(id: ObjId, version: Int, ownerOps: Seq[TxOwnerOp])
        : Unit = {
            instances.get(getIdString(clazz, id)) match {
                case Some(node) => node.validateUpdate(version, ownerOps)
                case None => throw new NotFoundException(clazz, id)
            }
        }

        /** Write synchronized by transaction */
        @throws[NotFoundException]
        @throws[BadVersionException]
        @throws[OwnershipConflictException]
        def delete(id: ObjId, version: Int, ownerOps: Seq[TxOwnerOp]): T = {
            instances.remove(getIdString(clazz, id)) match {
                case Some(node) => node.delete(version, ownerOps)
                case None => throw new NotFoundException(clazz, id)
            }
        }

        /** Read synchronized by transaction */
        @throws[NotFoundException]
        @throws[BadVersionException]
        @throws[OwnershipConflictException]
        def validateDelete(id: ObjId, version: Int, ownerOps: Seq[TxOwnerOp])
        : Unit = {
            instances.get(getIdString(clazz, id)) match {
                case Some(node) => node.validateDelete(version, ownerOps)
                case None => throw new NotFoundException(clazz, id)
            }
        }

        /** Lock free read */
        def apply(id: ObjId): Option[T] =
            instances.get(getIdString(clazz, id)).map(_.value)

        /** Lock free read */
        def get(id: ObjId): Future[T] = {
            instances.get(getIdString(clazz, id)) match {
                case Some(node) => node.get
                case None => Promise[T]()
                    .failure(new NotFoundException(clazz, id))
                    .future
            }
        }

        /** Lock free read */
        def getAll(ids: Seq[_ <: ObjId]): Seq[Future[T]] = ids.map(get)

        /** Lock free read */
        def getAll: Future[Seq[Future[T]]] =
            Promise().success(instances.values.map(_.get).to[Seq]).future

        /** Lock free read */
        def getOwners(id: ObjId): Future[Set[String]] = {
            instances.get(getIdString(clazz, id)) match {
                case Some(node) => node.getOwners
                case None => Promise[Set[String]]()
                    .failure(new NotFoundException(clazz, id))
                    .future
            }
        }

        /** Lock free read */
        def getSnapshot(id: ObjId): ObjSnapshot = {
            instances.getOrElse(getIdString(clazz, id),
                                throw new NotFoundException(clazz, id))
                .getSnapshot
        }

        /** Lock free read */
        def exists(id: ObjId): Future[Boolean] =
            Promise().success(instances.containsKey(getIdString(clazz, id)))
                     .future

        /** Lock free read */
        def observable(id: ObjId): Observable[T] = {
            instances.get(getIdString(clazz, id)) match {
                case Some(node) => node.observable
                case None => Observable.error(new NotFoundException(clazz, id))
            }
        }

        /** Read lock while emitting current objects */
        def observable: Observable[Observable[T]] = Observable.create(
            new OnSubscribe[Observable[T]] {
                override def call(sub: Subscriber[_ >: Observable[T]]): Unit =
                    rl {
                        instances.values.foreach { i => sub.onNext(i.observable) }
                        sub.add(stream unsafeSubscribe sub)
                    }
            }
        )

        /** Lock free read */
        def ownersObservable(id: ObjId): Observable[Set[String]] = {
            instances.get(getIdString(clazz, id)) match {
                case Some(node) => node.ownersObservable
                case None => Observable.error(new ParentDeletedException(
                    s"${clazz.getSimpleName}/${id.toString}"))
            }
        }
    }

    private class InstanceNode[T](val clazz: Class[T], id: ObjId, obj: T,
                                  initOwnerOps: Seq[TxOwnerOp])
            extends Observer[T] {

        private val ref = new AtomicReference[T](copyObj(obj))
        private val ver = new AtomicInteger
        private val owners = new TrieMap[String, Unit]

        private val streamInstance = BehaviorSubject.create[T](ref.get)
        private val streamOwners =
            BehaviorSubject.create[Set[String]](Set.empty[String])
        private val obsInstance = streamInstance.subscribeOn(scheduler)
        private val obsOwners = streamOwners.subscribeOn(scheduler)

        updateOwners(initOwnerOps)
        obsInstance.subscribe(this)

        def value = ref.get
        def version = ver.get

        /** Write synchronized by transaction */
        @throws[BadVersionException]
        def update(value: T, version: Int, ownerOps: Seq[TxOwnerOp]): Unit = {
            if (!ver.compareAndSet(version, version + 1))
                throw new BadVersionException
            streamInstance.onNext(copyObj(value))
            updateOwners(ownerOps)
        }

        /** Read synchronized by transaction */
        @throws[BadVersionException]
        @throws[OwnershipConflictException]
        def validateUpdate(version: Int, ownerOps: Seq[TxOwnerOp]): Unit = {
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

        /** Write synchronized by transaction */
        @throws[BadVersionException]
        def delete(version: Int, ownerOps: Seq[TxOwnerOp]): T = {
            if (!ver.compareAndSet(version, version + 1))
                throw new BadVersionException

            streamInstance.onCompleted()
            streamOwners.onError(new ParentDeletedException(
                s"${clazz.getSimpleName}/${id.toString}"))
            ref.get()
        }

        /** Read synchronized by transaction */
        @throws[BadVersionException]
        @throws[OwnershipConflictException]
        def validateDelete(version: Int, ownerOps: Seq[TxOwnerOp]): Unit = {
            if (ver.get != version)
                throw new BadVersionException
            val delDiff = ownerOps
                .filter(_.getClass == classOf[TxDeleteOwner])
                .map(_.owner).toSet.diff(owners.keySet)
            if (delDiff.nonEmpty)
                throw new OwnershipConflictException(
                    clazz.toString, id.toString, delDiff.head)
        }

        def observable = obsInstance

        def ownersObservable = obsOwners

        /** Lock free read */
        def get: Future[T] = Promise[T]().success(ref.get).future

        /** Lock free read */
        def getOwners: Future[Set[String]] =
            Promise[Set[String]]().success(owners.keySet.toSet).future

        /** Requires read lock */
        def getSnapshot = rl {
            ObjSnapshot(ref.get.asInstanceOf[Obj], ver.get, owners.keySet.toSet)
        }

        override def onCompleted(): Unit = { }
        override def onError(e: Throwable): Unit = { }
        override def onNext(value: T): Unit = ref.set(value)

        /** Write synchronized by transaction */
        private def updateOwners(ops: Seq[TxOwnerOp]): Unit = {
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

        override def commit(): Unit = wl {

            // Validate the transaction ops.
            for (op <- ops) op._2 match {
                case TxCreate(obj, ownerOps) =>
                    classes(op._1.clazz).validateCreate(op._1.id, ownerOps)
                case TxUpdate(obj, ver, ownerOps) =>
                    classes(op._1.clazz).validateUpdate(op._1.id, ver, ownerOps)
                case TxDelete(ver, ownerOps) =>
                    classes(op._1.clazz).validateDelete(op._1.id, ver, ownerOps)
            }

            // Apply the transaction ops.
            for (op <- ops) op._2 match {
                case TxCreate(obj, ownerOps) =>
                    classes(op._1.clazz).create(op._1.id, obj, ownerOps)
                case TxUpdate(obj, ver, ownerOps) =>
                    classes(op._1.clazz).update(op._1.id, obj, ver, ownerOps)
                case TxDelete(ver, ownerOps) =>
                    classes(op._1.clazz).delete(op._1.id, ver, ownerOps)
            }
        }
    }

    @volatile private var built = false
    private val scheduler = new ReactorScheduler()
    private val classes = new ConcurrentHashMap[Class[_], ClassNode[_]]
    private val bindings = ArrayListMultimap.create[Class[_], FieldBinding]()
    private val lock = new ReentrantReadWriteLock()

    private val classInfo = new mutable.HashMap[Class[_], ClassInfo]()

    override def create(obj: Obj): Unit = multi(List(CreateOp(obj)))

    override def create(obj: Obj, owner: ObjId): Unit =
        multi(List(CreateWithOwnerOp(obj, owner.toString)))

    override def update(obj: Obj): Unit = multi(List(UpdateOp(obj)))

    override def update[T <: Obj](obj: T, validator: UpdateValidator[T]): Unit =
        multi(List(UpdateOp(obj, validator)))

    override def update[T <: Obj](obj: T, owner: ObjId, overwriteOwner: Boolean,
                                     validator: UpdateValidator[T]): Unit =
        multi(List(UpdateWithOwnerOp(obj, owner.toString, overwriteOwner,
                                     validator)))

    override def updateOwner(clazz: Class[_], id: ObjId, owner: ObjId,
                             overwriteOwner: Boolean): Unit =
        multi(List(UpdateOwnerOp(clazz, id, owner.toString, overwriteOwner)))

    override def delete(clazz: Class[_], id: ObjId): Unit =
        multi(List(DeleteOp(clazz, id)))

    override def delete(clazz: Class[_], id: ObjId, owner: ObjId): Unit =
        multi(List(DeleteWithOwnerOp(clazz, id, owner.toString)))

    override def deleteIfExists(clazz: Class[_], id: ObjId): Unit =
        multi(List(DeleteOp(clazz, id, ignoreIfNotExists = true)))

    override def deleteOwner(clazz: Class[_], id: ObjId, owner: ObjId): Unit =
        multi(List(DeleteOwnerOp(clazz, id, owner.toString)))

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

    override def getAll[T](clazz: Class[T]): Future[Seq[Future[T]]] = {
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
            case UpdateWithOwnerOp(obj, owner, overwrite, validator) =>
                manager.update(obj, owner, overwrite, validator)
            case UpdateOwnerOp(clazz, id, owner, overwrite) =>
                manager.updateOwner(clazz, id, owner, overwrite)
            case DeleteOp(clazz, id, ignores) =>
                manager.delete(clazz, id, ignores, None)
            case DeleteWithOwnerOp(clazz, id, owner) =>
                manager.delete(clazz, id, false, Some(owner))
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

    private def rl[T](fn: => T) = withReadLock[T](lock)(fn)
    private def wl[T](fn: => T) = withWriteLock[T](lock)(fn)
}

object InMemoryStorage {

    private def copyObj[T](obj: T): T =
        deserialize(serialize(obj.asInstanceOf[Obj]), obj.getClass)
}

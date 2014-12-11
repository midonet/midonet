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

import java.util.concurrent.atomic.AtomicReference
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
import rx.Observable.OnSubscribe
import rx.Scheduler.Worker
import rx._
import rx.functions.Action0
import rx.subjects.{BehaviorSubject, PublishSubject}

import org.midonet.cluster.data.storage.FieldBinding.DeleteAction
import org.midonet.cluster.data.storage.InMemoryStorage.{Key, copyObj}
import org.midonet.cluster.data.storage.OwnershipType.OwnershipType
import org.midonet.cluster.data.storage.ZookeeperObjectMapper._
import org.midonet.cluster.data.{Obj, ObjId}
import org.midonet.util.concurrent.Locks.{withReadLock, withWriteLock}
import org.midonet.util.eventloop.Reactor

/**
 * A simple in-memory implementation of the [[Storage]] trait, equivalent to
 * the [[ZookeeperObjectMapper]] to use within unit tests.
 */
@Inject
class InMemoryStorage(reactor: Reactor) extends Storage {

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
        private val lock = new ReentrantReadWriteLock()
        private val stream = PublishSubject.create[Observable[T]]()

        def create(id: ObjId, obj: Obj): Unit = withWriteLock(lock) {
            val node = new InstanceNode(clazz, obj.asInstanceOf[T])
            instances.putIfAbsent(getIdString(clazz, id), node) match {
                case Some(n) => throw new ObjectExistsException(clazz, id)
                case None => stream.onNext(node.observable)
            }
        }

        def apply(id: ObjId): Option[T] =
            instances.get(getIdString(clazz, id)).map(_.value)

        def update(id: ObjId, obj: T): Unit = {
            instances.get(getIdString(clazz, id)) match {
                case Some(node) => node.update(obj)
                case None => throw new NotFoundException(clazz, id)
            }
        }

        def get(id: ObjId): Future[T] = {
            instances.get(getIdString(clazz, id)) match {
                case Some(node) => node.get
                case None => Promise[T]()
                    .failure(new NotFoundException(clazz, id))
                    .future
            }
        }

        def getAll(ids: Seq[_ <: ObjId]): Seq[Future[T]] = ids.map(get)

        def getAll: Future[Seq[Future[T]]] =
            Promise().success(instances.values.map(_.get).to[Seq]).future

        def exists(id: ObjId): Future[Boolean] =
            Promise().success(instances.containsKey(getIdString(clazz, id)))
                     .future

        def delete(id: ObjId): T = {
            instances.remove(getIdString(clazz, id)) match {
                case Some(node) => node.delete()
                case None => throw new NotFoundException(clazz, id)
            }
        }

        def observable(id: ObjId): Observable[T] = {
            instances.get(getIdString(clazz, id)) match {
                case Some(node) => node.observable
                case None => Observable.error(new NotFoundException(clazz, id))
            }
        }

        def observable(): Observable[Observable[T]] = Observable.create(
            new OnSubscribe[Observable[T]] {
                override def call(sub: Subscriber[_ >: Observable[T]]): Unit =
                    withReadLock(lock) {
                        instances.values.foreach { i => sub.onNext(i.observable) }
                        sub.add(stream unsafeSubscribe sub)
                    }
            }
        )
    }

    private class InstanceNode[T](val clazz: Class[T],
                                  obj: T) extends Observer[T] {

        private val ref = new AtomicReference[T](copyObj(obj))
        private val stream = BehaviorSubject.create[T](ref.get)
        private val obs = stream.subscribeOn(scheduler)

        obs.subscribe(this)

        def value = ref.get

        def update(value: T): Unit = {
            stream.onNext(copyObj(value))
        }

        def delete(): T = {
            stream.onCompleted()
            ref.get()
        }

        def observable = obs

        def subscribe(observer: Observer[_ >: T]): Subscription = {
            obs.subscribe(observer)
        }

        def get: Future[T] = Promise[T]().success(ref.get).future

        override def onCompleted(): Unit = { }
        override def onError(e: Throwable): Unit = { }
        override def onNext(value: T): Unit = ref.set(value)
    }

    private class Transaction {
        private val objsToDelete = new mutable.HashSet[Key[_]]()

        def get[T](clazz: Class[T], id: ObjId): Option[T] = {
            val key = Key(clazz, getIdString(clazz, id))
            if (objsToDelete.contains(key))
                return None

            classes.get(clazz)(id) match {
                case Some(o) => Some(o.asInstanceOf[T])
                case None => throw new NotFoundException(clazz, id)
            }
        }

        def create(obj: Obj): Unit = {
            val thisClass = obj.getClass
            assert(isRegistered(thisClass))
            val thisId = getObjectId(obj)
            for (binding <- bindings.get(thisClass).asScala;
                 thatId <- binding.getFwdReferenceAsList(obj).asScala) {
                addBackreference(binding, thisId, thatId)
            }
            classes.get(thisClass).create(thisId, obj)
        }

        def update(obj: Obj, validator: UpdateValidator[Obj]): Unit = {
            val thisClass = obj.getClass
            assert(isRegistered(thisClass))
            val thisId = getObjectId(obj)
            val oldThisObj = classes.get(thisClass)(thisId)
                .getOrElse(throw new NotFoundException(thisClass, thisId))
                .asInstanceOf[Obj]

            val newThisObj = if (null == validator) obj else {
                val modified = validator.validate(oldThisObj, obj)
                val thisObj = if (modified != null) modified else obj
                if (!getObjectId(thisObj).equals(thisId)) {
                    throw new IllegalArgumentException(
                        "Modifying newObj.id in UpdateValidator.validate() " +
                        "is not supported.")
                }
                thisObj
            }

            for (bdg <- bindings.get(thisClass).asScala) {
                val oldThoseIds = bdg.getFwdReferenceAsList(oldThisObj).asScala
                val newThoseIds = bdg.getFwdReferenceAsList(newThisObj).asScala
                for (removedThatId <- oldThoseIds - newThoseIds)
                    clearBackreference(bdg, thisId, removedThatId)
                for (addedThatId <- newThoseIds - oldThoseIds)
                    addBackreference(bdg, thisId, addedThatId)
            }

            classes.get(thisClass).asInstanceOf[ClassNode[Obj]]
                .update(thisId, newThisObj)
        }

        /* If ignoresNeo (ignores deletion on non-existing objects) is true,
         * the method silently returns if the specified object does not exist /
         * has already been deleted.
         */
        def delete(clazz: Class[_], id: ObjId, ignoresNeo: Boolean): Unit = {
            assert(isRegistered(clazz))
            val key = Key(clazz, getIdString(clazz, id))
            val thisObj = classes.get(clazz)(id) getOrElse {
                if (!ignoresNeo) throw new NotFoundException(clazz, id)
                else return
            }
            objsToDelete += key

            for (bdg <- bindings.get(clazz).asScala
                 if bdg.hasBackReference;
                 thatId <- bdg.getFwdReferenceAsList(thisObj).asScala.distinct
                 if !objsToDelete.contains(
                     Key(bdg.getReferencedClass,
                         getIdString(bdg.getReferencedClass, thatId)))) {
                bdg.onDeleteThis match {
                    case DeleteAction.ERROR =>
                        throw new ObjectReferencedException(
                            clazz, id, bdg.getReferencedClass, thatId)
                    case DeleteAction.CLEAR =>
                        clearBackreference(bdg, id, thatId)
                    case DeleteAction.CASCADE =>
                        delete(bdg.getReferencedClass, thatId, ignoresNeo)
                }
            }

            classes.get(clazz).delete(id)
        }

        private def addBackreference(binding: FieldBinding,
                                     thisId: ObjId, thatId: ObjId): Unit = {
            get(binding.getReferencedClass, thatId) foreach { thatObj =>
                classes.get(binding.getReferencedClass).asInstanceOf[ClassNode[Any]]
                    .update(thatId,
                            binding.addBackReference(thatObj, thatId, thisId))
            }
        }

        private def clearBackreference(binding: FieldBinding,
                                       thisId: ObjId, thatId: ObjId): Unit = {
            get(binding.getReferencedClass, thatId) foreach { thatObj =>
                classes.get(binding.getReferencedClass).asInstanceOf[ClassNode[Any]]
                    .update(thatId,
                            binding.clearBackReference(thatObj, thisId))
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

    override def update(obj: Obj): Unit = multi(List(UpdateOp(obj)))

    override def update[T <: Obj](obj: T, validator: UpdateValidator[T]): Unit =
        multi(List(UpdateOp(obj, validator)))

    override def delete(clazz: Class[_], id: ObjId): Unit =
        multi(List(DeleteOp(clazz, id)))

    override def deleteIfExists(clazz: Class[_], id: ObjId): Unit =
        multi(List(DeleteOp(clazz, id, ignoreIfNotExists = true)))

    override def get[T](clazz: Class[T], id: ObjId): Future[T] =
            withReadLock(lock) {
        assertBuilt()
        assert(isRegistered(clazz))

        classes.get(clazz).asInstanceOf[ClassNode[T]].get(id)
    }

    override def getAll[T](clazz: Class[T],
                           ids: Seq[_ <: ObjId]): Seq[Future[T]] =
            withReadLock(lock) {
        assertBuilt()
        assert(isRegistered(clazz))

        classes.get(clazz).asInstanceOf[ClassNode[T]].getAll(ids)
    }

    override def getAll[T](clazz: Class[T]): Future[Seq[Future[T]]] =
            withReadLock(lock) {
        assertBuilt()
        assert(isRegistered(clazz))

        classes.get(clazz).asInstanceOf[ClassNode[T]].getAll
    }

    override def exists(clazz: Class[_], id: ObjId): Future[Boolean] =
            withReadLock(lock) {
        assertBuilt()
        assert(isRegistered(clazz))

        classes.get(clazz).exists(id)
    }

    override def multi(ops: Seq[PersistenceOp]): Unit = withWriteLock(lock) {

        if (ops.isEmpty) return

        val tr = new Transaction

        ops.foreach {
            case CreateOp(obj) => tr.create(obj)
            case UpdateOp(obj, validator) => tr.update(obj, validator)
            case DeleteOp(clazz, id, ignores) => tr.delete(clazz, id, ignores)
            case _ =>
        }
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
        classes.get(clazz).asInstanceOf[ClassNode[T]].observable()
    }

    override def registerClass(clazz: Class[_]): Unit = {
        registerClassInternal(clazz, OwnershipType.Shared)
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

    private def registerClassInternal(clazz: Class[_],
                                      ownershipType: OwnershipType): Unit = {
        classes.putIfAbsent(clazz, new ClassNode(clazz)) match {
            case c: ClassNode[_] => throw new IllegalStateException(
                s"Class $clazz is already registered.")
            case _ =>
        }

        classInfo += clazz -> makeInfo(clazz, ownershipType)
    }

    private def getObjectId(obj: Obj) = classInfo(obj.getClass).idOf(obj)
}

object InMemoryStorage {
    private case class Key[T](clazz: Class[T], id: String)

    private def copyObj[T](obj: T): T =
        deserialize(serialize(obj.asInstanceOf[Obj]), obj.getClass)
}

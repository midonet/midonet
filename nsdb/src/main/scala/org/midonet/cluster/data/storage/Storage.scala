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

import java.util.Properties

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.control.NonFatal

import com.google.common.collect.{ArrayListMultimap, ImmutableListMultimap, Multimap, Multimaps}
import com.google.protobuf.Message

import org.apache.commons.lang.StringUtils

import rx.Observable

import org.midonet.cluster.data.ZoomMetadata.ZoomOwner
import org.midonet.cluster.data.storage.FieldBinding.DeleteAction
import org.midonet.cluster.data.storage.Storage.{BindingsMap, ClassInfo, ClassesMap}
import org.midonet.cluster.data.{Obj, ObjId}

/* Op classes for ZookeeperObjectMapper.multi */
sealed trait PersistenceOp

case class CreateOp(obj: Obj) extends PersistenceOp

case class UpdateOp[T <: Obj](obj: T, validator: UpdateValidator[T])
    extends PersistenceOp

object UpdateOp {
    def apply[T <: Obj](obj: T): UpdateOp[T] = UpdateOp(obj, null)
}

case class DeleteOp(clazz: Class[_], id: ObjId,
                    ignoreIfNotExists: Boolean = false) extends PersistenceOp

/**
 * Operation to create a node with the specified value at the specified path.
 * The node must not already exist. Ancestor nodes need not exist, and will be
 * created as needed to provide a path from the root to the new node.
 *
 * Only required to support writes into the old Replicated maps.
 */
case class CreateNodeOp(path: String, value: String) extends PersistenceOp

/**
 * Operation to update the value of the node at the specified path. The node
 * must already exist.
 *
 * Only required to support writes into the old Replicated maps.
 */
case class UpdateNodeOp(path: String, value: String) extends PersistenceOp

/**
 * Operation to delete the node at the specified path. The node must already
 * exist. If any descendant nodes exist, they will be recursively deleted.
 *
 * Only required to support writes into the old Replicated maps.
 */
case class DeleteNodeOp(path: String) extends PersistenceOp


/**
 * Used in update operations to perform validation that depends on knowing the
 * current state of the object to be validated.
 *
 * A caller could call get() to get the current value and use that for
 * validation, but this creates a race condition and requires an extra round
 * trip to the data store, as the update operation will also need to fetch the
 * object's current state from the data store. Using an UpdateValidator solves
 * both these problems.
 */
trait UpdateValidator[T <: Obj] {
    /**
     * Called at the beginning of the update operation after fetching the
     * current state of the object from the data store but before doing anything
     * else. Implementations of validate() may:
     *
     * 1. Throw an exception after detecting a validation error.
     * 2. Modify newObj. This is useful for cases where a field in type T is not
     *    exposed via the API, and will thus be null in instances received from
     *    the API. In such cases, validate() should update newObj to set the
     *    values of thees fields to their values in oldObj so that the update
     *    does not set them to null in the data store. If newObj is immutable,
     *    you can return an object to be committed in place of newObj.
     *
     * Do not modify oldObj, as this can lead to errors and data corruption.
     *
     * @param oldObj The current state of the object in the data store. In the
     *               case of a multi() operation, this will also reflect any
     *               changes made by prior operations.
     *
     * @param newObj The state to which the object is to be updated. This is the
     *               same object passed to update() or UpdateOp().
     *
     * @return Object to commit in place of newObj. If null, newObj will be
     *         committed.
     */
    def validate(oldObj: T, newObj: T): T
}

/**
 * A general exception class thrown by the Storage service API. Respective
 * Storage logic is expected to extend this general exception class to define a
 * proper specific exception.
 */
class StorageException(val msg: String, val cause: Throwable)
        extends RuntimeException(msg, cause) {

    def this(msg: String) {
        this(msg, null)
    }

    def this(cause: Throwable) {
        this("", cause)
    }
}

object Storage {

    type ClassesMap = Map[Class[_], ClassInfo]
    type BindingsMap = Multimap[Class[_], FieldBinding]

    final val ProductVersion = productVersion
    final val ProductCommit = productCommit

    abstract class ClassInfo(val clazz: Class[_]) {
        def idOf(obj: Obj): ObjId
    }

    private[storage] final class MessageClassInfo(clazz: Class[_])
        extends ClassInfo(clazz) {

        private val idFieldDesc =
            ProtoFieldBinding.getMessageField(clazz, FieldBinding.ID_FIELD)

        def idOf(obj: Obj) = obj.asInstanceOf[Message].getField(idFieldDesc)
    }

    private[storage] final class JavaClassInfo(clazz: Class[_])
        extends ClassInfo(clazz) {

        private val idField = clazz.getDeclaredField(FieldBinding.ID_FIELD)

        idField.setAccessible(true)

        def idOf(obj: Obj) = idField.get(obj)
    }

    @throws[IllegalArgumentException]
    private def makeInfo(clazz: Class[_]): ClassInfo = {
        try {
            if (classOf[Message].isAssignableFrom(clazz)) {
                new MessageClassInfo(clazz)
            } else {
                new JavaClassInfo(clazz)
            }
        } catch {
            case NonFatal(e) =>
                throw new IllegalArgumentException(
                    s"Class $clazz does not have a field named 'id', or the " +
                    "field could not be made accessible.", e)
        }
    }

    private def productVersion: String = {
        StringUtils.defaultIfEmpty(getClass.getPackage.getImplementationVersion,
                                   StringUtils.EMPTY)
    }

    private def productCommit: String = {
        val gitStream =
            getClass.getClassLoader.getResourceAsStream("git.properties")
        if (gitStream ne null) {
            val properties = new Properties()
            properties.load(gitStream)
            StringUtils.defaultIfEmpty(properties.getProperty("git.commit.id"),
                                       StringUtils.EMPTY)
        } else StringUtils.EMPTY
    }

}

/**
 * A trait that extends the read-only storage service API and provides storage
 * write service API.
 */
trait Storage extends ReadOnlyStorage {

    private final val mutex = new Object

    @volatile private var built = false
    private val fieldBindings = ArrayListMultimap.create[Class[_], FieldBinding]()

    private val classNames = new mutable.HashSet[String]()
    private val classInfo = new mutable.HashMap[Class[_], ClassInfo]()

    @volatile private var currentClasses: ClassesMap = Map.empty
    @volatile private var currentBindings: BindingsMap = ImmutableListMultimap.of()

    /**
     * Synchronous method that persists the specified object to the storage. The
     * object must have a field named "id", and an appropriate unique ID must
     * already be assigned to the object before the call.
     */
    @throws[ObjectExistsException]
    @throws[ReferenceConflictException]
    def create(obj: Obj): Unit = multi(List(CreateOp(obj)))

    /**
     * Synchronous method that updates the specified object in the storage.
     */
    @throws[NotFoundException]
    @throws[ReferenceConflictException]
    def update(obj: Obj): Unit = multi(List(UpdateOp(obj)))

    /**
     * Synchronous method that updates the specified object in the storage. It
     * takes an optional UpdateValidator callback which can be used to validate
     * the new version of obj against the current version in storage and/or to
     * copy data from the current version to the new version.
     */
    @throws[NotFoundException]
    @throws[ReferenceConflictException]
    def update[T <: Obj](obj: T, validator: UpdateValidator[T]): Unit =
        multi(List(UpdateOp(obj, validator)))

    /**
     * Synchronous method that deletes the specified object from the storage.
     */
    @throws[NotFoundException]
    @throws[ObjectReferencedException]
    def delete(clazz: Class[_], id: ObjId): Unit =
        multi(List(DeleteOp(clazz, id)))

    /**
     * Synchronous method that deletes the specified object from the storage if
     * it exists, or silently returns if it doesn't.
     */
    @throws[ObjectReferencedException]
    def deleteIfExists(clazz: Class[_], id: ObjId): Unit =
        multi(List(DeleteOp(clazz, id, ignoreIfNotExists = true)))

    /**
     * Synchronous method that executes multiple create, update, and/or delete
     * operations atomically.
     */
    @throws[NotFoundException]
    @throws[ObjectExistsException]
    @throws[ObjectReferencedException]
    @throws[ReferenceConflictException]
    def multi(ops: Seq[PersistenceOp]): Unit

    /**
     * Creates a new storage transaction that allows multiple read and write
     * operations to be executed atomically. The transaction guarantees that
     * the value of an object is not modified until the transaction is
     * completed or that the transaction will fail with a
     * [[java.util.ConcurrentModificationException]].
     */
    def transaction(owner: ZoomOwner = ZoomOwner.None): Transaction

    /**
     * Provide an Observable that emits updates to the specified object
     * asynchronously. Note that an implementation may chose to cache
     * the observable for each given object in order for several callers
     * to share it. Any recycling/GC mechanism may also be applied on
     * observables, as long as it guarantees that the observable has no
     * subscriptions left.
     *
     * When the object exists in the backend, the Observable will always emit
     * at least one element containing its most recent state. After this,
     * further updates will be emitted in the same order as they occurred in
     * the backend storage. Note however that if an object is updated in the
     * backend storage multiple times in quick succession, some updates may not
     * trigger a call to onNext(). In any case, each call to onNext() will
     * provide the most up-to-date data available, and all subscribers will
     * be able to see the same sequence of events, in the same order.
     *
     * When an object doesn't exist the returned observable will
     * immediately complete with an Error containing an IllegalStateException.
     *
     * When an object is deleted, the observable will be completed. If
     * the object doesn't exist in the backend when the Observable is first
     * requested then it'll onError.
     */
    def observable[T](clazz: Class[T], id: ObjId): Observable[T]

    /**
     * Subscribes to all the entities of the given type. Upon subscription at
     * time t0, obs.onNext() will receive an Observable[T] for each object of
     * class T existing at time t0, and future updates at tn > t0 will each
     * trigger a call to onNext() with an Observable[T] for a new object.
     *
     * Neither obs.onCompleted() nor obs.onError() will be invoked under
     * normal circumstances.
     *
     * The subscribe() method of each of these Observables has the same behavior
     * as ZookeeperObjectMapper.subscribe(Class[T], ObjId).
     */
    def observable[T](clazz: Class[T]): Observable[Observable[T]]

    /**
      * Tries to execute a transaction using the current write and retry policy.
      */
    @throws[NotFoundException]
    @throws[ObjectExistsException]
    @throws[ObjectReferencedException]
    @throws[ReferenceConflictException]
    @throws[StorageException]
    def tryTransaction[R](owner: ZoomOwner)(f: (Transaction) => R): R

    /**
      * Registers a new object class in storage.
      */
    @throws[IllegalArgumentException]
    final def registerClass(clazz: Class[_]): Unit = mutex.synchronized {
        assertNotBuilt()

        val name = clazz.getSimpleName
        if (classNames.contains(name)) {
            throw new IllegalArgumentException(
                s"A class with the simple name $name is already " +
                s"registered; registering multiple classes with the same " +
                s"simple name is not supported")
        }

        classNames.add(name)
        classInfo.put(clazz, Storage.makeInfo(clazz))

        onRegisterClass(clazz)
    }

    /**
      * Declares a field binding between the specified fields of the given
      * classes.
      */
    final def declareBinding(leftClass: Class[_], leftFieldName: String,
                             onDeleteLeft: DeleteAction,
                             rightClass: Class[_], rightFieldName: String,
                             onDeleteRight: DeleteAction)
    : Unit = mutex.synchronized {
        assertNotBuilt()
        assertRegistered(leftClass)
        assertRegistered(rightClass)

        val leftIsMessage = classOf[Message].isAssignableFrom(leftClass)
        val rightIsMessage = classOf[Message].isAssignableFrom(rightClass)
        if (leftIsMessage != rightIsMessage) {
            throw new IllegalArgumentException(
                "Cannot bind a Protocol Buffers class to a plain Java class")
        }

        val bindings = if (leftIsMessage) {
            ProtoFieldBinding.createBindings(
                leftClass, leftFieldName, onDeleteLeft,
                rightClass, rightFieldName, onDeleteRight)
        } else {
            PojoFieldBinding.createBindings(
                leftClass, leftFieldName, onDeleteLeft,
                rightClass, rightFieldName, onDeleteRight)
        }

        for (entry <- bindings.entries.asScala) {
            fieldBindings.put(entry.getKey, entry.getValue)
        }
    }


    /** This method must be called after all calls to registerClass() and
      * declareBinding() have been made, but before any calls to data-related
      * methods such as CRUD operations and subscribe().
      */
    @throws[IllegalStateException]
    final def build(): Unit = mutex.synchronized {
        assertNotBuilt()

        currentClasses = classInfo.toMap
        currentBindings = Multimaps.unmodifiableListMultimap(fieldBindings)

        onBuild()
        built = true
    }

    /**
      * Verifies that storage is built and throws a
      * [[ServiceUnavailableException]] otherwise.
      */
    @throws[ServiceUnavailableException]
    @inline protected final def assertBuilt(): Unit = {
        if (!built) {
            throw new ServiceUnavailableException()
        }
    }

    @inline protected final def assertNotBuilt(): Unit = {
        if (built) {
            throw new IllegalStateException("Storage already built")
        }
    }

    /**
      * Verifies the specified class is registered and throws an
      * [[IllegalArgumentException]] otherwise.
      */
    @throws[IllegalArgumentException]
    @inline protected final def assertRegistered(clazz: Class[_]): Unit = {
        if (!isRegistered(clazz)) {
            throw new IllegalArgumentException(
                s"Class ${clazz.getSimpleName} is not registered")
        }
    }

    /** Whether the instance is ready to service requests */
    @inline final def isBuilt: Boolean = built

    /**
      * @return Whether the specified class is registered.
      */
    @inline final def isRegistered(clazz: Class[_]): Boolean = {
        classInfo.contains(clazz)
    }

    /**
      * @return An immutable copy of the current classes.
      */
    def objectClasses: ClassesMap = currentClasses

    /**
      * @return An immutable copy of the current field bindings.
      */
    def bindings: BindingsMap = currentBindings

    /**
      * Called when a class is registered. This allows derived classes to
      * perform further initialization for this class.
      */
    protected def onRegisterClass(clazz: Class[_]): Unit = { }

    /**
      * Called when the storage is built. This allows derived classes to perform
      * further storage initialization.
      */
    protected def onBuild(): Unit = { }

}

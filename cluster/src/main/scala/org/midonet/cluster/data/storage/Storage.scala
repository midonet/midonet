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

import java.util.{List => JList}

import scala.concurrent.Future

import rx.Observable

import org.midonet.cluster.data.storage.FieldBinding.DeleteAction
import org.midonet.cluster.data.storage.OwnershipType.OwnershipType
import org.midonet.cluster.data.{Obj, ObjId}

/* Op classes for ZookeeperObjectMapper.multi */
sealed trait PersistenceOp

case class CreateOp(obj: Obj) extends PersistenceOp

private[storage]
case class CreateWithOwnerOp(obj: Obj, owner: String)
    extends PersistenceOp

case class UpdateOp[T <: Obj](obj: T, validator: UpdateValidator[T])
    extends PersistenceOp

case class UpdateWithOwnerOp[T <: Obj](obj: T, owner: String,
                                       validator: UpdateValidator[T])
    extends PersistenceOp

case class UpdateOwnerOp(clazz: Class[_], id: ObjId, owner: String,
                         throwIfExists: Boolean)
    extends PersistenceOp

object UpdateOp {
    def apply[T <: Obj](obj: T): UpdateOp[T] = UpdateOp(obj, null)
}

case class DeleteOp(clazz: Class[_], id: ObjId,
                    ignoreIfNotExists: Boolean = false)
    extends PersistenceOp

private[storage]
case class DeleteWithOwnerOp(clazz: Class[_], id: ObjId, owner: String)
    extends PersistenceOp

private[storage]
case class DeleteOwnerOp(clazz: Class[_], id: ObjId, owner: String)
    extends PersistenceOp

case class CreateMap(mapId: String) extends PersistenceOp

case class DeleteMap(mapId: String) extends PersistenceOp

case class AddMapEntry(mapId: String, key: String, value: String)
    extends PersistenceOp

case class UpdateMapEntry(mapId: String, key: String, value: String)
    extends PersistenceOp

case class DeleteMapEntry(mapId: String, key: String) extends PersistenceOp

/* Object ownership types */
object OwnershipType extends Enumeration {
    class OwnershipType(val isExclusive: Boolean) extends Val
    val Exclusive = new OwnershipType(true)
    val Shared = new OwnershipType(false)
}

case class ObjWithOwner(obj: Obj, owners: Seq[ObjId])

abstract class ClassInfo(val clazz: Class[_], val ownershipType: OwnershipType) {
    def idOf(obj: Obj): ObjId
}


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

/**
 * A trait defining the read-only storage service API.
 */
trait ReadOnlyStorage {
    /**
     * Asynchronous method that gets the specified instance of the specified
     * class from storage. If the value is available in the internal cache,
     * the returned future is completed synchronously.
     */
    @throws[NotFoundException]
    def get[T](clazz: Class[T], id: ObjId): Future[T]

    /**
     * Asynchronous method that gets the specified instances of the specified
     * class from storage. The method returns a sequence a futures,
     * corresponding to the retrieval of each requested instance. The futures
     * will fail for the objects that could not be retrieved from storage. The
     * futures for the objects that are cached internally, will complete
     * synchronously.
     */
    def getAll[T](clazz: Class[T], ids: Seq[_ <: ObjId]): Seq[Future[T]]

    /**
     * Asynchronous method that gets all the instances of the specified class
     * from the storage. The method returns a future with a sequence a futures.
     * The outer future completes when the sequence of objects has been
     * retrieved from storage. The inner futures complete when the data of each
     * object has been retrived from storage.
     */
    def getAll[T](clazz: Class[T]): Future[Seq[T]]

    /**
     * Asynchronous method that indicated if the specified object exists in the
     * storage.
     */
    def exists(clazz: Class[_], id: ObjId): Future[Boolean]
}

/**
 * A trait that extends the read-only storage service API and provides storage
 * write service API.
 */
trait Storage extends ReadOnlyStorage {
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
     * Synchronous method that executes multiple create, update, and/or delete
     * operations atomically.
     */
    @throws[NotFoundException]
    @throws[ObjectExistsException]
    @throws[ObjectReferencedException]
    @throws[ReferenceConflictException]
    def multi(ops: JList[PersistenceOp]): Unit

    /**
     * Flushes the storage, deleting all the objects stored in it.
     */
    @throws[StorageException]
    def flush(): Unit

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

    /* We should remove the methods below, but first we must make ZOOM support
     * offline class registration so that we can register classes from the
     * guice modules without causing exceptions */
    def registerClass(clazz: Class[_]): Unit

    def isRegistered(clazz: Class[_]): Boolean

    def declareBinding(leftClass: Class[_], leftFieldName: String,
                       onDeleteLeft: DeleteAction,
                       rightClass: Class[_], rightFieldName: String,
                       onDeleteRight: DeleteAction): Unit

    /** This method must be called after all calls to registerClass() and
      * declareBinding() have been made, but before any calls to data-related
      * methods such as CRUD operations and subscribe().
      */
    def build()

    def isBuilt: Boolean

}

/**
 * A trait defining the methods for ownership-based storage.
 */
trait StorageWithOwnership extends Storage {

    /**
     * Registers an object class with support for ownership. The ownership type
     * can be one of the following:
     * - [[OwnershipType.Exclusive]] Every object in this class supports only
     * one owner at a time. The owner identifier is specified when the object
     * is created, and ownership is released either when the object is deleted,
     * or when the current owner disconnects from storage, orphaning the object.
     * It is possible to update the ownership both by the current owner, and
     * when an object has been orphaned.
     * - [[OwnershipType.Shared]] Every object in this class supports multiple
     * owners, where all owners are peers. Ownership is optional for objects
     * with shared ownership. Owners can be added using the update() or
     * updateOwner() methods, where the former also updates the object data.
     * Owners are removed with the delete() or deleteOwner() methods.
     */
    def registerClass(clazz: Class[_], ownershipType: OwnershipType): Unit

    /**
     * Gets the set of owners for the given object.
     */
    @throws[NotFoundException]
    def getOwners(clazz: Class[_], id: ObjId): Future[Set[String]]

    /**
     * Creates an object with the specified owner.
     */
    @throws[ObjectExistsException]
    @throws[ReferenceConflictException]
    @throws[OwnershipConflictException]
    def create(obj: Obj, owner: ObjId): Unit =
        multi(List(CreateWithOwnerOp(obj, owner.toString)))

    /**
     * Updates an object and its owner. The method has the following behavior
     * depending on the ownership type:
     * - for [[OwnershipType.Exclusive]], the update succeeds only if the
     *   specified owner is the current owner of the object, or if the object
     *   has been orphaned and it has no owner.
     * - for [[OwnershipType.Shared]], it is always possible to update the
     *   owner.
     * In both cases, the method rewrites the current ownership node in storage,
     * such that it corresponds to the client session last performing the
     * update.
     */
    @throws[NotFoundException]
    @throws[ReferenceConflictException]
    @throws[OwnershipConflictException]
    def update[T <: Obj](obj: T, owner: ObjId,
                         validator: UpdateValidator[T]): Unit =
        multi(List(UpdateWithOwnerOp(obj, owner.toString, validator)))

    /**
     * Updates the owner for the specified object. This method is similar to
     * [[update()]], except that the method throws an exception if the ownership
     * owner already exists and the throwIfExists argument is set to true.
     * The method does not modify the object data, however it increments the
     * object version to prevent concurrent modifications,
     * @param throwIfExists When set to true, the method throws an exception if
     *                      the ownership node already exists. This can be used
     *                      to prevent taking ownership from a different client
     *                      session, when the owner is the same.
     */
    @throws[NotFoundException]
    @throws[OwnershipConflictException]
    def updateOwner(clazz: Class[_], id: ObjId, owner: ObjId,
                    throwIfExists: Boolean): Unit =
        multi(List(UpdateOwnerOp(clazz, id, owner.toString, throwIfExists)))

    /**
     * Deletes an object and/or removes an ownership. The specified identifier
     * must be a current owner of the object. For [[OwnershipType.Exclusive]],
     * the operation either fails or the object is deleted. For
     * [[OwnershipType.Shared]], the object is not deleted if there are one or
     * more remaining owners for the object, in which case only the specified
     * identifier is removed as owner.
     */
    @throws[NotFoundException]
    @throws[ReferenceConflictException]
    @throws[OwnershipConflictException]
    def delete(clazz: Class[_], id: ObjId, owner: ObjId): Unit =
        multi(List(DeleteWithOwnerOp(clazz, id, owner.toString)))

    @throws[NotFoundException]
    @throws[OwnershipConflictException]
    def deleteOwner(clazz: Class[_], id: ObjId, owner: ObjId): Unit =
        multi(List(DeleteOwnerOp(clazz, id, owner.toString)))

    /**
     * Returns an observable, which emits the current set of owners for
     * a given object. The observable emits an
     * [[org.midonet.cluster.util.ParentDeletedException]] if the object
     * does not exist, or when the object is deleted.
     */
    def ownersObservable(clazz: Class[_], id: ObjId): Observable[Set[String]]

}

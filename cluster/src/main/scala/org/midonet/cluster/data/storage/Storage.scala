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

private[storage]
case class UpdateWithOwnerOp[T <: Obj](obj: T, owner: String, overwrite: Boolean,
                                       validator: UpdateValidator[T])
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

/* Object ownership types */
object OwnershipType extends Enumeration {
    class OwnershipType(val isSupported: Boolean,
                        val isExclusive: Boolean) extends Val
    val None = new OwnershipType(false, false)
    val Exclusive = new OwnershipType(true, true)
    val Shared = new OwnershipType(true, false)
}

case class ObjWithOwner(obj: Obj, owners: Seq[ObjId])

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
    def getAll[T](clazz: Class[T]): Future[Seq[Future[T]]]

    /**
     * Asynchronous method that indicated if the specified object exists in the
     * storage.
     */
    def exists(clazz: Class[_], id: ObjId): Future[Boolean]
}

/**
 * A trait defining the methods for ownership-based storage.
 */
trait StorageWithOwnership {

    def registerClass(clazz: Class[_], ownershipType: OwnershipType): Unit

    @throws[NotFoundException]
    @throws[ObjectExistsException]
    @throws[ReferenceConflictException]
    @throws[OwnershipConflictException]
    def create(obj: Obj, owner: ObjId): Unit

    @throws[NotFoundException]
    @throws[ReferenceConflictException]
    @throws[OwnershipConflictException]
    def update[T <: Obj](obj: T, owner: ObjId, overwriteOwner: Boolean,
                         validator: UpdateValidator[T]): Unit

    @throws[NotFoundException]
    @throws[ReferenceConflictException]
    @throws[OwnershipConflictException]
    def delete(clazz: Class[_], id: ObjId, owner: ObjId): Unit

}

/**
 * A trait that extends the read-only storage service API and provides storage
 * write service API.
 */
trait Storage extends ReadOnlyStorage with StorageWithOwnership {
    /**
     * Synchronous method that persists the specified object to the storage. The
     * object must have a field named "id", and an appropriate unique ID must
     * already be assigned to the object before the call.
     */
    @throws[NotFoundException]
    @throws[ObjectExistsException]
    @throws[ReferenceConflictException]
    def create(obj: Obj): Unit

    /**
     * Synchronous method that updates the specified object in the storage.
     */
    @throws[NotFoundException]
    @throws[ReferenceConflictException]
    def update(obj: Obj): Unit

    /**
     * Synchronous method that updates the specified object in the storage. It
     * takes an optional UpdateValidator callback which can be used to validate
     * the new version of obj against the current version in storage and/or to
     * copy data from the current version to the new version.
     */
    @throws[NotFoundException]
    @throws[ReferenceConflictException]
    def update[T <: Obj](obj: T, validator: UpdateValidator[T]): Unit

    /**
     * Synchronous method that deletes the specified object from the storage.
     */
    @throws[NotFoundException]
    @throws[ObjectReferencedException]
    def delete(clazz: Class[_], id: ObjId): Unit

    /**
     * Synchronous method that deletes the specified object from the storage if
     * it exists, or silently returns if it doesn't.
     */
    @throws[ObjectReferencedException]
    def deleteIfExists(clazz: Class[_], id: ObjId): Unit

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

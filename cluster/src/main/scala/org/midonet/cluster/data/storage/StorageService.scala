/**
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.storage

import java.util.{List => JList}

import rx.{Observable, Observer, Subscription}

import org.midonet.cluster.data.storage.FieldBinding.DeleteAction

/* Op classes for ZookeeperObjectMapper.multi */
sealed trait PersistenceOp

case class CreateOp(obj: Obj) extends PersistenceOp

case class UpdateOp[T <: Obj](
    obj: T, validator: UpdateValidator[T]) extends PersistenceOp {
    def this(obj: T) = this(obj, null)
}

object UpdateOp {
    def apply[T <: Obj](obj: T): UpdateOp[T] = UpdateOp(obj, null)
}

case class DeleteOp(clazz: Class[_], id: ObjId) extends PersistenceOp


/**
 * Used in StorageService update operations to perform validation that depends
 * on knowing the current state of the object to be validated. For example, we
 * don't allow a LoadBalancer update to change the value of routerId. Without
 * knowing the current value of routerId in the data store, there's no way to
 * validate this.
 *
 * A caller could call StorageService.get() to get the current value and use
 * that for validation, but this creates a race condition and requires an extra
 * round trip to the data store, as the update operation will also need to fetch
 * the object's current state from the data store. Using an UpdateValidator
 * solves both these problems.
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
     * @param oldObj
     *     The current state of the object in the data store. In the case of a
     *     multi() operation, this will also reflect any changes made by prior
     *     operations.
     *
     * @param newObj
     *     The state to which the object is to be updated. This is the same
     *     object passed to update() or UpdateOp().
     *
     * @return
     *     Object to commit in place of newObj. If null, newObj will be
     *     committed.
     */
    def validate(oldObj: T, newObj: T): T
}

/**
 * A trait defining the cluster persistence service API.
 */
trait StorageService {
    /**
     * Persists the specified object to the storage. The object must have a
     * field named "id", and an appropriate unique ID must already be assigned
     * to the object before the call.
     */
    @throws[NotFoundException]
    @throws[ObjectExistsException]
    @throws[ReferenceConflictException]
    def create(obj: Obj): Unit

    /**
     * Updates the specified object in the storage.
     */
    @throws[NotFoundException]
    @throws[ReferenceConflictException]
    def update(obj: Obj): Unit

    /**
     * Updates the specified object in the storage. Takes an optional
     * UpdateValidator callback which can be used to validate the new version
     * of obj against the current version in storage and/or to copy data from
     * the current version to the new version.
     */
    @throws[NotFoundException]
    @throws[ReferenceConflictException]
    def update[T <: Obj](obj: T, validator: UpdateValidator[T]): Unit

    /**
     * Deletes the specified object from the storage.
     */
    @throws[NotFoundException]
    @throws[ObjectReferencedException]
    def delete(clazz: Class[_], id: ObjId): Unit

    /**
     * Gets the specified instance of the specified class from the storage.
     */
    @throws[NotFoundException]
    def get[T](clazz: Class[T], id: ObjId): T

    /**
     * Gets the specified instances of the specified class from storage.
     * Any objects not found are assumed to have been deleted since the ID list
     * was retrieved, and are silently ignored.
     */
    def getAll[T](clazz: Class[T], ids: JList[_ <: ObjId]): JList[T]

    /**
     * Gets all the instances of the specified class from the storage.
     */
    def getAll[T](clazz: Class[T]): JList[T]

    /**
     * Returns true if the specified object exists in the storage.
     */
    def exists(clazz: Class[_], id: ObjId): Boolean

    /**
     * Executes multiple create, update, and/or delete operations atomically.
     */
    @throws[NotFoundException]
    @throws[ObjectExistsException]
    @throws[ObjectReferencedException]
    @throws[ReferenceConflictException]
    def multi(ops: Seq[PersistenceOp]): Unit

    /**
     * Executes multiple create, update, and/or delete operations atomically.
     */
    @throws[NotFoundException]
    @throws[ObjectExistsException]
    @throws[ObjectReferencedException]
    @throws[ReferenceConflictException]
    def multi(ops: JList[PersistenceOp]): Unit

    /**
     * Subscribe to the specified object. Upon subscription at time t0,
     * obs.onNext() will receive the object's state at time t0, and future
     * updates at tn > t0 will trigger additional calls to onNext(). If an
     * object is updated in Zookeeper multiple times in quick succession, some
     * updates may not trigger a call to onNext(), but each call to onNext()
     * will provide the most up-to-date data available.
     *
     * obs.onCompleted() will be called when the object is deleted, and
     * obs.onError() will be invoked with a NotFoundException if the object
     * does not exist.
     */
    def subscribe[T](clazz: Class[T],
                     id: ObjId,
                     obs: Observer[_ >: T]): Subscription

    /**
     * Subscribes to the specified class. Upon subscription at time t0,
     * obs.onNext() will receive an Observable[T] for each object of class
     * T existing at time t0, and future updates at tn > t0 will each trigger
     * a call to onNext() with an Observable[T] for a new object.
     *
     * Neither obs.onCompleted() nor obs.onError() will be invoked under normal
     * circumstances.
     *
     * The subscribe() method of each of these Observables has the same behavior
     * as ZookeeperObjectMapper.subscribe(Class[T], ObjId).
     */
    def subscribeAll[T](clazz: Class[T],
                        obs: Observer[_ >: Observable[T]]): Subscription

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

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
 * A trait that extends the read-only storage service API and provides storage
 * write service API.
 */
trait Storage extends ReadOnlyStorage {
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
     * Subscribe to the specified object asynchronously. If a cached version
     * of the requested object already exists, before the subscription method
     * returns (at t0), obs.onNext() will receive the current object's state,
     * and future updates at tn > t0 will trigger additional calls to onNext().
     * If an object is updated in Zookeeper multiple times in quick succession,
     * some updates may not trigger a call to onNext(), but each call to
     * onNext() will provide the most up-to-date data available.
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

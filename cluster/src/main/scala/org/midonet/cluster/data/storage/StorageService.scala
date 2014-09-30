/**
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.storage

import java.util.{List => JList}

import org.midonet.cluster.data.storage.FieldBinding.DeleteAction

import scala.collection.immutable.List

import rx.Observable
import rx.Observer
import rx.Subscription

/* Op classes for ZookeeperObjectMapper.multi */
sealed trait PersistenceOp
case class CreateOp(obj: Obj) extends PersistenceOp
case class UpdateOp(obj: Obj) extends PersistenceOp
case class DeleteOp(clazz: Class[_], id: ObjId) extends PersistenceOp

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

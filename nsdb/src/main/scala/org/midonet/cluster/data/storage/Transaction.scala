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

import java.util.ConcurrentModificationException

import org.midonet.cluster.data.{Obj, ObjId}

trait Transaction {

    /** Gets the specified object within the context of the current transaction.
      * The object is either guaranteed to not be modified until the transaction
      * is committed, or the transaction will fail with a
      * [[ConcurrentModificationException]]. */
    @throws[NotFoundException]
    @throws[InternalObjectMapperException]
    @throws[ConcurrentModificationException]
    def get[T](clazz: Class[T], id: ObjId): T

    /** Gets all objects of the specified class within the context of the
      * current transaction. The objects are either guaranteed to not be
      * modified until the transaction is committed, or the transaction will
      * fail with a [[ConcurrentModificationException]]. */
    @throws[InternalObjectMapperException]
    @throws[ConcurrentModificationException]
    def getAll[T](clazz: Class[T]): Seq[T]

    /** Gets the specified objects within the context of the current transaction.
      * The objects are either guaranteed to not be modified until the
      * transaction is committed, or the transaction will fail with a
      * [[ConcurrentModificationException]]. */
    @throws[NotFoundException]
    @throws[InternalObjectMapperException]
    @throws[ConcurrentModificationException]
    def getAll[T](clazz: Class[T], ids: Seq[ObjId]): Seq[T]

    /**
      * Returns whether the specified object exists.
      */
    @throws[ConcurrentModificationException]
    def exists(clazz: Class[_], id: ObjId): Boolean

    /** Creates an object in the current transaction. After an object is created
      * it becomes available within the context of the transaction even before
      * the transaction is committed, and it can be retrieved, modified or
      * deleted. */
    @throws[NotFoundException]
    @throws[InternalObjectMapperException]
    @throws[ConcurrentModificationException]
    @throws[ObjectExistsException]
    def create(obj: Obj): Unit

    /** Updates an object in the current transaction. After an object is updated
      * its modified value becomes available within the context of the
      * transaction even before the transaction is committed. */
    @throws[NotFoundException]
    @throws[InternalObjectMapperException]
    @throws[ConcurrentModificationException]
    @throws[IllegalArgumentException]
    def update(obj: Obj, validator: UpdateValidator[Obj] = null): Unit

    /** Deletes an object in the current transaction. After an object is deleted
      * it is no longer available to the transaction operations even before the
      * transaction is committed. When the `ignoresNeo` is true, the operation
      * will not fail if the object does not exist. */
    @throws[NotFoundException]
    @throws[InternalObjectMapperException]
    @throws[ConcurrentModificationException]
    def delete(clazz: Class[_], id: ObjId, ignoresNeo: Boolean = false): Unit

    /** Creates a new data node as part of the current transaction. */
    @throws[StorageNodeExistsException]
    def createNode(path: String, value: String = null): Unit

    /** Updates a data node as part of the current transaction. */
    @throws[StorageNodeNotFoundException]
    def updateNode(path: String, value: String): Unit

    /** Deletes a data node as part of the current transaction. */
    def deleteNode(path: String, idempotent: Boolean = true): Unit

    /** Commits the operations from the current transaction to the storage
      * backend. */
    @throws[InternalObjectMapperException]
    @throws[ConcurrentModificationException]
    @throws[ReferenceConflictException]
    @throws[ObjectExistsException]
    @throws[StorageNodeExistsException]
    @throws[StorageNodeNotFoundException]
    def commit(): Unit

}

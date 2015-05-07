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

import scala.concurrent.Future

import rx.Observable

import org.midonet.cluster.data.{Obj, ObjId}
import org.midonet.cluster.data.storage.OwnershipType.OwnershipType

/**
 * A trait defining the methods for ownership-based storage.
 */
trait StorageWithOwnership extends Storage {

    /**
     * Registers an object class with support for ownership. The ownership type
     * can be one of the following:
     * - [[OwnershipType.Exclusive]] Every object in this class permits at most
     * one owner at any time. The owner identifier may be specified when the
     * object is created, or may be created without an owner and its ownership
     * may later be claimed by a subsequent Update operation. The ownership is
     * released either when the object is deleted, or when the current owner
     * disconnects from storage, orphaning the object. It is possible to update
     * the ownership both by the current owner, and when an object has been
     * orphaned.
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
     * - for [[OwnershipType.Exclusive]], the update succeeds if it is a
     *   regular owner-agnostic update, the object is not assigned an owner, the
     *   specified owner is the current owner of the object, or the object has
     *   been orphaned and has no owner.
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

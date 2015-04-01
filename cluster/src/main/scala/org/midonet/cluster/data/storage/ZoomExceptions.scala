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

import org.midonet.cluster.data.ObjId
import org.midonet.cluster.data.storage.TransactionManager.getIdString

/**
 * Catch-all wrapper for any non-runtime exception occurring in the
 * ZookeeperObjectMapper or below that is not due to caller error. This
 * indicates either data corruption or a bug. See cause for details.
 */
class InternalObjectMapperException private[storage](message: String,
                                                     cause: Throwable)
    extends StorageException(message, cause) {
    private[storage] def this(cause: Throwable) = this(null, cause)
    private[storage] def this(msg: String) = this(msg, null)
}

/**
 * Thrown when the StorageService receives a request while it is unable to
 * service requests.
 */
class ServiceUnavailableException(message: String)
    extends StorageException(message)

class NotFoundException (val clazz: Class[_], val id: ObjId)
    extends StorageException(
        if (id != None) s"There is no ${clazz.getSimpleName} with ID " +
                        s"${getIdString(clazz, id)}."
        else s"There is no ${clazz.getSimpleName} with the specified ID.")

class ObjectExistsException private[storage](val clazz: Class[_],
                                             val id: ObjId)
    extends StorageException(
        s"A(n) ${clazz.getSimpleName} with ID ${getIdString(clazz, id)} " +
        s"already exists.")

/**
 * Thrown by the ZookeeperObjectMapper when a caller attempts to delete
 * an object which cannot be deleted due to the fact that is still referenced
 * by another object via a binding whose DeleteAction for that class is
 * ERROR.
 *
 * For example:
 *
 * mapper.declareBinding(Bridge.class, "portIds", ERROR,
 * Port.class, "bridgeId", CLEAR)
 *
 * Bridge bridge = new Bridge();
 * mapper.create(bridge);
 *
 * Port port = new Port();
 * port.bridgeId = bridge.getId();
 * mapper.create(port); // Adds port's ID to bridge's portIds.
 *
 * // Throws ObjectReferencedException because it's still bound to port
 * // and has an ERROR DeleteAction.
 * mapper.delete(bridge);
 *
 * // Succeeds and removes port's ID from bridge's portIds because the
 * // Port -> Bridge binding has a CLEAR DeleteAction.
 * mapper.delete(port);
 */
class ObjectReferencedException private[storage](
        val referencedClass: Class[_],
        val referencedId: ObjId,
        val referencingClass: Class[_],
        val referencingId: ObjId) extends StorageException(
    s"Cannot delete the ${referencedClass.getSimpleName} with ID " +
    s"${getIdString(referencingClass, referencingId)} because it is still " +
    s"referenced by the ${referencingClass.getSimpleName} with ID " +
    s"${getIdString(referencingClass, referencingId)}.")

/**
 * Thrown by the ZookeeperObjectMapper in response to a create or update
 * request that would otherwise overwrite an existing non-list reference.
 * For example:
 *
 *   Port p1 = new Port();
 *   mapper.create(p1);
 *
 *   Port p2 = new Port();
 *   p2.peerId = p1.id;
 *
 *   mapper.create(p2); // Sets p1.peerId = p2.id
 *
 *   Port p3 = new Port();
 *   p3.peerId = p2.id
 *
 *   // Attempts to set p2.peerId = p3.id, but throws
 *   // ReferenceConflictException because p2.peerId is already set to p1.id.
 *   mapper.create(p3);
 *
 * This restriction does not apply to list references, which can accommodate
 * an arbitrary number of IDs.
 *
 * @param referencingClass
 *        Class of the object referenced by the primary target of the
 *        Create/Update operation. This is Port in the example above.
 *
 * @param referencingId
 *        Id of the object referenced by the primary target of the Create/Update
 *        operation. This is p2.id in the example above.
 *
 * @param referencingFieldName
 *        The name of the field by which the object referenced by the
 *        operation's primary target references a third object. The operation
 *        failed because this field was not null. This is peerId in the example
 *        above.
 *
 * @param referencedClass
 *        Class of object referenced by referencingObj. This is Port in the
 *        example above.
 *
 * @param referencedId
 *        ID of object referenced by referencingObj. This is p1.id in the
 *        example above.
 */
class ReferenceConflictException private[storage](
        val referencingClass: String, val referencingId: String,
        val referencingFieldName: String,
        val referencedClass: String, val referencedId: String)
    extends StorageException(
        s"Operation failed because the $referencingClass with ID " +
        s"$referencingId already references the $referencedClass with ID " +
        s"$referencedId via the field $referencingFieldName. This field can " +
        s"accommodate only one reference.")

/**
 * Thrown by [[ZookeeperObjectMapper]] when a state object is being acquired
 * by a new owner, when previously acquired by a different owner.
 */
class OwnershipConflictException private[storage](val clazz: String,
                                                  val id: String,
                                                  val currentOwner: Set[String],
                                                  val newOwner: String,
                                                  msg: String)
    extends StorageException(msg) {
    def this(clazz: String, id: String)
    = this(clazz, id, Set.empty, null,
           s"Object of class $clazz with ID $id cannot be modified because " +
           s"it is owned by a different owner")
    def this(clazz: String, id: String, owner: String)
    = this(clazz, id, Set.empty[String], owner,
           s"Object of class $clazz with ID $id cannot be modified by " +
           s"owner $owner")
    def this(clazz: String, id: String, currentOwner: Set[String], newOwner: String)
    = this(clazz, id, currentOwner, newOwner,
           s"Object of class $clazz with ID $id cannot be owned by $newOwner " +
           s"because it has owner(s) $currentOwner")
}

/**
 * Thrown when attempting to create a node that already exists.
 */
class StorageNodeExistsException private[storage](val path: String, msg: String)
    extends StorageException(msg) {
    def this(path: String) = this(path, s"There is already a node at $path.")
}

/** Thrown when attempting to delete or modify a node that does not exist. */
class StorageNodeNotFoundException private[storage](val path: String,
                                                    msg: String)
    extends StorageException(msg) {
    def this(path: String) = this(path, s"There is no node at $path.")
}

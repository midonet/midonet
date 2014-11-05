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

/**
 * Catch-all wrapper for any non-runtime exception occurring in the
 * ZookeeperObjectMapper or below that is not due to caller error. This
 * indicates either data corruption or a bug. See cause for details.
 */
class InternalObjectMapperException private[storage](val message: String,
                                                     val cause: Throwable)
    extends RuntimeException(message, cause) {
    private[storage] def this(cause: Throwable) = this(null, cause)
}

/**
 * Thrown when the StorageService receives a request while it is unable to
 * service requests.
 */
class ServiceUnavailableException(val message: String)
    extends RuntimeException(message)

class NotFoundException private[cluster](val clazz: Class[_], val id: ObjId)
    extends RuntimeException(
        if (id != None) s"There is no ${clazz.getSimpleName} with ID $id."
        else s"There is no ${clazz.getSimpleName} with the specified ID.")

class ObjectExistsException private[storage](val clazz: Class[_],
                                             val id: ObjId)
    extends RuntimeException(
        s"A(n) ${clazz.getSimpleName} with ID $id already exists.")

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
        val referencingId: ObjId) extends RuntimeException(
    s"Cannot delete the ${referencedClass.getSimpleName} with ID " +
    s"$referencingId because it is still referenced by the " +
    s"${referencingClass.getSimpleName} with ID $referencingId.")

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
 *        operation. This is p2.id in the exapmle above.
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
    extends RuntimeException(
        s"Operation failed because the $referencingClass with ID " +
        s"$referencingId. already references the " +
        s"$referencedClass with ID $referencedId via the field " +
        s"$referencingFieldName. This field can accommodate only one " +
        "reference.")

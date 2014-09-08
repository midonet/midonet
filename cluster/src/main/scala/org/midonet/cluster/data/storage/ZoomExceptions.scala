/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.storage

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

class NotFoundException private[storage](val clazz: Class[_], val id: ObjId)
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
        val referencedObj: Obj, val referencingClass: Class[_],
        val referencingId: ObjId) extends RuntimeException(
    s"Cannot delete $referencedObj because it is still referenced by the " +
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
 * @param referencingObj
 *        Object referenced by the primary target of the Create/Update
 *        operation. This is p2 in the example above.
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
        val referencingObj: Obj, val referencingFieldName: String,
        val referencedClass: String, val referencedId: String)
    extends RuntimeException(
        s"Operation failed because $referencingObj already references the " +
        s"$referencedClass with ID $referencedId via the field " +
        s"$referencingFieldName. This field can accommodate only one " +
        "reference.")

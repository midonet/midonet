/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.storage

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
class ObjectReferencedException (val referencedObj: Object,
                                 val referencingClass: Class[_],
                                 val referencingId: Object)
    extends Exception(
        s"""
           |Cannot delete $referencedObj because it is still referenced by the
           |${referencingClass.getSimpleName} with ID $referencingId.
         """.stripMargin)

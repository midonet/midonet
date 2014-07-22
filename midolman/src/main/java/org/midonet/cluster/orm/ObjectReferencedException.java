/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.orm;

/**
 * Thrown by the ZookeeperObjectMapper when a caller attempts to delete
 * an object which cannot be deleted due to the fact that is still referenced
 * by another object via a binding whose DeleteAction for that class is
 * ERROR.
 *
 * For example:
 *
 * mapper.declareBinding(Bridge.class, "portIds", ERROR,
 *                       Port.class, "bridgeId", CLEAR)
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
public class ObjectReferencedException extends Exception {

    private static final long serialVersionUID = -5818157223233080030L;

    private final Object referencedObj;
    private final Class<?> referencingClass;
    private final Object referencingId;

    public ObjectReferencedException(Object referencedObj,
                                     Class<?> referencingClass,
                                     Object referencingId) {
        super("Cannot delete "+referencedObj+" because it is still "+
              "referenced by the "+referencingClass.getSimpleName()+" with ID "+
              referencingId+".");
        this.referencedObj = referencedObj;
        this.referencingClass = referencingClass;
        this.referencingId = referencingId;
    }

    /**
     * Object which cannot be deleted due to being referenced by another object.
     * This is port in the example above.
     */
    public Object getReferencedObj() {
        return referencedObj;
    }

    /**
     * Class of object preventing the deletion. This is Bridge.class in the
     * example above.
     */
    public Class<?> getReferencingClass() {
        return referencingClass;
    }

    /**
     * ID of object preventing the deletion. This is bridge.id in the
     * example above.
     */
    public Object getReferencingId() {
        return referencingId;
    }
}

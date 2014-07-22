/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.orm;

import java.lang.reflect.Field;

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
 */
public class ReferenceConflictException extends Exception {

    private static final long serialVersionUID = 2117334227803555760L;

    private final Object referencingObj;
    private final Field referencingField;
    private final Class<?> referencedClass;
    private final Object referencedId;

    public ReferenceConflictException(Object referencingObj,
                                      Field referencingField,
                                      Class<?> referencedClass,
                                      Object referencedId) {
        super("Operation failed because "+referencingObj+" already " +
              "references the "+referencedClass.getSimpleName()+" with ID "+
              referencedId+" via the field "+referencingField.getName()+". "+
              "This field can accommodate only one reference.");
        this.referencingObj = referencingObj;
        this.referencingField = referencingField;
        this.referencedClass = referencedClass;
        this.referencedId = referencedId;
    }

    /**
     * Object referenced by the primary target of the Create/Update operation.
     * This is p2 in the example above.
     */
    public Object getReferencingObj() {
        return referencingObj;
    }

    /**
     * Field by which the object referenced by the operation's primary
     * target references a third object. The operation failed because
     * this field was not null. This is peerId in the example above.
     */
    public Field getReferencingField() {
        return referencingField;
    }

    /**
     * Class of object referenced by referencingObj. This is Port in the
     * example above.
     */
    public Class<?> getReferencedClass() {
        return referencedClass;
    }

    /**
     * ID of object referenced by referencingObj. This is p1.id in the
     * example above.
     */
    public Object getReferencedId() {
        return referencedId;
    }
}

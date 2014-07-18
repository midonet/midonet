/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.storage;

public class NotFoundException extends Exception {

    private static final long serialVersionUID = -5567854037856737924L;

    private final Class<?> clazz;
    private final Object id;

    public NotFoundException(Class<?> clazz, Object id) {
        this(clazz, id, null);
    }

    public NotFoundException(Class<?> clazz, Object id, Throwable cause) {
        super("There is no "+clazz.getSimpleName()+" with ID "+id+".", cause);
        this.clazz = clazz;
        this.id = id;
    }

    public Class<?> getClazz() {
        return clazz;
    }

    public Object getId() {
        return id;
    }
}

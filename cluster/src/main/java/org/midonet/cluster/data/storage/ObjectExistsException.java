/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.storage;

/**
 * Thrown when a caller attempts to use the ZookeeperObjectMapper to store
 * an object when an object of the same class with the same ID already
 * exists.
 */
public class ObjectExistsException extends Exception {

    private static final long serialVersionUID = 6075751393345072018L;

    private final Class<?> clazz;
    private final Object id;

    public ObjectExistsException(Class<?> clazz, Object id) {
        this(clazz, id, null);
    }

    public ObjectExistsException(Class<?> clazz, Object id, Throwable cause) {
        super("A(n) "+clazz.getSimpleName()+" with ID "+id+" already exists.",
              cause);
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

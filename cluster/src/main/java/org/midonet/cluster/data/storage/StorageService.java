/**
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.storage;

import scala.collection.immutable.List;

/**
 * An interface defining the cluster persistence service.
 */
public interface StorageService {
    /**
     * Persists the specified object to the storage. The object must have a
     * field named "id", and an appropriate unique ID must already be assigned
     * to the object before the call.
     */
    public void create(Object o)
            throws NotFoundException, ObjectExistsException,
                   ReferenceConflictException;

    /**
     * Updates the specified object in the storage.
     */
    public void update(Object newThisObj)
            throws NotFoundException, ReferenceConflictException;

    /**
     * Deletes the specified object from the storage.
     */
    public void delete(Class<?> clazz, Object id)
            throws NotFoundException, ObjectReferencedException;

    /**
     * Gets the specified instance of the specified class from the storage.
     */
    public <T> T get(Class<T> clazz, Object id) throws NotFoundException;

    /**
     * Gets all the instances of the specified class from the storage.
     */
    public <T> List<T> getAll(Class<T> clazz);

    /**
     * Returns true if the specified object exists in the storage.
     */
    public boolean exists(Class<?> clazz, Object id);
}

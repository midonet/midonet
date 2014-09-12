/**
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.storage;

import java.util.List;

import rx.Observable;
import rx.Observer;
import rx.Subscription;

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

    public <T> Subscription subscribe(Class<T> clazz, Object id,
                                      Observer<? super T> obs);

    public <T> Subscription subscribeAll(Class<T> clazz,
                                         Observer<? super Observable<T>> obs);

    public void multi(List<ZoomOp> ops);

    /* We should remove both methods, but first we must make ZOOM support
     * offline class registration so that we can register classes from the
     * guice modules without causing exceptions */
    public void registerClass(Class<?> clazz);

    public boolean isRegistered(Class<?> clazz);

}

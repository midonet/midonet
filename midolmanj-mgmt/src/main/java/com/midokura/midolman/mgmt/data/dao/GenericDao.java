/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dao;

import com.midokura.midolman.state.InvalidStateOperationException;
import com.midokura.midolman.state.StateAccessException;

import java.io.Serializable;

/**
 * Generic DAO interface for CRUD operations
 */
public interface GenericDao <T, PK extends Serializable> {

    /**
     * Create a new instance of obj
     * @param obj
     * @return
     * @throws StateAccessException
     * @throws InvalidStateOperationException
     */
    PK create(T obj) throws StateAccessException,
            InvalidStateOperationException;

    /**
     * Get an object with id
     * @param id
     * @return
     * @throws StateAccessException
     */
    T get(PK id) throws StateAccessException;

    /**
     * Update the object.
     * @param obj
     * @throws StateAccessException
     * @throws InvalidStateOperationException
     */
    void update(T obj)
            throws StateAccessException, InvalidStateOperationException;

    /**
     * Delete the object with id.
     * @param id
     * @throws StateAccessException
     * @throws InvalidStateOperationException
     */
    void delete(PK id)
            throws StateAccessException, InvalidStateOperationException;

}

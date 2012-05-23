/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dao;

import com.midokura.midolman.state.StateAccessException;

/**
 * Application level data access interface. This DAO specifies the interfaces
 * used to manage the data applicable to the whole application.
 */
public interface ApplicationDao {

    /**
     * Initialize the data store of the application.
     *
     * @throws StateAccessException
     *             Data access error occurred.
     */
    void initialize() throws StateAccessException;

}

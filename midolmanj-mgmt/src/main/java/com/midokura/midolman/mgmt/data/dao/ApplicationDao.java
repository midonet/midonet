/*
 * @(#)AdminDao        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao;

import com.midokura.midolman.state.StateAccessException;

/**
 * Application level data access interface. This DAO specifies the interfaces
 * used to manage the data applicable to the whole application.
 *
 * @version 1.6 08 Sept 2011
 * @author Ryu Ishimoto
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

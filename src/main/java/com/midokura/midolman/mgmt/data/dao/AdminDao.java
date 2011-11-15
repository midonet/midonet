/*
 * @(#)AdminDao        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao;

import com.midokura.midolman.state.StateAccessException;

public interface AdminDao {

    void initialize() throws StateAccessException;

}

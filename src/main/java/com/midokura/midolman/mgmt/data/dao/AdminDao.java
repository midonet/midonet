package com.midokura.midolman.mgmt.data.dao;

import com.midokura.midolman.state.StateAccessException;

public interface AdminDao {

    void initialize() throws StateAccessException;

}

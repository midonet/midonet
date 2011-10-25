package com.midokura.midolman.mgmt.data.dao;

import com.midokura.midolman.state.StateAccessException;

public interface TenantDao {

    void delete(String id) throws StateAccessException;

    String create() throws StateAccessException;

    String create(String id) throws StateAccessException;
}

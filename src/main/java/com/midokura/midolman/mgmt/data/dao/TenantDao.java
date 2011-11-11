package com.midokura.midolman.mgmt.data.dao;

import java.util.List;

import com.midokura.midolman.mgmt.data.dto.Tenant;
import com.midokura.midolman.state.StateAccessException;

public interface TenantDao {

    List<Tenant> list() throws StateAccessException;

    void delete(String id) throws StateAccessException;

    String create() throws StateAccessException;

    String create(String id) throws StateAccessException;
}

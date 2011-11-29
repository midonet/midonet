/*
 * @(#)TenantDao        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.List;

import com.midokura.midolman.mgmt.data.dto.Tenant;
import com.midokura.midolman.state.StateAccessException;

public interface TenantDao {

    List<Tenant> list() throws StateAccessException;

    Tenant getTenant(String id) throws StateAccessException;

    void delete(String id) throws StateAccessException;

    String create() throws StateAccessException;

    String create(String id) throws StateAccessException;
}

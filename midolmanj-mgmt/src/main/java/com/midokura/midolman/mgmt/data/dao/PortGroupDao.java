/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.PortGroup;
import com.midokura.midolman.state.StateAccessException;

/**
 * PortGroup DAO interface.
 */
public interface PortGroupDao extends GenericDao<PortGroup, UUID> {

    /**
     * @param tenantId
     *            ID of the tenant to which the port group belongs.
     * @param name
     *            Name of the PortGroup to fetch.
     * @return PortGroup object.
     * @throws com.midokura.midolman.state.StateAccessException
     *             Data access error.
     */
    PortGroup findByName(String tenantId, String name)
            throws StateAccessException;

    /**
     * List all of a tenant's PortGroups
     *
     * @param tenantId
     *            ID of the tenant to which the groups belongs.
     * @return A list of PortGroup objects.
     * @throws com.midokura.midolman.state.StateAccessException
     *             Data access error.
     */
    List<PortGroup> findByTenant(String tenantId) throws StateAccessException;

}
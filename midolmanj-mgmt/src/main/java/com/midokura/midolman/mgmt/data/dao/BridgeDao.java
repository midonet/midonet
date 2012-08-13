/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.Bridge;
import com.midokura.midolman.state.StateAccessException;

/**
 * Bridge DAO interface.
 */
public interface BridgeDao extends GenericDao<Bridge, UUID> {

    /**
     * Get a bridge by tenant ID and bridge name.
     *
     * @param tenantId
     *            ID of the tenant
     * @param name
     *            bridge name
     * @return Bridge DTO
     */
    Bridge findByName(String tenantId, String name) throws
            StateAccessException;

    /**
     * Get brige by port.
     *
     * @param portId
     *            ID of the port to get the Bridge from.
     * @return Bridge object.
     * @throws StateAccessException
     *             Data access error.
     */
    Bridge findByPort(UUID portId) throws StateAccessException;

    /**
     * List bridges.
     *
     * @param tenantId
     *            Tenant ID to get the bridges for.
     * @return A list of Bridge objects.
     * @throws StateAccessException
     *             Data access error.
     */
    List<Bridge> findByTenant(String tenantId) throws StateAccessException;

}

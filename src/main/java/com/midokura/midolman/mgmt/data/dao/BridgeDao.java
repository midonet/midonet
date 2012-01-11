/*
 * @(#)BridgeDao        1.6 12/1/10
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.Bridge;
import com.midokura.midolman.state.StateAccessException;

/**
 * Bridge DAO interface.
 *
 * @version 1.6 10 Jan 2012
 * @author Ryu Ishimoto
 */
public interface BridgeDao {

    /**
     * Create a new Bridge.
     *
     * @param bridge
     *            Bridge to create.
     * @return The new UUID.
     * @throws StateAccessException
     *             Data access exception.
     */
    UUID create(Bridge bridge) throws StateAccessException;

    /**
     * Delete the bridge with the given ID.
     *
     * @param id
     *            Bridge ID
     * @throws StateAccessException
     *             Data access error.
     */
    void delete(UUID id) throws StateAccessException;

    /**
     * Get the bridge with the given ID.
     *
     * @param id
     *            ID of th bridge object.
     * @return Bridge object.
     * @throws StateAccessException
     *             Data access exception.
     */
    Bridge get(UUID id) throws StateAccessException;

    /**
     * Get brige by port.
     *
     * @param portId
     *            ID of the port to get the Bridge from.
     * @return Bridge object.
     * @throws StateAccessException
     *             Data access error.
     */
    Bridge getByPort(UUID portId) throws StateAccessException;

    /**
     * List bridges.
     *
     * @param tenantId
     *            Tenant ID to get the bridges for.
     * @return A list of Bridge objects.
     * @throws StateAccessException
     *             Data access error.
     */
    List<Bridge> list(String tenantId)
            throws StateAccessException;

    /**
     * Update a bridge.
     *
     * @param bridge
     *            Bridge to update
     * @throws StateAccessException
     *             Data access error.
     */
    void update(Bridge bridge) throws StateAccessException;

}

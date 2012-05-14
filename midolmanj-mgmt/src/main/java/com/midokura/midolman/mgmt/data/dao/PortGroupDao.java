/*
 * @(#)ChainDao        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.PortGroup;
import com.midokura.midolman.state.StateAccessException;

/**
 * PortGroup DAO interface.
 */
public interface PortGroupDao {

    /**
     * Create a new port group.
     *
     * @param group
     *            PortGroup object to create.
     * @return new group's UUID.
     * @throws com.midokura.midolman.state.StateAccessException
     *             Data access error.
     */
    UUID create(PortGroup group) throws StateAccessException;

    /**
     * Delete a port group.
     *
     * @param id
     *            ID of the PortGroup to delete.
     * @throws com.midokura.midolman.state.StateAccessException
     *             Data access error.
     */
    void delete(UUID id) throws StateAccessException;

    /**
     * @param id
     *            ID of the group to get.
     * @return PortGroup object.
     * @throws com.midokura.midolman.state.StateAccessException
     *             Data access error.
     */
    PortGroup get(UUID id) throws StateAccessException;

    /**
     * @param tenantId
     *            ID of the tenant to which the port group belongs.
     * @param name
     *            Name of the PortGroup to fetch.
     * @return PortGroup object.
     * @throws com.midokura.midolman.state.StateAccessException
     *             Data access error.
     */
    PortGroup get(String tenantId, String name)
            throws StateAccessException;

    /**
     * List all of a tenant's PortGroups
     * @param tenantId
     *            ID of the tenant to which the groups belongs.
     * @return A list of PortGroup objects.
     * @throws com.midokura.midolman.state.StateAccessException
     *             Data access error.
     */
    List<PortGroup> list(String tenantId)
            throws StateAccessException;
}
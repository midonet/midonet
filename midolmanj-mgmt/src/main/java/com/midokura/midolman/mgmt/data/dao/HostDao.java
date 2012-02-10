/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.Host;
import com.midokura.midolman.mgmt.data.dto.Interface;
import com.midokura.midolman.state.StateAccessException;

/**
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 1/30/12
 */
public interface HostDao {

    /**
     * Delete a host.
     *
     * @param id ID of the host we want to delete.
     * @throws StateAccessException if a data access error occurs or the host
     *                              entry can't be deleted because it's alive.
     */
    void delete(UUID id) throws StateAccessException;

    /**
     * Get a host information.
     *
     * @param id ID of the host we want to get.
     * @return Host object.
     * @throws StateAccessException if a data access error occurs.
     */
    Host get(UUID id) throws StateAccessException;

    /**
     * List hosts.
     *
     * @return A list of Host objects (that can be modified).
     * @throws StateAccessException if a data access error occurs.
     */
    List<Host> list() throws StateAccessException;

    /**
     * Add an interface description to a host
     * 
     * @param hostId      the host uuid
     * @param anInterface the interface description
     *
     * @throws StateAccessException if a data access error occurs.
     * @return the UUID of the created interface
     */
    UUID createInterface(UUID hostId, Interface anInterface) throws StateAccessException;

    /**
     * Remove an interface description from a host.
     *
     * @param hostId      the host uuid
     * @param interfaceId the interface uuid
     * @throws StateAccessException if the deletion fails
     */
    void deleteInterface(UUID hostId, UUID interfaceId)
        throws StateAccessException;

    /**
     * Lists all the interface information available on a host.
     *
     * @param hostId the host uuid
     * @return the list of interface data available on a host (that can be modified).
     * @throws StateAccessException if the operation fails
     */
    List<Interface> listInterfaces(UUID hostId) throws StateAccessException;

    /**
     * Returns the information about an interface available on a host.
     *
     * @param hostId      the host uuid
     * @param interfaceId the interface uuid
     * @return an object encapsulating interface data
     * @throws StateAccessException if the operation fails
     */
    Interface getInterface(UUID hostId, UUID interfaceId)
        throws StateAccessException;
}

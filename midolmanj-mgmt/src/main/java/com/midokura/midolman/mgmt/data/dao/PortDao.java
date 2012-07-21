/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.Port;
import com.midokura.midolman.mgmt.data.zookeeper.dao.PortInUseException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Port data accessor interface.
 */
public interface PortDao {

    /**
     * Create a port.
     *
     * @param port
     *            Port object to create.
     * @return New port ID
     * @throws StateAccessException
     *             Data access error.
     */
    UUID create(Port port) throws StateAccessException;

    /**
     * Delete a port.
     *
     * @param id
     *            ID of the port to delete.
     * @throws StateAccessException
     *             Data access error.
     * @throws PortInUseException
     *             Attempt to delete a used port.
     */
    void delete(UUID id) throws StateAccessException, PortInUseException;

    /**
     * Check if a port exists.
     *
     * @param id
     *            ID of the port to check.
     * @return True if the port exists. False otherwise.
     * @throws StateAccessException
     *             Data access error.
     */
    boolean exists(UUID id) throws StateAccessException;

    /**
     * Get a port.
     *
     * @param id
     *            ID of the port to get.
     * @return Port object.
     * @throws StateAccessException
     *             Data access error.
     */
    Port get(UUID id) throws StateAccessException;

    /**
     * Get Port by AdRoute.
     *
     * @param adRouteId
     * @return
     * @throws StateAccessException
     */
    Port getByAdRoute(UUID adRouteId) throws StateAccessException;

    /**
     * Get Port by BGP.
     *
     * @param bgpId
     * @return
     * @throws StateAccessException
     */
    Port getByBgp(UUID bgpId) throws StateAccessException;

    /**
     * Get Port by VPN.
     *
     * @param vpnId
     * @return
     * @throws StateAccessException
     */
    Port getByVpn(UUID vpnId) throws StateAccessException;

    /**
     * Link two logical ports
     *
     * @param id
     *            Port ID
     * @param peerId
     *            Peer port ID
     * @throws StateAccessException
     * @throws PortInUseException
     *             Attempt to link used ports.
     */
    void link(UUID id, UUID peerId) throws StateAccessException,
            PortInUseException;

    /**
     * Get bridge ports.
     *
     * @return List of Port objects.
     * @throws StateAccessException
     *             Data access error.
     */
    List<Port> listBridgePorts(UUID bridgeId) throws StateAccessException;

    /**
     * Get bridge peer ports.
     *
     * @return List of peer Port objects.
     * @throws StateAccessException
     *             Data access error.
     */
    List<Port> listBridgePeerPorts(UUID bridgeId) throws StateAccessException;

    /**
     * Get router ports.
     *
     * @param routerId
     *            ID of the router to get the ports of.
     * @return List of Port objects.
     * @throws StateAccessException
     *             Data access error.
     */
    List<Port> listRouterPorts(UUID routerId) throws StateAccessException;

    /**
     * Get router peer ports.
     *
     * @return List of peer Port objects.
     * @throws StateAccessException
     *             Data access error.
     */
    List<Port> listRouterPeerPorts(UUID routerId) throws StateAccessException;

    /**
     * Unlink two logical ports
     *
     * @param id
     *            Port ID
     * @throws StateAccessException
     */
    void unlink(UUID id) throws StateAccessException;

    /**
     * Update the Port whose ID is specified in the Port DTO.
     *
     * @param port
     *            New port configuration.
     * @throws StateAccessException
     */
    void update(Port port) throws StateAccessException;
}

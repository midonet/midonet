/*
 * @(#)RouterDao        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.Router;
import com.midokura.midolman.state.StateAccessException;

/**
 * Data access class for Router.
 *
 * @version 1.6 29 Nov 2011
 * @author Ryu Ishimoto
 */
public interface RouterDao {

    /**
     * Create a Router.
     *
     * @param router
     *            Router to create.
     * @return Router ID.
     * @throws StateAccessException
     *             Data Access error.
     */
    UUID create(Router router) throws StateAccessException;

    /**
     * Delete a Router.
     *
     * @param id
     *            ID of the Router to delete.
     * @throws StateAccessException
     *             Data Access error.
     */
    void delete(UUID id) throws StateAccessException;

    /**
     * Get a Router by ID.
     *
     * @param id
     *            ID of the Router to get.
     * @return the Router DTO with the router's configuration.
     * @throws StateAccessException
     *             Data Access error.
     */
    Router get(UUID id) throws StateAccessException;

    /**
     * Get the Router that owns the given Advertised Route ID.
     *
     * @param adRouteId
     * @return the Router DTO with the router's configuration.
     * @throws StateAccessException
     */
    Router getByAdRoute(UUID adRouteId) throws StateAccessException;

    /**
     * Get the Router that owns the given BGP ID.
     *
     * @param bgpId
     * @return the Router DTO with the router's configuration.
     * @throws StateAccessException
     */
    Router getByBgp(UUID bgpId) throws StateAccessException;

    /**
     * Get the Router that owns the given Port ID.
     *
     * @param portId
     * @return the Router DTO with the router's configuration.
     * @throws StateAccessException
     */
    Router getByPort(UUID portId) throws StateAccessException;

    /**
     * Get the Router that owns the given Route ID.
     *
     * @param routeId
     * @return the Router DTO with the router's configuration.
     * @throws StateAccessException
     */
    Router getByRoute(UUID routeId) throws StateAccessException;

    /**
     * Get the Router that owns the given VPN ID.
     *
     * @param vpnId
     * @return the Router DTO with the router's configuration.
     * @throws StateAccessException
     */
    Router getByVpn(UUID vpnId) throws StateAccessException;

    /**
     * List all the Routers onwed by a specified Tenant.
     *
     * @return A list of Router objects.
     * @throws StateAccessException
     *             Data Access error.
     */
    List<Router> list(String tenantId)
            throws StateAccessException;

    /**
     * Update the Router whose ID is specified in the Router DTO.
     *
     * @param router
     *          New Router configuration.
     * @throws StateAccessException
     */
    void update(Router router) throws StateAccessException;
}

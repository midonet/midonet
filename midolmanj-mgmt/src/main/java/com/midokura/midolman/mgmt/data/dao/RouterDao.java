/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.Router;
import com.midokura.midolman.state.StateAccessException;

/**
 * Data access class for Router.
 */
public interface RouterDao extends GenericDao<Router, UUID> {

    /**
     * Get a Router by tenant ID and router name.
     *
     * @param tenantId
     *            ID of the tenant
     * @param name
     *            Router name
     * @return Router DTO
     */
    Router findByName(String tenantId, String name) throws
            StateAccessException;

    /**
     * Get the Router that owns the given Advertised Route ID.
     *
     * @param adRouteId
     * @return the Router DTO with the router's configuration.
     * @throws StateAccessException
     */
    Router findByAdRoute(UUID adRouteId) throws StateAccessException;

    /**
     * Get the Router that owns the given BGP ID.
     *
     * @param bgpId
     * @return the Router DTO with the router's configuration.
     * @throws StateAccessException
     */
    Router findByBgp(UUID bgpId) throws StateAccessException;

    /**
     * Get the Router that owns the given Port ID.
     *
     * @param portId
     * @return the Router DTO with the router's configuration.
     * @throws StateAccessException
     */
    Router findByPort(UUID portId) throws StateAccessException;

    /**
     * Get the Router that owns the given Route ID.
     *
     * @param routeId
     * @return the Router DTO with the router's configuration.
     * @throws StateAccessException
     */
    Router findByRoute(UUID routeId) throws StateAccessException;

    /**
     * Get the Router that owns the given VPN ID.
     *
     * @param vpnId
     * @return the Router DTO with the router's configuration.
     * @throws StateAccessException
     */
    Router findByVpn(UUID vpnId) throws StateAccessException;

    /**
     * List all the Routers onwed by a specified Tenant.
     *
     * @return A list of Router objects.
     * @throws StateAccessException
     *             Data Access error.
     */
    List<Router> findByTenant(String tenantId) throws StateAccessException;

}

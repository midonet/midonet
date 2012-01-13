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
     * @param Router
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
     * Get a Router.
     *
     * @param id
     *            ID of the Router to get.
     * @return Route object.
     * @throws StateAccessException
     *             Data Access error.
     */
    Router get(UUID id) throws StateAccessException;

    /**
     * @param adRouteId
     * @return
     * @throws StateAccessException
     */
    Router getByAdRoute(UUID adRouteId) throws StateAccessException;

    /**
     * @param bgpId
     * @return
     * @throws StateAccessException
     */
    Router getByBgp(UUID bgpId) throws StateAccessException;

    /**
     * @param chainId
     * @return
     * @throws StateAccessException
     */
    Router getByChain(UUID chainId) throws StateAccessException;

    /**
     * @param portId
     * @return
     * @throws StateAccessException
     */
    Router getByPort(UUID portId) throws StateAccessException;

    /**
     * @param routeId
     * @return
     * @throws StateAccessException
     */
    Router getByRoute(UUID routeId) throws StateAccessException;

    /**
     * @param ruleId
     * @return
     * @throws StateAccessException
     */
    Router getByRule(UUID ruleId) throws StateAccessException;

    /**
     * @param vpnId
     * @return
     * @throws StateAccessException
     */
    Router getByVpn(UUID vpnId) throws StateAccessException;

    /**
     * List Routers.
     *
     * @return A list of Router objects.
     * @throws StateAccessException
     *             Data Access error.
     */
    List<Router> list(String tenantId)
            throws StateAccessException;

    /**
     * Update Router.
     *
     * @return Router object to update.
     * @throws StateAccessException
     *             Data Access error.
     */
    void update(Router router) throws StateAccessException;
}

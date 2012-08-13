/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.Tenant;
import com.midokura.midolman.state.StateAccessException;

/**
 * Tenant DAO.
 */
public interface TenantDao extends GenericDao<Tenant, String> {

    /**
     * @param adRouteId
     * @return
     * @throws StateAccessException
     */
    Tenant findByAdRoute(UUID adRouteId) throws StateAccessException;

    /**
     * @param bgpId
     * @return
     * @throws StateAccessException
     */
    Tenant findByBgp(UUID bgpId) throws StateAccessException;

    /**
     * @param bridgeId
     * @return
     * @throws StateAccessException
     */
    Tenant findByBridge(UUID bridgeId) throws StateAccessException;

    /**
     * @param chainId
     * @return
     * @throws StateAccessException
     */
    Tenant findByChain(UUID chainId) throws StateAccessException;

    /**
     *
     * @param groupId
     * @return
     * @throws StateAccessException
     */
    Tenant findByPortGroup(UUID groupId) throws StateAccessException;

    /**
     * @param portId
     * @return
     * @throws StateAccessException
     */
    Tenant findByPort(UUID portId) throws StateAccessException;

    /**
     * @param routeId
     * @return
     * @throws StateAccessException
     */
    Tenant findByRoute(UUID routeId) throws StateAccessException;

    /**
     * @param routerId
     * @return
     * @throws StateAccessException
     */
    Tenant findByRouter(UUID routerId) throws StateAccessException;

    /**
     * @param ruleId
     * @return
     * @throws StateAccessException
     */
    Tenant findByRule(UUID ruleId) throws StateAccessException;

    /**
     * @param vpnId
     * @return
     * @throws StateAccessException
     */
    Tenant findByVpn(UUID vpnId) throws StateAccessException;

    /**
     * Get all the Tenant objects.
     *
     * @return List of Tenant DTOs.
     * @throws StateAccessException
     *             Data access error.
     */
    List<Tenant> findAll() throws StateAccessException;
}

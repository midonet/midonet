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
public interface TenantDao {

    /**
     * Create a new tenant.
     *
     * @param tenant
     *            Tenant DTO.
     * @return ID of the new tenant.
     * @throws StateAccessException
     *             Data access error.
     */
    String create(Tenant tenant) throws StateAccessException;

    /**
     * Delete a tenant.
     *
     * @param id
     *            ID of the tenant to delete.
     * @throws StateAccessException
     *             Data access error.
     */
    void delete(String id) throws StateAccessException;

    /**
     * Get the tenant with the speicfied ID.
     *
     * @param id
     *            Tenant ID
     * @return Tenant object
     * @throws StateAccessException
     *             Data access error.
     */
    Tenant get(String id) throws StateAccessException;

    /**
     * @param adRouteId
     * @return
     * @throws StateAccessException
     */
    Tenant getByAdRoute(UUID adRouteId) throws StateAccessException;

    /**
     * @param bgpId
     * @return
     * @throws StateAccessException
     */
    Tenant getByBgp(UUID bgpId) throws StateAccessException;

    /**
     * @param bridgeId
     * @return
     * @throws StateAccessException
     */
    Tenant getByBridge(UUID bridgeId) throws StateAccessException;

    /**
     * @param chainId
     * @return
     * @throws StateAccessException
     */
    Tenant getByChain(UUID chainId) throws StateAccessException;

    /**
     *
     * @param groupId
     * @return
     * @throws StateAccessException
     */
    Tenant getByPortGroup(UUID groupId) throws StateAccessException;

    /**
     * @param portId
     * @return
     * @throws StateAccessException
     */
    Tenant getByPort(UUID portId) throws StateAccessException;

    /**
     * @param routeId
     * @return
     * @throws StateAccessException
     */
    Tenant getByRoute(UUID routeId) throws StateAccessException;

    /**
     * @param routerId
     * @return
     * @throws StateAccessException
     */
    Tenant getByRouter(UUID routerId) throws StateAccessException;

    /**
     * @param ruleId
     * @return
     * @throws StateAccessException
     */
    Tenant getByRule(UUID ruleId) throws StateAccessException;

    /**
     * @param vpnId
     * @return
     * @throws StateAccessException
     */
    Tenant getByVpn(UUID vpnId) throws StateAccessException;

    /**
     * Get all the Tenant objects.
     *
     * @return List of Tenant DTOs.
     * @throws StateAccessException
     *             Data access error.
     */
    List<Tenant> list() throws StateAccessException;
}

/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.Chain;
import com.midokura.midolman.state.StateAccessException;

/**
 * Chain DAO interface.
 */
public interface ChainDao extends GenericDao<Chain, UUID> {

    /**
     * @param tenantId
     *            ID of the tenant to which the chain belongs.
     * @param name
     *            Name of the chain to fetch.
     * @return Chain object.
     * @throws StateAccessException
     *             Data access error.
     */
    Chain findByName(String tenantId, String name) throws StateAccessException;

    /**
     * Get Chain by rule ID.
     *
     * @param ruleId
     *            Rule ID to get the chain from.
     * @return Chain object.
     * @throws StateAccessException
     *             Data access error.
     */
    Chain findByRule(UUID ruleId) throws StateAccessException;

    /**
     * @param tenantId
     *            ID of the tenant to which the chain belongs.
     * @return A list of Chain objects.
     * @throws StateAccessException
     *             Data access error.
     */
    List<Chain> findByTenant(String tenantId) throws StateAccessException;

}
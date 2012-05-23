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
public interface ChainDao {

    /**
     * Create a new chain.
     *
     * @param chain
     *            Chain object to create.
     * @return Chain object.
     * @throws StateAccessException
     *             Data access error.
     */
    UUID create(Chain chain) throws StateAccessException;

    /**
     * Delete a chain.
     *
     * @param id
     *            ID of the chain to delete.
     * @throws StateAccessException
     *             Data access error.
     */
    void delete(UUID id) throws StateAccessException;

    /**
     * @param id
     *            ID of the chain to get.
     * @return Chain object.
     * @throws StateAccessException
     *             Data access error.
     */
    Chain get(UUID id) throws StateAccessException;

    /**
     * @param tenantId
     *            ID of the tenant to which the chain belongs.
     * @param name
     *            Name of the chain to fetch.
     * @return Chain object.
     * @throws StateAccessException
     *             Data access error.
     */
    Chain get(String tenantId, String name)
            throws StateAccessException;

    /**
     * Get Chain by rule ID.
     *
     * @param ruleId
     *            Rule ID to get the chain from.
     * @return Chain object.
     * @throws StateAccessException
     *             Data access error.
     */
    Chain getByRule(UUID ruleId) throws StateAccessException;

    /**
     * @param tenantId
     *            ID of the tenant to which the chain belongs.
     * @return A list of Chain objects.
     * @throws StateAccessException
     *             Data access error.
     */
    List<Chain> list(String tenantId)
            throws StateAccessException;
}
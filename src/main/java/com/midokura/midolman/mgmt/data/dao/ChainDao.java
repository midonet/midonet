/*
 * @(#)ChainDao        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.Chain;
import com.midokura.midolman.mgmt.rest_api.core.ChainTable;
import com.midokura.midolman.state.StateAccessException;
/**
 * Chain DAO interface.
 *
 * @version 1.6 29 Nov 2011
 * @author Ryu Ishimoto
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
     * Create a list of built-in Chains belonging to a router.
     *
     * @param routerId
     *            Router ID to create the Chains for.
     * @return A list of Chain objects with randomly generated ID.
     */
    List<Chain> generateBuiltInChains(UUID routerId);

    /**
     * @param id
     *            ID of the chain to get.
     * @return Chain object.
     * @throws StateAccessException
     *             Data access error.
     */
    Chain get(UUID id) throws StateAccessException;

    /**
     * @param routerId
     *            ID of the router in which the chain belongs to.
     * @param table
     *            Name of the table in which the chain belongs to.
     * @param name
     *            Name of the chain to fetch.
     * @return Chain object.
     * @throws StateAccessException
     *             Data access error.
     */
    Chain get(UUID routerId, ChainTable table, String name)
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
     * @param routerId
     *            ID of the router for which to get the chains.
     * @return A list of Chain objects.
     * @throws StateAccessException
     *             Data access error.
     */
    List<Chain> list(UUID routerId) throws StateAccessException;

    /**
     * @param routerId
     *            ID of the router in which the chain belongs to.
     * @param table
     *            Name of the table in which the chain belongs to.
     * @return A list of Chain objects.
     * @throws StateAccessException
     *             Data access error.
     */
    List<Chain> list(UUID routerId, ChainTable table)
            throws StateAccessException;
}
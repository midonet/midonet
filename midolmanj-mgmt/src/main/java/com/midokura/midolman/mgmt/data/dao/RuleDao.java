/*
 * @(#)RuleDao        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.Rule;
import com.midokura.midolman.state.RuleIndexOutOfBoundsException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Data access class for Rule.
 *
 * @version 1.6 29 Nov 2011
 * @author Ryu Ishimoto
 */
public interface RuleDao {

    /**
     * Create a Rule.
     *
     * @param Rule
     *            Rule to create.
     * @return Rule ID.
     * @throws StateAccessException
     *             Data Access error.
     */
    UUID create(Rule rule) throws RuleIndexOutOfBoundsException,
            StateAccessException;

    /**
     * Delete a Rule.
     *
     * @param id
     *            ID of the Rule to delete.
     * @throws StateAccessException
     *             Data Access error.
     */
    void delete(UUID id) throws StateAccessException;

    /**
     * Get a Rule.
     *
     * @param id
     *            ID of the Rule to get.
     * @return Route object.
     * @throws StateAccessException
     *             Data Access error.
     */
    Rule get(UUID id) throws StateAccessException;

    /**
     * List Rules.
     *
     * @return A list of Rule objects.
     * @throws StateAccessException
     *             Data Access error.
     */
    List<Rule> list(UUID chainId) throws StateAccessException;
}

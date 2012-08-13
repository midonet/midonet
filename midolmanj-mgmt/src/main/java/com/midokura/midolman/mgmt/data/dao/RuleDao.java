/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.Rule;
import com.midokura.midolman.state.RuleIndexOutOfBoundsException;
import com.midokura.midolman.state.StateAccessException;

/**
 * Data access class for Rule.
 */
public interface RuleDao extends GenericDao<Rule, UUID> {

    /**
     * List Rules.
     *
     * @return A list of Rule objects.
     * @throws StateAccessException
     *             Data Access error.
     */
    List<Rule> findByChain(UUID chainId) throws StateAccessException;
}

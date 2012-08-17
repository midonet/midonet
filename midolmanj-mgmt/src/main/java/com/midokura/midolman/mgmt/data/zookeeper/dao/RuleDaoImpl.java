/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.zookeeper.dao;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.google.inject.Inject;
import com.midokura.midolman.mgmt.data.dao.RuleDao;
import com.midokura.midolman.mgmt.data.dto.Rule;
import com.midokura.midolman.state.InvalidStateOperationException;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.RuleIndexOutOfBoundsException;
import com.midokura.midolman.state.zkManagers.RuleZkManager;
import com.midokura.midolman.state.StateAccessException;

/**
 * Data access class for rules.
 */
public class RuleDaoImpl implements RuleDao {

    private final RuleZkManager dataAccessor;

    /**
     * Constructor.
     *
     * @param dataAccessor
     *            Rule data accessor.
     */
    @Inject
    public RuleDaoImpl(RuleZkManager dataAccessor) {
        this.dataAccessor = dataAccessor;
    }

    @Override
    public void delete(UUID id) throws StateAccessException {
        dataAccessor.delete(id);
    }

    @Override
    public UUID create(Rule rule)
            throws StateAccessException, RuleIndexOutOfBoundsException {
        return dataAccessor.create(rule.toZkRule());
    }

    @Override
    public Rule get(UUID id) throws StateAccessException {
        try {
            return new Rule(id, dataAccessor.get(id));
        } catch (NoStatePathException e) {
            return null;
        }
    }

    @Override
    public void update(Rule obj)
            throws StateAccessException, InvalidStateOperationException {
        throw new UnsupportedOperationException();
    }


    @Override
    public List<Rule> findByChain(UUID chainId) throws StateAccessException {
        List<Rule> rules = new ArrayList<Rule>();
        Set<UUID> ruleIds = dataAccessor.getRuleIds(chainId);
        for (UUID ruleId : ruleIds) {
            rules.add(new Rule(ruleId, dataAccessor.get(ruleId)));
        }
        return rules;
    }
}

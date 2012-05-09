/*
 * @(#)RuleZkProxy        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao.zookeeper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dao.RuleDao;
import com.midokura.midolman.mgmt.data.dto.Rule;
import com.midokura.midolman.state.NoStatePathException;
import com.midokura.midolman.state.RuleIndexOutOfBoundsException;
import com.midokura.midolman.state.RuleZkManager;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkNodeEntry;

/**
 * Data access class for rules.
 *
 * @version 1.6 08 Sept 2011
 * @author Ryu Ishimoto
 */
public class RuleZkProxy implements RuleDao {

    private final RuleZkManager dataAccessor;

    /**
     * Constructor.
     *
     * @param dataAccessor
     *            Rule data accessor.
     */
    public RuleZkProxy(RuleZkManager dataAccessor) {
        this.dataAccessor = dataAccessor;
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.RuleDao#create(com.midokura.midolman
     * .mgmt.data.dto.Rule)
     */
    @Override
    public UUID create(Rule rule, UUID jumpChainID)
            throws RuleIndexOutOfBoundsException, StateAccessException {
        return dataAccessor.create(rule.toZkRule(jumpChainID));
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.RuleDao#delete(java.util.UUID)
     */
    @Override
    public void delete(UUID id) throws StateAccessException {
        dataAccessor.delete(id);
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.RuleDao#get(java.util.UUID)
     */
    @Override
    public Rule get(UUID id) throws StateAccessException {
        try {
            return new Rule(id, dataAccessor.get(id).value);
        } catch (NoStatePathException e) {
            return null;
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see com.midokura.midolman.mgmt.data.dao.RuleDao#list(java.util.UUID)
     */
    @Override
    public List<Rule> list(UUID chainId) throws StateAccessException {
        List<Rule> rules = new ArrayList<Rule>();
        List<ZkNodeEntry<UUID, com.midokura.midolman.rules.Rule>> entries = dataAccessor
                .list(chainId);
        for (ZkNodeEntry<UUID, com.midokura.midolman.rules.Rule> entry : entries) {
            rules.add(new Rule(entry.key, entry.value));
        }
        return rules;
    }
}

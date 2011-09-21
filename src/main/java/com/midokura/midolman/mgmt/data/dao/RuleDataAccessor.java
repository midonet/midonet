/*
 * @(#)RuleDataAccessor        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.ZookeeperService;
import com.midokura.midolman.mgmt.data.dto.Rule;
import com.midokura.midolman.state.RuleZkManager;
import com.midokura.midolman.state.ZkConnection;
import com.midokura.midolman.state.ZkNodeEntry;

/**
 * Data access class for rules.
 * 
 * @version 1.6 08 Sept 2011
 * @author Ryu Ishimoto
 */
public class RuleDataAccessor extends DataAccessor {

    /**
     * Constructor
     * 
     * @param zkConn
     *            Zookeeper connection string
     */
    public RuleDataAccessor(String zkConn, int timeout, String rootPath,
            String mgmtRootPath) {
        super(zkConn, timeout, rootPath, mgmtRootPath);
    }

    private RuleZkManager getRuleZkManager() throws Exception {
        ZkConnection conn = ZookeeperService.getConnection(zkConn, zkTimeout);
        return new RuleZkManager(conn.getZooKeeper(), zkRoot);
    }

    /**
     * Add rule object to Zookeeper directories.
     * 
     * @param rule
     *            Rule object to add.
     * @throws Exception
     *             Error adding data to Zookeeper.
     */
    public UUID create(Rule rule) throws Exception {
        return getRuleZkManager().create(rule.toZkRule());
    }

    public void delete(UUID id) throws Exception {
        RuleZkManager manager = getRuleZkManager();
        // TODO: catch NoNodeException if does not exist.
        manager.delete(id);
    }

    /**
     * Get a Rule for the given ID.
     * 
     * @param id
     *            Rule ID to search.
     * @return Rule object with the given ID.
     * @throws Exception
     *             Error getting data to Zookeeper.
     */
    public Rule get(UUID id) throws Exception {
        return Rule.createRule(id, getRuleZkManager().get(id).value);
    }

    /**
     * Get a list of rules for a chain.
     * 
     * @param chainId
     *            UUID of chain.
     * @return A Set of Rules
     * @throws Exception
     *             Zookeeper(or any) error.
     */
    public Rule[] list(UUID chainId) throws Exception {
        RuleZkManager manager = getRuleZkManager();
        List<Rule> rules = new ArrayList<Rule>();
        List<ZkNodeEntry<UUID, com.midokura.midolman.rules.Rule>> entries = manager
                .list(chainId);
        for (ZkNodeEntry<UUID, com.midokura.midolman.rules.Rule> entry : entries) {
            rules.add(Rule.createRule(entry.key, entry.value));
        }
        return rules.toArray(new Rule[rules.size()]);
    }

}

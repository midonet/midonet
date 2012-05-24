/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.rules;

import com.midokura.midolman.state.*;
import junit.framework.Assert;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class TestChain {

    static UUID chainId;
    static MockDirectory mockDirectory;
    static String basePath;
    static ChainZkManager chainMgr;
    static RuleZkManager ruleMgr;
    static Chain chain;
    static String chainName;
    static ZkPathManager pathMgr;
    static ControlRules controlRules;

    private class ControlRules {
        private Map<UUID, Rule> ruleMap;
        private ArrayList<UUID> ruleIdList;
        private static final int offset = 1;

        public ControlRules() {
            ruleMap = new HashMap<UUID, Rule>();
            ruleIdList = new ArrayList<UUID>();
        }
        public void addRule(UUID ruleId, Rule rule, int position) {
            int listPosition = getListPosition(position);
            ruleIdList.add(listPosition, ruleId);

            ruleMap.put(ruleId, rule);
        }

        public void deleteRule(UUID ruleId) {
            Assert.assertTrue("Rule must exist in control map",
                    ruleMap.containsKey(ruleId));
            ruleMap.remove(ruleId);

            Assert.assertNotNull("Rule must exist in control list",
                    ruleIdList.contains(ruleId));
            ruleIdList.remove(ruleId);
        }

        public void deleteRule(int position) {
            int listPosition = getListPosition(position);
            Assert.assertNotNull("Rule must exist in control list",
                    ruleIdList.get(listPosition));
            UUID ruleId = ruleIdList.remove(listPosition);

            Assert.assertTrue("Rule must exist in control map",
                    ruleMap.containsKey(ruleId));
            ruleMap.remove(ruleId);
        }

        public UUID getRuleId(int position) {
            int listPosition = getListPosition(position);

            return ruleIdList.get(listPosition);
        }

        public Rule getRule(int position) {
            int listPosition = getListPosition(position);

            UUID ruleId = ruleIdList.get(listPosition);
            return ruleMap.get(ruleId);
        }

        public Rule getRule(UUID ruleId) {
            return ruleMap.get(ruleId);
        }

        private int getListPosition(int position) {
            Assert.assertTrue("Rule positions start at 1",
                    position >= 1);

            return position - offset;
        }
    }

    @Before
    public void setup()
            throws KeeperException.NoNodeException,
                   KeeperException.NodeExistsException,
                   KeeperException.NoChildrenForEphemeralsException,
                   StateAccessException {
        /*
         * Create an empty mock directory and an empty chain for every test.
         */
        mockDirectory = new MockDirectory();
        basePath = "";
        chainName = "TestChainName";

        pathMgr = new ZkPathManager(basePath);
        mockDirectory.add(pathMgr.getChainsPath(), null, CreateMode.PERSISTENT);
        mockDirectory.add(pathMgr.getRulesPath(), null, CreateMode.PERSISTENT);
        chainMgr = new ChainZkManager(mockDirectory, basePath);
        ruleMgr = new RuleZkManager(mockDirectory, basePath);

        addChain(chainName);
        controlRules = new ControlRules();
    }

    @Test
    public void testEmptyChain() {
        Assert.assertNotNull(chain);

        Assert.assertEquals("Check chain ID matches expected ID",
                chainId, chain.getID());

        Assert.assertEquals("Check chain name matches expected name",
                chainName, chain.getChainName());

        checkNumRules(0);
    }

    @Test
    public void testEmptyUpdateRules() {
        chain.updateRules();

        checkNumRules(0);
    }

    @Test
    public void testOneRuleAdded() throws
            StateAccessException,
            RuleIndexOutOfBoundsException {

        int position = 1;
        addRule(position);

        checkNumRules(1);
    }

    @Test
    public void testTwoRulesAdded() throws
            StateAccessException,
            RuleIndexOutOfBoundsException {

        int position = 1;
        addRule(position++);
        addRule(position);

        checkNumRules(2);
    }

    @Test (expected = RuleIndexOutOfBoundsException.class)
    public void testIndexOutOfBounds() throws Exception {

        int position = 10;
        addRule(position);
    }

    @Test
    public void testDeleteFirstRule() throws
            StateAccessException,
            RuleIndexOutOfBoundsException {

        int position = 1;
        addRule(position++);
        addRule(position++);
        addRule(position);

        checkEqualRules();

        // Delete first rule
        deleteRule(1);

        checkEqualRules();
    }

    @Test
    public void testDeleteMiddleRule() throws
            StateAccessException,
            RuleIndexOutOfBoundsException {

        int position = 1;
        addRule(position++);
        addRule(position++);
        addRule(position);

        checkEqualRules();

        // Delete middle rule
        deleteRule(2);

        checkEqualRules();
    }

    @Test
    public void testDeleteLastRule() throws
            StateAccessException,
            RuleIndexOutOfBoundsException {

        int position = 1;
        addRule(position++);
        addRule(position++);
        addRule(position);

        checkEqualRules();

        // Delete last rule
        deleteRule(3);

        checkEqualRules();
    }

    @Test
    public void testReplaceFirstRule() throws
            StateAccessException,
            RuleIndexOutOfBoundsException {

        int position = 1;
        addRule(position++);
        addRule(position++);
        addRule(position);

        checkEqualRules();

        // Delete first rule
        deleteRule(1);

        checkEqualRules();

        // Add new rule to first position
        addRule(1);

        checkEqualRules();
    }

    @Test
    public void testReplaceMiddleRule() throws
            StateAccessException,
            RuleIndexOutOfBoundsException {

        int position = 1;
        addRule(position++);
        addRule(position++);
        addRule(position);

        checkEqualRules();

        // Delete middle rule
        deleteRule(2);

        checkEqualRules();

        // Add new rule to middle position
        addRule(2);

        checkEqualRules();
    }

    @Test
    public void testReplaceLastRule() throws
            StateAccessException,
            RuleIndexOutOfBoundsException {

        int position = 1;
        addRule(position++);
        addRule(position++);
        addRule(position);

        checkEqualRules();

        // Delete last rule
        deleteRule(3);

        checkEqualRules();

        // Add new rule to last position
        addRule(3);

        checkEqualRules();
    }

    /*
     * Auxiliary methods
     */
    private void addChain(String chainName) throws
            KeeperException.NoNodeException,
            KeeperException.NodeExistsException,
            KeeperException.NoChildrenForEphemeralsException,
            StateAccessException {

        ChainZkManager.ChainConfig chainConfig =
                new ChainZkManager.ChainConfig(chainName);
        chainId = chainMgr.create(chainConfig);

        chain = new Chain(chainId, mockDirectory, basePath, null);
    }

    private void addRule(int position) throws
            StateAccessException,
            RuleIndexOutOfBoundsException {

        Rule rule = new LiteralRule(new Condition(), RuleResult.Action.ACCEPT,
                chainId, position);

        /* this will trigger chain.updateRules() */
        UUID ruleId = ruleMgr.create(rule);

        /* save rule and its ID for testing purposes */
        controlRules.addRule(ruleId, rule, position);
    }

    private void deleteRule(UUID ruleId) throws StateAccessException {
        ruleMgr.delete(ruleId);
        controlRules.deleteRule(ruleId);
    }

    private void deleteRule(int position) throws StateAccessException {
        UUID ruleId = controlRules.getRuleId(position);
        controlRules.deleteRule(position);

        ruleMgr.delete(ruleId);
    }

    private void checkNumRules (int numRules) {
        List<Rule> rules = chain.getRules();
        Assert.assertEquals("Expected " + numRules + " rule(s) in list",
                numRules , rules.size());
    }

    private void checkEqualRules() {
        // Check that rules in the state are the same as in control list
        List<Rule> rules = chain.getRules();
        int index = 1;
        for (Rule rule : rules) {
            Assert.assertEquals("Chain rules and control rules should be equal",
                    rule, controlRules.getRule(index++));
        }
    }
}

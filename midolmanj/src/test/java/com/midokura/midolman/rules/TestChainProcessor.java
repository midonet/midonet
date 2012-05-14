/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.rules;

import java.util.LinkedList;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.openflow.protocol.OFMatch;

import com.midokura.midolman.eventloop.MockReactor;
import com.midokura.midolman.eventloop.Reactor;
import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.rules.RuleResult.Action;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.FiltersZkManager;
import com.midokura.midolman.state.MockDirectory;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkPathManager;
import com.midokura.midolman.util.Cache;
import com.midokura.midolman.util.MockCache;

public class TestChainProcessor {

    static Random rand;
    static UUID ownerId;
    static UUID chainId;
    static UUID inPortId;
    static UUID outPortId;
    static MidoMatch pktMatch;
    static MidoMatch flowMatch;
    static MockChainProcessor mockChainProcessor;
    static Condition matchingCondition;
    static Condition nonMatchingCondition;

    class MockChainProcessor extends ChainProcessor {

        public MockChainProcessor(Directory dir, String zkBasePath, Cache cache,
                                  Reactor reactor) throws StateAccessException {
            super(dir, zkBasePath, cache, reactor);
        }

        public void addChain(Chain chain) {
            if (chain == null) {
                return;
            }

            chainByUuid.put(chain.getID(), chain);
        }
    }

    class MockChain extends Chain {

        public MockChain(UUID chainId, String chainName) {

            // we don't call super constructor, because it's got references
            // to zookeeper we don't need for testing.
            this.chainId = chainId;
            this.rules = new LinkedList<Rule>();
            this.setChainName(chainName);
        }

        public void addRule (Rule rule) {
            if (rule == null) {
                return;
            }

            rules.add(rule);
        }
    }

    @BeforeClass
    public static void setupOnce() {
        rand = new Random();
        ownerId = new UUID(rand.nextLong(), rand.nextLong());
        chainId = new UUID(rand.nextLong(), rand.nextLong());
        inPortId = new UUID(rand.nextLong(), rand.nextLong());
        outPortId = new UUID(rand.nextLong(), rand.nextLong());

        // Build a packet to test the rules.
        pktMatch = new MidoMatch();
        pktMatch.setInputPort((short) 5);
        pktMatch.setDataLayerSource("02:11:33:00:11:01");
        pktMatch.setDataLayerDestination("02:11:aa:ee:22:05");
        pktMatch.setNetworkSource(0x0a001406, 32);
        pktMatch.setNetworkDestination(0x0a000b22, 32);
        pktMatch.setNetworkProtocol((byte) 6); // TCP
        pktMatch.setNetworkTypeOfService((byte) 34);
        pktMatch.setTransportSource((short) 4321);
        pktMatch.setTransportDestination((short) 1234);

        // Initially the flowMatch and the packetMatch are the same
        flowMatch = pktMatch.clone();

        // Matching condition
        matchingCondition = new Condition();
        matchingCondition.nwSrcIp = 0x0a001406;
        matchingCondition.nwSrcLength = 32;

        // Non-matching condition
        nonMatchingCondition = new Condition();
        nonMatchingCondition.nwSrcIp = 0xdeadbeef;
        nonMatchingCondition.nwSrcLength = 32;
    }

    @Before
    public void setup() throws InterruptedException, KeeperException, StateAccessException {

        // Initialize the chainProcessor each test, so we can easily have a
        // clear directory every time
        Directory mockDirectory = new MockDirectory();
        ZkPathManager pathMgr = new ZkPathManager("");
        mockDirectory.add(pathMgr.getFiltersPath(), null, CreateMode.PERSISTENT);
        FiltersZkManager filterMgr = new FiltersZkManager(mockDirectory, "");
        filterMgr.create(ownerId);

        mockChainProcessor = new MockChainProcessor(mockDirectory, "",
                new MockCache(), new MockReactor());
    }

    @Test
    public void testFreeFlowResources() {
        OFMatch match = new OFMatch();

        Assert.assertNotNull(mockChainProcessor);
        mockChainProcessor.freeFlowResources(match, ownerId);

        // No need to check the result of the operation, we just want to
        // check that the code doesn't crash.
        Assert.assertTrue(true);
    }

    @Test
    public void testGetOrCreateChain() {

    }

    @Test
    public void testApply1() throws StateAccessException {
        MockChain mockChain = new MockChain(chainId, "mainChain");

        addRule(nonMatchingCondition, Action.DROP ,mockChain, 10);
        addRule(matchingCondition, Action.REJECT, mockChain, 20); //match

        mockChainProcessor.addChain(mockChain);

        RuleResult ruleResult = mockChainProcessor.applyChain(chainId,
                flowMatch, pktMatch, inPortId, outPortId, ownerId);

        Assert.assertEquals(Action.REJECT, ruleResult.action);
    }

    @Test
    public void testApply2() throws StateAccessException {
        MockChain mockChain = new MockChain(chainId, "mainChain");

        addRule(matchingCondition, Action.REJECT, mockChain, 10); //match
        addRule(nonMatchingCondition, Action.ACCEPT, mockChain, 20);

        mockChainProcessor.addChain(mockChain);

        RuleResult ruleResult = mockChainProcessor.applyChain(chainId,
                flowMatch, pktMatch, inPortId, outPortId, ownerId);

        Assert.assertEquals(Action.REJECT, ruleResult.action);
    }

    @Test
    public void testApply3() throws StateAccessException {
        MockChain mockChain = new MockChain(chainId, "mainChain");

        addRule(nonMatchingCondition, Action.DROP, mockChain, 10);
        addRule(nonMatchingCondition, Action.DROP, mockChain, 20);

        mockChainProcessor.addChain(mockChain);

        RuleResult ruleResult = mockChainProcessor.applyChain(chainId,
                flowMatch, pktMatch, inPortId, outPortId, ownerId);

        // When no rules are matched, expect ACCEPT
        Assert.assertEquals(Action.ACCEPT, ruleResult.action);
    }

    @Test
    public void testApply4() throws StateAccessException {
        MockChain mockChain = new MockChain(chainId, "mainChain");

        addRule(nonMatchingCondition, Action.REJECT, mockChain, 10);
        addRule(matchingCondition, Action.DROP, mockChain, 20); //match

        mockChainProcessor.addChain(mockChain);

        RuleResult ruleResult = mockChainProcessor.applyChain(chainId,
                flowMatch, pktMatch, inPortId, outPortId, ownerId);

        // When no rules are matched, expect ACCEPT
        Assert.assertEquals(Action.DROP, ruleResult.action);
    }

    @Test
    public void testApply5() throws StateAccessException {
        MockChain mockChain = new MockChain(chainId, "mainChain");

        addRule(nonMatchingCondition, Action.REJECT, mockChain, 10);
        addRule(matchingCondition, Action.RETURN, mockChain, 20); //match

        mockChainProcessor.addChain(mockChain);

        RuleResult ruleResult = mockChainProcessor.applyChain(chainId,
                flowMatch, pktMatch, inPortId, outPortId, ownerId);

        // When chain is interrupted (RETURN), expect ACCEPT
        Assert.assertEquals(Action.ACCEPT, ruleResult.action);
    }

    @Test
    public void testApply6() throws StateAccessException {
        MockChain mockChain = new MockChain(chainId, "mainChain");

        addRule(matchingCondition, Action.RETURN, mockChain, 10); //match
        addRule(matchingCondition, Action.REJECT, mockChain, 20);
        addRule(matchingCondition, Action.DROP, mockChain, 30);

        mockChainProcessor.addChain(mockChain);

        RuleResult ruleResult = mockChainProcessor.applyChain(chainId,
                flowMatch, pktMatch, inPortId, outPortId, ownerId);

        // When chain is interrupted (RETURN), expect ACCEPT
        Assert.assertEquals(Action.ACCEPT, ruleResult.action);
    }

    @Test
    public void testJump1() throws StateAccessException {
        // Main chain
        MockChain mockChain = new MockChain(chainId, "mainChain");

        // Target chain, we jump here from main chain
        UUID targetChainId = new UUID (rand.nextLong(), rand.nextLong());
        MockChain mockChainTarget = new MockChain(targetChainId, "targetChain");

        addRule(nonMatchingCondition, Action.DROP, mockChain, 10);
        addRule(matchingCondition, mockChainTarget, mockChain, 20);
        addRule(matchingCondition, Action.DROP, mockChain, 50);

        addRule(nonMatchingCondition, Action.DROP, mockChainTarget, 30);
        addRule(matchingCondition, Action.REJECT, mockChainTarget, 40); //match

        mockChainProcessor.addChain(mockChain);
        mockChainProcessor.addChain(mockChainTarget);

        RuleResult ruleResult = mockChainProcessor.applyChain(chainId,
                flowMatch, pktMatch, inPortId, outPortId, ownerId);

        Assert.assertEquals(Action.REJECT, ruleResult.action);
    }

    @Test
    public void testJump2() throws StateAccessException {
        // Main chain
        MockChain mockChain = new MockChain(chainId, "mainChain");

        // Target chain, we jump here from main chain
        UUID targetChainId = new UUID (rand.nextLong(), rand.nextLong());
        MockChain mockChainTarget = new MockChain(targetChainId, "targetChain");

        addRule(nonMatchingCondition, Action.ACCEPT, mockChain, 10);
        addRule(matchingCondition, mockChainTarget, mockChain, 20);
        addRule(matchingCondition, Action.ACCEPT, mockChain, 50);

        addRule(nonMatchingCondition, Action.ACCEPT, mockChainTarget, 30);
        addRule(matchingCondition, Action.REJECT, mockChainTarget, 40); //match

        mockChainProcessor.addChain(mockChain);
        mockChainProcessor.addChain(mockChainTarget);

        RuleResult ruleResult = mockChainProcessor.applyChain(chainId,
                flowMatch, pktMatch, inPortId, outPortId, ownerId);

        Assert.assertEquals(Action.REJECT, ruleResult.action);
    }

    @Test
    public void testJump3() throws StateAccessException {
        // Main chain
        MockChain mockChain = new MockChain(chainId, "mainChain");

        // Target chain, we jump here from main chain
        UUID targetChainId = new UUID (rand.nextLong(), rand.nextLong());
        MockChain mockChainTarget = new MockChain(targetChainId, "targetChain");

        addRule(nonMatchingCondition, Action.ACCEPT, mockChain, 10);
        addRule(matchingCondition, mockChainTarget, mockChain, 20);
        addRule(matchingCondition, Action.REJECT, mockChain, 50); //match

        addRule(nonMatchingCondition, Action.ACCEPT, mockChainTarget, 30);
        addRule(matchingCondition, mockChain, mockChainTarget, 40); //loop

        mockChainProcessor.addChain(mockChain);
        mockChainProcessor.addChain(mockChainTarget);

        RuleResult ruleResult = mockChainProcessor.applyChain(chainId,
                flowMatch, pktMatch, inPortId, outPortId, ownerId);

        Assert.assertEquals(Action.REJECT, ruleResult.action);
    }

    @Test
    public void testJump4() throws StateAccessException {
        // Main chain
        MockChain mockChain = new MockChain(chainId, "mainChain");

        // Target chain, we jump here from main chain
        UUID targetChainId = new UUID (rand.nextLong(), rand.nextLong());
        MockChain mockChainTarget1 = new MockChain(targetChainId, "targetChain1");

        targetChainId = new UUID (rand.nextLong(), rand.nextLong());
        MockChain mockChainTarget2 = new MockChain(targetChainId, "targetChain2");

        addRule(nonMatchingCondition, Action.ACCEPT, mockChain, 10);
        addRule(matchingCondition, mockChainTarget1, mockChain, 20);
        addRule(matchingCondition, Action.REJECT, mockChain, 70); //match

        addRule(nonMatchingCondition, Action.ACCEPT, mockChainTarget1, 30);
        addRule(matchingCondition, mockChainTarget2, mockChainTarget1, 40);

        addRule(nonMatchingCondition, Action.ACCEPT, mockChainTarget2, 50);
        addRule(matchingCondition, mockChainTarget1, mockChainTarget2, 60); //loop

        mockChainProcessor.addChain(mockChain);
        mockChainProcessor.addChain(mockChainTarget1);
        mockChainProcessor.addChain(mockChainTarget2);

        RuleResult ruleResult = mockChainProcessor.applyChain(chainId,
                flowMatch, pktMatch, inPortId, outPortId, ownerId);

        Assert.assertEquals(Action.REJECT, ruleResult.action);
    }

    @Test
    public void testJump5() throws StateAccessException {
        // Main chain
        MockChain mockChain = new MockChain(chainId, "mainChain");

        // Target chain, we jump here from main chain
        UUID targetChainId = new UUID (rand.nextLong(), rand.nextLong());
        MockChain mockChainTarget = new MockChain(targetChainId, "targetChain");

        addRule(nonMatchingCondition, Action.REJECT, mockChain, 10);
        addRule(matchingCondition, mockChainTarget, mockChain, 20);
        addRule(matchingCondition, Action.REJECT, mockChain, 50);

        addRule(nonMatchingCondition, Action.REJECT, mockChainTarget, 30);
        addRule(matchingCondition, Action.DROP, mockChainTarget, 40); //match

        mockChainProcessor.addChain(mockChain);
        mockChainProcessor.addChain(mockChainTarget);

        RuleResult ruleResult = mockChainProcessor.applyChain(chainId,
                flowMatch, pktMatch, inPortId, outPortId, ownerId);

        Assert.assertEquals(Action.DROP, ruleResult.action);
    }

    @Test
    public void testJump6() throws StateAccessException {
        // Main chain
        MockChain mockChain = new MockChain(chainId, "mainChain");

        // Target chain, we jump here from main chain
        UUID targetChainId = new UUID (rand.nextLong(), rand.nextLong());
        MockChain mockChainTarget = new MockChain(targetChainId, "targetChain");

        addRule(nonMatchingCondition, Action.ACCEPT, mockChain, 10);
        addRule(matchingCondition, mockChainTarget, mockChain, 20);
        addRule(matchingCondition, Action.ACCEPT, mockChain, 50);

        addRule(nonMatchingCondition, Action.ACCEPT, mockChainTarget, 30);
        addRule(matchingCondition, Action.DROP, mockChainTarget, 40); //match

        mockChainProcessor.addChain(mockChain);
        mockChainProcessor.addChain(mockChainTarget);

        RuleResult ruleResult = mockChainProcessor.applyChain(chainId,
                flowMatch, pktMatch, inPortId, outPortId, ownerId);

        Assert.assertEquals(Action.DROP, ruleResult.action);
    }

    @Test
    public void testJump7() throws StateAccessException {
        // Main chain
        MockChain mockChain = new MockChain(chainId, "mainChain");

        // Target chain, we jump here from main chain
        UUID targetChainId = new UUID (rand.nextLong(), rand.nextLong());
        MockChain mockChainTarget = new MockChain(targetChainId, "targetChain");

        addRule(nonMatchingCondition, Action.ACCEPT, mockChain, 10);
        addRule(matchingCondition, mockChainTarget, mockChain, 20);
        addRule(matchingCondition, Action.DROP, mockChain, 50); //match

        addRule(matchingCondition, Action.RETURN, mockChainTarget, 30); //match
        addRule(matchingCondition, Action.ACCEPT, mockChainTarget, 40);

        mockChainProcessor.addChain(mockChain);
        mockChainProcessor.addChain(mockChainTarget);

        RuleResult ruleResult = mockChainProcessor.applyChain(chainId,
                flowMatch, pktMatch, inPortId, outPortId, ownerId);

        Assert.assertEquals(Action.DROP, ruleResult.action);
    }

    @Test
    public void testNat1() throws StateAccessException {
        MockChain mockChain = new MockChain(chainId, "mainChain");
        mockChainProcessor.addChain(mockChain);

        Set<NatTarget> set = new TreeSet<NatTarget>();
        set.add(new NatTarget(0x0c000102, 0x0c00010a, (short) 1030,
                (short) 1050));
        addRule(matchingCondition, Action.ACCEPT, mockChain, 10, true, set);
        addRule(matchingCondition, Action.REJECT, mockChain, 20);

        RuleResult ruleResult = mockChainProcessor.applyChain(chainId,
                flowMatch, pktMatch, inPortId, outPortId, ownerId);

        Assert.assertEquals(Action.ACCEPT, ruleResult.action);
    }

    @Test
    public void testNat2() throws StateAccessException {
        MockChain mockChain = new MockChain(chainId, "mainChain");
        mockChainProcessor.addChain(mockChain);

        Set<NatTarget> set = new TreeSet<NatTarget>();
        set.add(new NatTarget(0x0c000102, 0x0c00010a, (short) 1030,
                (short) 1050));
        addRule(matchingCondition, Action.CONTINUE, mockChain, 10, true, set);
        addRule(matchingCondition, Action.REJECT, mockChain, 20);

        RuleResult ruleResult = mockChainProcessor.applyChain(chainId,
                flowMatch, pktMatch, inPortId, outPortId, ownerId);

        Assert.assertEquals(Action.REJECT, ruleResult.action);
    }

    @Test
    public void testNat3() throws StateAccessException {
        MockChain mockChain = new MockChain(chainId, "mainChain");
        mockChainProcessor.addChain(mockChain);

        Set<NatTarget> set = new TreeSet<NatTarget>();
        set.add(new NatTarget(0x0c000102, 0x0c00010a, (short) 1030,
                (short) 1050));
        addRule(matchingCondition, Action.ACCEPT, mockChain, 10, false, set);
        addRule(matchingCondition, Action.REJECT, mockChain, 20);

        RuleResult ruleResult = mockChainProcessor.applyChain(chainId,
                flowMatch, pktMatch, inPortId, outPortId, ownerId);

        Assert.assertEquals(Action.ACCEPT, ruleResult.action);
    }

    @Test
    public void testNat4() throws StateAccessException {
        MockChain mockChain = new MockChain(chainId, "mainChain");
        mockChainProcessor.addChain(mockChain);

        Set<NatTarget> set = new TreeSet<NatTarget>();
        set.add(new NatTarget(0x0c000102, 0x0c00010a, (short) 1030,
                (short) 1050));
        addRule(matchingCondition, Action.CONTINUE, mockChain, 10, false, set);
        addRule(matchingCondition, Action.REJECT, mockChain, 20);

        RuleResult ruleResult = mockChainProcessor.applyChain(chainId,
                flowMatch, pktMatch, inPortId, outPortId, ownerId);

        Assert.assertEquals(Action.ACCEPT, ruleResult.action);
    }

    @Test
    public void testNat5() throws StateAccessException {
        MockChain mockChain = new MockChain(chainId, "mainChain");
        mockChainProcessor.addChain(mockChain);

        addRule(matchingCondition, Action.ACCEPT, mockChain, 10, true);
        addRule(matchingCondition, Action.REJECT, mockChain, 20); //match

        RuleResult ruleResult = mockChainProcessor.applyChain(chainId,
                flowMatch, pktMatch, inPortId, outPortId, ownerId);

        Assert.assertEquals(Action.REJECT, ruleResult.action);
    }

    @Test
    public void testNat6() throws StateAccessException {
        MockChain mockChain = new MockChain(chainId, "mainChain");
        mockChainProcessor.addChain(mockChain);

        addRule(matchingCondition, Action.ACCEPT, mockChain, 10, false);
        addRule(matchingCondition, Action.REJECT, mockChain, 20); //match

        RuleResult ruleResult = mockChainProcessor.applyChain(chainId,
                flowMatch, pktMatch, inPortId, outPortId, ownerId);

        Assert.assertEquals(Action.REJECT, ruleResult.action);
    }

    @Test
    public void testNat7() throws StateAccessException {
        MockChain mockChain = new MockChain(chainId, "mainChain");
        mockChainProcessor.addChain(mockChain);

        Set<NatTarget> set = new TreeSet<NatTarget>();
        set.add(new NatTarget(0x0c000102, 0x0c00010a, (short) 1030,
                (short) 1050));
        addRule(matchingCondition, Action.CONTINUE, mockChain, 10, false, set); //match
        addRule(matchingCondition, Action.DROP, mockChain, 20, false); //no match
        addRule(matchingCondition, Action.REJECT, mockChain, 30); //no match

        RuleResult ruleResult = mockChainProcessor.applyChain(chainId,
                flowMatch, pktMatch, inPortId, outPortId, ownerId);

        Assert.assertEquals(Action.ACCEPT, ruleResult.action);
    }

    @Test
    public void testNat8() throws StateAccessException {
        MockChain mockChain = new MockChain(chainId, "mainChain");
        mockChainProcessor.addChain(mockChain);

        Set<NatTarget> set = new TreeSet<NatTarget>();
        set.add(new NatTarget(0x0c000102, 0x0c00010a, (short) 1030,
                (short) 1050));
        addRule(matchingCondition, Action.CONTINUE, mockChain, 10, false, set); //match
        addRule(matchingCondition, Action.ACCEPT, mockChain, 20, false, set); //no match
        addRule(matchingCondition, Action.REJECT, mockChain, 30); //no match

        RuleResult ruleResult = mockChainProcessor.applyChain(chainId,
                flowMatch, pktMatch, inPortId, outPortId, ownerId);

        Assert.assertEquals(Action.ACCEPT, ruleResult.action);
    }

    /*
     * auxiliary methods
     */

    private void addRule (Condition condition, Action action, MockChain mockChain, int position) {
        Rule rule = new LiteralRule(condition, action, mockChain.getID(), position);
        mockChain.addRule(rule);
    }

    private void addRule (Condition condition, Chain targetChain, MockChain mockChain, int position) {
        Rule rule = new JumpRule(condition, targetChain.getID(), targetChain.getChainName(), mockChain.getID(), position);
        mockChain.addRule(rule);
    }

    private void addRule (Condition condition, Action action, MockChain mockChain,
                          int position, boolean dnat, Set<NatTarget> targets) {
        Rule rule = new ForwardNatRule(condition, action, mockChain.getID(),
                position, dnat, targets);
        mockChain.addRule(rule);
    }

    private void addRule (Condition condition, Action action, MockChain mockChain,
                          int position, boolean dnat) {
        Rule rule = new ReverseNatRule(condition, action, mockChain.getID(),
                position, dnat);
        mockChain.addRule(rule);
    }

}

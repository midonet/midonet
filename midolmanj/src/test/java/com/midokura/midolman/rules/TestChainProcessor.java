/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.rules;

import java.util.LinkedList;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.openflow.protocol.OFMatch;

import com.midokura.midolman.eventloop.MockReactor;
import com.midokura.util.eventloop.eventloop.Reactor;
import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.rules.RuleResult.Action;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.FiltersZkManager;
import com.midokura.midolman.state.MockDirectory;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkPathManager;
import com.midokura.midolman.util.Cache;
import com.midokura.midolman.util.MockCache;
import com.midokura.midolman.vrn.ForwardInfo;


public class TestChainProcessor {

    static UUID ownerId;
    static UUID chainId;
    static UUID inPortId;
    static UUID outPortId;
    static MidoMatch pktMatch;
    static MidoMatch flowMatch;
    static MockChainProcessor mockChainProcessor;
    static Condition matchingCondition;
    static Condition nonMatchingCondition;
    private MockChain mockChain;
    private ForwardInfo fwdInfo;

    class MockChainProcessor extends ChainProcessor {

        public MockChainProcessor(Directory dir, String zkBasePath, Cache cache,
                                  Reactor reactor) throws StateAccessException {
            super(dir, zkBasePath, cache, reactor, null);
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
            // We don't call the super constructor, because it has references
            // to zookeeper we don't need for testing.
            this.chainId = chainId;
            this.rules = new LinkedList<Rule>();
            this.setChainName(chainName);
        }

        public void addRule(Rule rule) {
            if (rule == null) {
                return;
            }

            rules.add(rule);
        }
    }

    @BeforeClass
    public static void setupOnce() {
        ownerId = UUID.randomUUID();
        chainId = UUID.randomUUID();
        inPortId = UUID.randomUUID();
        outPortId = UUID.randomUUID();

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
    public void setup() throws InterruptedException, KeeperException,
                               StateAccessException {

        // Initialize the chainProcessor each test, so we can easily have a
        // clear directory every time
        Directory mockDirectory = new MockDirectory();
        ZkPathManager pathMgr = new ZkPathManager("");
        mockDirectory.add(pathMgr.getFiltersPath(), null, CreateMode.PERSISTENT);
        FiltersZkManager filterMgr = new FiltersZkManager(mockDirectory, "");
        filterMgr.create(ownerId);

        mockChainProcessor = new MockChainProcessor(mockDirectory, "",
                new MockCache(), new MockReactor());

        // Add main chain for the tests
        mockChain = new MockChain(chainId, "mainChain");
        mockChainProcessor.addChain(mockChain);

        fwdInfo = new ForwardInfo(false, null, null);
        fwdInfo.inPortId = inPortId;
        fwdInfo.outPortId = outPortId;
        fwdInfo.flowMatch = flowMatch;
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
        //FIXME
    }

    @Test
    public void testMatchSecondRule() throws StateAccessException {
        addLiteralRule(nonMatchingCondition, Action.DROP, mockChain, 10);
        addLiteralRule(matchingCondition, Action.REJECT, mockChain, 20); //match

        RuleResult ruleResult = mockChainProcessor.applyChain(chainId,
                fwdInfo, pktMatch, ownerId, false);

        Assert.assertEquals(Action.REJECT, ruleResult.action);
    }

    @Test
    public void testMatchFirstRule() throws StateAccessException {

        addLiteralRule(matchingCondition, Action.REJECT, mockChain, 10); //match
        addLiteralRule(nonMatchingCondition, Action.ACCEPT, mockChain, 20);

        RuleResult ruleResult = mockChainProcessor.applyChain(chainId,
                fwdInfo, pktMatch, ownerId, false);

        Assert.assertEquals(Action.REJECT, ruleResult.action);
    }

    @Test
    public void testNoMatchingRules() throws StateAccessException {

        addLiteralRule(nonMatchingCondition, Action.DROP, mockChain, 10);
        addLiteralRule(nonMatchingCondition, Action.DROP, mockChain, 20);

        RuleResult ruleResult = mockChainProcessor.applyChain(chainId,
                fwdInfo, pktMatch, ownerId, false);

        // When no rules are matched, expect ACCEPT
        Assert.assertEquals(Action.ACCEPT, ruleResult.action);
    }

    @Test
    public void testMatchDropAction() throws StateAccessException {

        addLiteralRule(nonMatchingCondition, Action.REJECT, mockChain, 10);
        addLiteralRule(matchingCondition, Action.DROP, mockChain, 20); //match

        RuleResult ruleResult = mockChainProcessor.applyChain(chainId,
                fwdInfo, pktMatch, ownerId, false);

        // When no rules are matched, expect ACCEPT
        Assert.assertEquals(Action.DROP, ruleResult.action);
    }

    @Test
    public void testMatchReturnAction() throws StateAccessException {

        addLiteralRule(nonMatchingCondition, Action.REJECT, mockChain, 10);
        addLiteralRule(matchingCondition, Action.RETURN, mockChain, 20); //match

        RuleResult ruleResult = mockChainProcessor.applyChain(chainId,
                fwdInfo, pktMatch, ownerId, false);

        // When chain is interrupted (RETURN), expect ACCEPT
        Assert.assertEquals(Action.ACCEPT, ruleResult.action);
    }

    @Test
    public void testThreeMatchingRules() throws StateAccessException {

        addLiteralRule(matchingCondition, Action.RETURN, mockChain, 10); //match
        addLiteralRule(matchingCondition, Action.REJECT, mockChain, 20);
        addLiteralRule(matchingCondition, Action.DROP, mockChain, 30);

        RuleResult ruleResult = mockChainProcessor.applyChain(chainId,
                fwdInfo, pktMatch, ownerId, false);

        // When chain is interrupted (RETURN), expect ACCEPT
        Assert.assertEquals(Action.ACCEPT, ruleResult.action);
    }

    @Test
    public void testJumpNoDropAction() throws StateAccessException {

        // Target chain, we jump here from main chain
        UUID targetChainId = UUID.randomUUID();
        MockChain mockChainTarget = new MockChain(targetChainId, "targetChain");

        addLiteralRule(nonMatchingCondition, Action.DROP, mockChain, 10);
        addJumpRule(matchingCondition, mockChainTarget, mockChain, 20);
        addLiteralRule(matchingCondition, Action.DROP, mockChain, 50);

        addLiteralRule(nonMatchingCondition, Action.DROP, mockChainTarget, 30);
        addLiteralRule(matchingCondition, Action.REJECT, mockChainTarget, 40); //match

        mockChainProcessor.addChain(mockChainTarget);

        RuleResult ruleResult = mockChainProcessor.applyChain(chainId,
                fwdInfo, pktMatch, ownerId, false);

        Assert.assertEquals(Action.REJECT, ruleResult.action);
    }

    @Test
    public void testJumpNoAcceptAction() throws StateAccessException {

        // Target chain, we jump here from main chain
        UUID targetChainId = UUID.randomUUID();
        MockChain mockChainTarget = new MockChain(targetChainId, "targetChain");

        addLiteralRule(nonMatchingCondition, Action.ACCEPT, mockChain, 10);
        addJumpRule(matchingCondition, mockChainTarget, mockChain, 20);
        addLiteralRule(matchingCondition, Action.ACCEPT, mockChain, 50);

        addLiteralRule(nonMatchingCondition, Action.ACCEPT, mockChainTarget, 30);
        addLiteralRule(matchingCondition, Action.REJECT, mockChainTarget, 40); //match

        mockChainProcessor.addChain(mockChainTarget);

        RuleResult ruleResult = mockChainProcessor.applyChain(chainId,
                fwdInfo, pktMatch, ownerId, false);

        Assert.assertEquals(Action.REJECT, ruleResult.action);
    }

    @Test
    public void testJumpPreventLoop() throws StateAccessException {

        // Target chain, we jump here from main chain
        UUID targetChainId = UUID.randomUUID();
        MockChain mockChainTarget = new MockChain(targetChainId, "targetChain");

        addLiteralRule(nonMatchingCondition, Action.ACCEPT, mockChain, 10);
        addJumpRule(matchingCondition, mockChainTarget, mockChain, 20);
        addLiteralRule(matchingCondition, Action.REJECT, mockChain, 50); //match

        addLiteralRule(nonMatchingCondition, Action.ACCEPT, mockChainTarget, 30);
        addJumpRule(matchingCondition, mockChain, mockChainTarget, 40); //loop

        mockChainProcessor.addChain(mockChainTarget);

        RuleResult ruleResult = mockChainProcessor.applyChain(chainId,
                fwdInfo, pktMatch, ownerId, false);

        Assert.assertEquals(Action.REJECT, ruleResult.action);
    }

    @Test
    public void testJumpPreventInnerLoop() throws StateAccessException {

        // Target chain, we jump here from main chain
        UUID targetChainId = UUID.randomUUID();
        MockChain mockChainTarget1 = new MockChain(targetChainId, "targetChain1");

        targetChainId = UUID.randomUUID();
        MockChain mockChainTarget2 = new MockChain(targetChainId, "targetChain2");

        addLiteralRule(nonMatchingCondition, Action.ACCEPT, mockChain, 10);
        addJumpRule(matchingCondition, mockChainTarget1, mockChain, 20);
        addLiteralRule(matchingCondition, Action.REJECT, mockChain, 70); //match

        addLiteralRule(nonMatchingCondition, Action.ACCEPT, mockChainTarget1, 30);
        addJumpRule(matchingCondition, mockChainTarget2, mockChainTarget1, 40);

        addLiteralRule(nonMatchingCondition, Action.ACCEPT, mockChainTarget2, 50);
        addJumpRule(matchingCondition, mockChainTarget1, mockChainTarget2, 60); //loop

        mockChainProcessor.addChain(mockChainTarget1);
        mockChainProcessor.addChain(mockChainTarget2);

        RuleResult ruleResult = mockChainProcessor.applyChain(chainId,
                fwdInfo, pktMatch, ownerId, false);

        Assert.assertEquals(Action.REJECT, ruleResult.action);
    }

    @Test
    public void testJumpNoRejectAction() throws StateAccessException {

        // Target chain, we jump here from main chain
        UUID targetChainId = UUID.randomUUID();
        MockChain mockChainTarget = new MockChain(targetChainId, "targetChain");

        addLiteralRule(nonMatchingCondition, Action.REJECT, mockChain, 10);
        addJumpRule(matchingCondition, mockChainTarget, mockChain, 20);
        addLiteralRule(matchingCondition, Action.REJECT, mockChain, 50);

        addLiteralRule(nonMatchingCondition, Action.REJECT, mockChainTarget, 30);
        addLiteralRule(matchingCondition, Action.DROP, mockChainTarget, 40); //match

        mockChainProcessor.addChain(mockChainTarget);

        RuleResult ruleResult = mockChainProcessor.applyChain(chainId,
                fwdInfo, pktMatch, ownerId, false);

        Assert.assertEquals(Action.DROP, ruleResult.action);
    }

    @Test
    public void testJumpNoAcceptAction2() throws StateAccessException {

        // Target chain, we jump here from main chain
        UUID targetChainId = UUID.randomUUID();
        MockChain mockChainTarget = new MockChain(targetChainId, "targetChain");

        addLiteralRule(nonMatchingCondition, Action.ACCEPT, mockChain, 10);
        addJumpRule(matchingCondition, mockChainTarget, mockChain, 20);
        addLiteralRule(matchingCondition, Action.ACCEPT, mockChain, 50);

        addLiteralRule(nonMatchingCondition, Action.ACCEPT, mockChainTarget, 30);
        addLiteralRule(matchingCondition, Action.DROP, mockChainTarget, 40); //match

        mockChainProcessor.addChain(mockChainTarget);

        RuleResult ruleResult = mockChainProcessor.applyChain(chainId,
                fwdInfo, pktMatch, ownerId, false);

        Assert.assertEquals(Action.DROP, ruleResult.action);
    }

    @Test
    public void testJumpReturnAction() throws StateAccessException {

        // Target chain, we jump here from main chain
        UUID targetChainId = UUID.randomUUID();
        MockChain mockChainTarget = new MockChain(targetChainId, "targetChain");

        addLiteralRule(nonMatchingCondition, Action.ACCEPT, mockChain, 10);
        addJumpRule(matchingCondition, mockChainTarget, mockChain, 20);
        addLiteralRule(matchingCondition, Action.DROP, mockChain, 50); //match

        addLiteralRule(matchingCondition, Action.RETURN, mockChainTarget, 30); //match
        addLiteralRule(matchingCondition, Action.ACCEPT, mockChainTarget, 40);

        mockChainProcessor.addChain(mockChainTarget);

        RuleResult ruleResult = mockChainProcessor.applyChain(chainId,
                fwdInfo, pktMatch, ownerId, false);

        Assert.assertEquals(Action.DROP, ruleResult.action);
    }

    @Test
    public void testForwardDNatAcceptAction() throws StateAccessException {

        Set<NatTarget> set = new TreeSet<NatTarget>();
        set.add(new NatTarget(0x0c000102, 0x0c00010a, (short) 1030,
                (short) 1050));
        addForwardNatRule(matchingCondition, Action.ACCEPT, mockChain, 10, true, set);
        addLiteralRule(matchingCondition, Action.REJECT, mockChain, 20);

        RuleResult ruleResult = mockChainProcessor.applyChain(chainId,
                fwdInfo, pktMatch, ownerId, false);

        Assert.assertEquals(Action.ACCEPT, ruleResult.action);
    }

    @Test
    public void testForwardDNatContinueAction() throws StateAccessException {

        Set<NatTarget> set = new TreeSet<NatTarget>();
        set.add(new NatTarget(0x0c000102, 0x0c00010a, (short) 1030,
                (short) 1050));
        addForwardNatRule(matchingCondition, Action.CONTINUE, mockChain, 10, true, set);
        addLiteralRule(matchingCondition, Action.REJECT, mockChain, 20);

        RuleResult ruleResult = mockChainProcessor.applyChain(chainId,
                fwdInfo, pktMatch, ownerId, false);

        Assert.assertEquals(Action.REJECT, ruleResult.action);
    }

    @Test
    public void testForwardSNatAcceptAction() throws StateAccessException {

        Set<NatTarget> set = new TreeSet<NatTarget>();
        set.add(new NatTarget(0x0c000102, 0x0c00010a, (short) 1030,
                (short) 1050));
        addForwardNatRule(matchingCondition, Action.ACCEPT, mockChain, 10, false, set);
        addLiteralRule(matchingCondition, Action.REJECT, mockChain, 20);

        RuleResult ruleResult = mockChainProcessor.applyChain(chainId,
                fwdInfo, pktMatch, ownerId, false);

        Assert.assertEquals(Action.ACCEPT, ruleResult.action);
    }

    @Test
    public void testForwardSNatContinueAction() throws StateAccessException {

        Set<NatTarget> set = new TreeSet<NatTarget>();
        set.add(new NatTarget(0x0c000102, 0x0c00010a, (short) 1030,
                (short) 1050));
        addForwardNatRule(matchingCondition, Action.CONTINUE, mockChain, 10, false, set);
        addLiteralRule(matchingCondition, Action.REJECT, mockChain, 20);

        RuleResult ruleResult = mockChainProcessor.applyChain(chainId,
                fwdInfo, pktMatch, ownerId, false);

        Assert.assertEquals(Action.ACCEPT, ruleResult.action);
    }

    @Test
    public void testReverseDNatAcceptAction() throws StateAccessException {

        addReverseNatRule(matchingCondition, Action.ACCEPT, mockChain, 10, true);
        addLiteralRule(matchingCondition, Action.REJECT, mockChain, 20); //match

        RuleResult ruleResult = mockChainProcessor.applyChain(chainId,
                fwdInfo, pktMatch, ownerId, false);

        Assert.assertEquals(Action.REJECT, ruleResult.action);
    }

    @Test
    public void testReverseSNATAcceptAction() throws StateAccessException {

        addReverseNatRule(matchingCondition, Action.ACCEPT, mockChain, 10, false);
        addLiteralRule(matchingCondition, Action.REJECT, mockChain, 20); //match

        RuleResult ruleResult = mockChainProcessor.applyChain(chainId,
                fwdInfo, pktMatch, ownerId, false);

        Assert.assertEquals(Action.REJECT, ruleResult.action);
    }

    @Test
    public void testForwardReverseNat() throws
            StateAccessException {

        Set<NatTarget> set = new TreeSet<NatTarget>();
        set.add(new NatTarget(0x0c000102, 0x0c00010a, (short) 1030,
                (short) 1050));
        addForwardNatRule(matchingCondition, Action.CONTINUE, mockChain, 10, false, set); //match
        addReverseNatRule(matchingCondition, Action.RETURN, mockChain, 20, false); //no match
        addLiteralRule(matchingCondition, Action.REJECT, mockChain, 30); //no match

        RuleResult ruleResult = mockChainProcessor.applyChain(chainId,
                fwdInfo, pktMatch, ownerId, false);

        Assert.assertEquals(Action.ACCEPT, ruleResult.action);
    }

    @Test
    public void testForwardForwardNat() throws StateAccessException {

        Set<NatTarget> set = new TreeSet<NatTarget>();
        set.add(new NatTarget(0x0c000102, 0x0c00010a, (short) 1030,
                (short) 1050));
        addForwardNatRule(matchingCondition, Action.CONTINUE, mockChain, 10, false, set); //match
        addForwardNatRule(matchingCondition, Action.ACCEPT, mockChain, 20, false, set); //no match
        addLiteralRule(matchingCondition, Action.REJECT, mockChain, 30); //no match

        RuleResult ruleResult = mockChainProcessor.applyChain(chainId,
                fwdInfo, pktMatch, ownerId, false);

        Assert.assertEquals(Action.ACCEPT, ruleResult.action);
    }

    /*
     * auxiliary methods
     */

    private void addLiteralRule(Condition condition, Action action, MockChain mockChain, int position) {
        Rule rule = new LiteralRule(condition, action, mockChain.getID(), position);
        mockChain.addRule(rule);
    }

    private void addJumpRule(Condition condition, Chain targetChain, MockChain mockChain, int position) {
        Rule rule = new JumpRule(condition, targetChain.getID(), targetChain.getChainName(), mockChain.getID(), position);
        mockChain.addRule(rule);
    }

    private void addForwardNatRule(Condition condition, Action action, MockChain mockChain,
                                   int position, boolean dnat, Set<NatTarget> targets) {
        Rule rule = new ForwardNatRule(condition, action, mockChain.getID(),
                position, dnat, targets);
        mockChain.addRule(rule);
    }

    private void addReverseNatRule(Condition condition, Action action, MockChain mockChain,
                                   int position, boolean dnat) {
        Rule rule = new ReverseNatRule(condition, action, mockChain.getID(),
                position, dnat);
        mockChain.addRule(rule);
    }

}

/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.rules;

import com.midokura.midolman.eventloop.MockReactor;
import com.midokura.midolman.layer4.NatLeaseManager;
import com.midokura.midolman.layer4.NatMapping;
import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.rules.RuleResult.Action;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.FiltersZkManager;
import com.midokura.midolman.state.MockDirectory;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkPathManager;
import com.midokura.midolman.util.MockCache;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public class TestRules {

    static MidoMatch pktMatch;
    static MidoMatch pktResponseMatch;
    static Random rand;
    static UUID inPort;
    static UUID ownerId;
    static UUID jumpChainId;
    static String jumpChainName;
    static Condition cond;
    static Set<NatTarget> nats;
    static NatMapping natMapping;
    RuleResult expRes, argRes;

    @BeforeClass
    public static void setupOnce() throws InterruptedException, KeeperException,
            StateAccessException {
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
        pktResponseMatch = new MidoMatch();
        pktResponseMatch.setInputPort((short) 5);
        pktResponseMatch.setDataLayerDestination("02:11:33:00:11:01");
        pktResponseMatch.setDataLayerSource("02:11:aa:ee:22:05");
        pktResponseMatch.setNetworkDestination(0x0a001406, 32);
        pktResponseMatch.setNetworkSource(0x0a000b22, 32);
        pktResponseMatch.setNetworkProtocol((byte) 6); // TCP
        pktResponseMatch.setNetworkTypeOfService((byte) 34);
        pktResponseMatch.setTransportDestination((short) 4321);
        pktResponseMatch.setTransportSource((short) 1234);
        rand = new Random();
        inPort = new UUID(rand.nextLong(), rand.nextLong());
        ownerId = new UUID(rand.nextLong(), rand.nextLong());
        jumpChainId = new UUID(rand.nextLong(), rand.nextLong());
        jumpChainName = "AJumpChainName";
        // Build a condition that matches the packet.
        cond = new Condition();
        cond.inPortIds = new HashSet<UUID>();
        cond.inPortIds.add(inPort);
        cond.nwSrcIp = 0x0a001400;
        cond.nwSrcLength = 24;
        cond.nwProto = 15;
        cond.nwProtoInv = true;
        cond.tpSrcStart = 2000;
        cond.tpSrcEnd = 3000;
        cond.tpSrcInv = true;
        cond.tpDstStart = 1000;
        cond.tpDstEnd = 2000;

        nats = new HashSet<NatTarget>();
        nats.add(new NatTarget(0x0a090807, 0x0a090810, (short) 21333,
                (short) 32999));

        Directory dir = new MockDirectory();
        ZkPathManager pathMgr = new ZkPathManager("");
        dir.add(pathMgr.getFiltersPath(), null, CreateMode.PERSISTENT);
        FiltersZkManager filterMgr = new FiltersZkManager(dir, "");
        filterMgr.create(ownerId);
        natMapping = new NatLeaseManager(
                filterMgr, ownerId, new MockCache(), new MockReactor());
    }

    @Before
    public void setup() {
        expRes = new RuleResult(null, null, pktMatch.clone(), false);
        argRes = new RuleResult(null, null, pktMatch.clone(), false);
    }

    @Test
    public void testLiteralRuleAccept() {
        Rule rule = new LiteralRule(cond, Action.ACCEPT);
        // If the condition doesn't match the result is not modified.
        rule.process(new MidoMatch(), null, null, argRes, null, null);
        Assert.assertTrue(expRes.equals(argRes));
        rule.process(new MidoMatch(), inPort, null, argRes, null, null);
        expRes.action = Action.ACCEPT;
        Assert.assertTrue(expRes.equals(argRes));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testLiteralRuleContinue() {
        new LiteralRule(cond, Action.CONTINUE);
    }

    @Test
    public void testLiteralRuleDrop() {
        Rule rule = new LiteralRule(cond, Action.DROP);
        // If the condition doesn't match the result is not modified.
        rule.process(new MidoMatch(), null, null, argRes, null, null);
        Assert.assertTrue(expRes.equals(argRes));
        rule.process(new MidoMatch(), inPort, null, argRes, null, null);
        expRes.action = Action.DROP;
        Assert.assertTrue(expRes.equals(argRes));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testLiteralRuleJump() {
        new LiteralRule(cond, Action.JUMP);
    }

    @Test
    public void testLiteralRuleReject() {
        Rule rule = new LiteralRule(cond, Action.REJECT);
        // If the condition doesn't match the result is not modified.
        rule.process(new MidoMatch(), null, null, argRes, null, null);
        Assert.assertTrue(expRes.equals(argRes));
        rule.process(new MidoMatch(), inPort, null, argRes, null, null);
        expRes.action = Action.REJECT;
        Assert.assertTrue(expRes.equals(argRes));
    }

    @Test
    public void testLiteralRuleReturn() {
        Rule rule = new LiteralRule(cond, Action.RETURN);
        // If the condition doesn't match the result is not modified.
        rule.process(new MidoMatch(), null, null, argRes, null, null);
        Assert.assertTrue(expRes.equals(argRes));
        rule.process(new MidoMatch(), inPort, null, argRes, null, null);
        expRes.action = Action.RETURN;
        Assert.assertTrue(expRes.equals(argRes));
    }

    @Test
    public void testJumpRule() {
        Rule rule = new JumpRule(cond, jumpChainId, jumpChainName);
        // If the condition doesn't match the result is not modified.
        rule.process(new MidoMatch(), null, null, argRes, null, null);
        Assert.assertTrue(expRes.equals(argRes));
        rule.process(new MidoMatch(), inPort, null, argRes, null, null);
        expRes.action = Action.JUMP;
        expRes.jumpToChain = jumpChainId;
        Assert.assertTrue(expRes.equals(argRes));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSnatRuleActionDrop() {
        new ForwardNatRule(cond, Action.DROP, null, 0, false, nats);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSnatRuleActionJump() {
        new ForwardNatRule(cond, Action.JUMP, null, 0, false, nats);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSnatRuleActionReject() {
        new ForwardNatRule(cond, Action.REJECT, null, 0, false, nats);
    }

    @Test
    public void testSnatAndReverseRules() {
        Set<NatTarget> nats = new HashSet<NatTarget>();
        nats.add(new NatTarget(0x0b000102, 0x0b00010a, (short) 3366,
                (short) 3399));
        Rule rule = new ForwardNatRule(cond, Action.ACCEPT, null, 0, false,
                nats);
        // If the condition doesn't match the result is not modified.
        rule.process(new MidoMatch(), null, null, argRes, null, null);
        Assert.assertTrue(expRes.equals(argRes));
        // We let the reverse snat rule try reversing everything.
        Rule revRule = new ReverseNatRule(new Condition(), Action.RETURN, false);
        // If the condition doesn't match the result is not modified.
        revRule.process(new MidoMatch(), null, null, argRes, natMapping, null);
        Assert.assertTrue(expRes.equals(argRes));
        // Now get the Snat rule to match.
        rule.process(new MidoMatch(), inPort, null, argRes, natMapping, null);
        Assert.assertEquals(Action.ACCEPT, argRes.action);
        int newNwSrc = argRes.match.getNetworkSource();
        Assert.assertTrue(0x0b000102 <= newNwSrc);
        Assert.assertTrue(newNwSrc <= 0x0b00010a);
        short newTpSrc = argRes.match.getTransportSource();
        Assert.assertTrue(3366 <= newTpSrc);
        Assert.assertTrue(newTpSrc <= 3399);
        Assert.assertTrue(argRes.trackConnection);
        // Now verify that the rest of the packet hasn't changed.
        expRes.match.setNetworkSource(newNwSrc);
        expRes.match.setTransportSource(newTpSrc);
        Assert.assertTrue(expRes.match.equals(argRes.match));
        // Verify we get the same mapping if we re-process the original match.
        expRes = argRes;
        argRes = new RuleResult(null, null, pktMatch.clone(), false);
        rule.process(new MidoMatch(), inPort, null, argRes, natMapping, null);
        Assert.assertTrue(expRes.equals(argRes));
        // Now use the new ip/port in the return packet.
        argRes.match = pktResponseMatch.clone();
        Assert.assertFalse(pktResponseMatch.getNetworkDestination() == newNwSrc);
        argRes.match.setNetworkDestination(newNwSrc);
        Assert.assertFalse(pktResponseMatch.getTransportDestination() == newTpSrc);
        argRes.match.setTransportDestination(newTpSrc);
        argRes.action = null;
        argRes.trackConnection = false;
        revRule.process(new MidoMatch(), null, null, argRes, natMapping, null);
        Assert.assertEquals(Action.RETURN, argRes.action);
        // The generated response should be the mirror of the original.
        Assert.assertTrue(pktResponseMatch.equals(argRes.match));
        Assert.assertFalse(argRes.trackConnection);
    }

    @Test
    public void testDnatAndReverseRule() {
        Set<NatTarget> nats = new HashSet<NatTarget>();
        nats.add(new NatTarget(0x0c000102, 0x0c00010a, (short) 1030,
                (short) 1050));
        Rule rule = new ForwardNatRule(cond, Action.CONTINUE, null, 0, true,
                nats);
        // If the condition doesn't match the result is not modified.
        rule.process(new MidoMatch(), null, null, argRes, natMapping, null);
        Assert.assertTrue(expRes.equals(argRes));
        // We let the reverse dnat rule try reversing everything.
        Rule revRule = new ReverseNatRule(new Condition(), Action.ACCEPT, true);
        // If the condition doesn't match the result is not modified.
        revRule.process(new MidoMatch(), null, null, argRes, natMapping, null);
        Assert.assertTrue(expRes.equals(argRes));
        // Now get the Dnat rule to match.
        rule.process(new MidoMatch(), inPort, null, argRes, natMapping, null);
        Assert.assertEquals(Action.CONTINUE, argRes.action);
        int newNwDst = argRes.match.getNetworkDestination();
        Assert.assertTrue(0x0c000102 <= newNwDst);
        Assert.assertTrue(newNwDst <= 0x0c00010a);
        short newTpDst = argRes.match.getTransportDestination();
        Assert.assertTrue(1030 <= newTpDst);
        Assert.assertTrue(newTpDst <= 1050);
        Assert.assertTrue(argRes.trackConnection);
        // Now verify that the rest of the packet hasn't changed.
        expRes.match.setNetworkDestination(newNwDst);
        expRes.match.setTransportDestination(newTpDst);
        Assert.assertTrue(expRes.match.equals(argRes.match));
        // Verify we get the same mapping if we re-process the original match.
        expRes = argRes;
        argRes = new RuleResult(null, null, pktMatch.clone(), false);
        rule.process(new MidoMatch(), inPort, null, argRes, natMapping, null);
        Assert.assertTrue(expRes.equals(argRes));
        // Now use the new ip/port in the return packet.
        argRes.match = pktResponseMatch.clone();
        Assert.assertFalse(pktResponseMatch.getNetworkSource() == newNwDst);
        argRes.match.setNetworkSource(newNwDst);
        Assert.assertFalse(pktResponseMatch.getTransportSource() == newTpDst);
        argRes.match.setTransportSource(newTpDst);
        argRes.action = null;
        argRes.trackConnection = false;
        revRule.process(new MidoMatch(), null, null, argRes, natMapping, null);
        Assert.assertEquals(Action.ACCEPT, argRes.action);
        // The generated response should be the mirror of the original.
        Assert.assertTrue(pktResponseMatch.equals(argRes.match));
        Assert.assertFalse(argRes.trackConnection);
    }

}

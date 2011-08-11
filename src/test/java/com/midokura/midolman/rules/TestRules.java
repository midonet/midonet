package com.midokura.midolman.rules;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;

import org.junit.Test;

import com.midokura.midolman.layer4.MockNatMapping;
import com.midokura.midolman.layer4.NatMapping;
import com.midokura.midolman.openflow.MidoMatch;

public class TestRules {

    static MidoMatch pktMatch;
    static MidoMatch pktResponseMatch;
    static Random rand;
    static UUID inPort;
    static Condition cond;
    RuleResult expRes, argRes;

    @BeforeClass
    public static void setupOnce() {
        pktMatch = new MidoMatch();
        pktMatch.setInputPort((short) 5);
        pktMatch.setDataLayerSource("02:11:33:00:11:01");
        pktMatch.setDataLayerDestination("02:11:aa:ee:22:05");
        pktMatch.setNetworkSource(0x0a001406, 32);
        pktMatch.setNetworkDestination(0x0a000b22, 32);
        pktMatch.setNetworkProtocol((byte) 8);
        pktMatch.setNetworkTypeOfService((byte) 34);
        pktMatch.setTransportSource((short) 4321);
        pktMatch.setTransportDestination((short) 1234);
        pktResponseMatch = new MidoMatch();
        pktResponseMatch.setInputPort((short) 5);
        pktResponseMatch.setDataLayerDestination("02:11:33:00:11:01");
        pktResponseMatch.setDataLayerSource("02:11:aa:ee:22:05");
        pktResponseMatch.setNetworkDestination(0x0a001406, 32);
        pktResponseMatch.setNetworkSource(0x0a000b22, 32);
        pktResponseMatch.setNetworkProtocol((byte) 8);
        pktResponseMatch.setNetworkTypeOfService((byte) 34);
        pktResponseMatch.setTransportDestination((short) 4321);
        pktResponseMatch.setTransportSource((short) 1234);
        rand = new Random();
        inPort = new UUID(rand.nextLong(), rand.nextLong());
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
        rule.process(null, null, argRes);
        Assert.assertTrue(expRes.equals(argRes));
        rule.process(inPort, null, argRes);
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
        rule.process(null, null, argRes);
        Assert.assertTrue(expRes.equals(argRes));
        rule.process(inPort, null, argRes);
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
        rule.process(null, null, argRes);
        Assert.assertTrue(expRes.equals(argRes));
        rule.process(inPort, null, argRes);
        expRes.action = Action.REJECT;
        Assert.assertTrue(expRes.equals(argRes));
    }

    @Test
    public void testLiteralRuleReturn() {
        Rule rule = new LiteralRule(cond, Action.RETURN);
        // If the condition doesn't match the result is not modified.
        rule.process(null, null, argRes);
        Assert.assertTrue(expRes.equals(argRes));
        rule.process(inPort, null, argRes);
        expRes.action = Action.RETURN;
        Assert.assertTrue(expRes.equals(argRes));
    }

    @Test
    public void testJumpRule() {
        String jChain = "SomeChainName";
        Rule rule = new JumpRule(cond, jChain);
        // If the condition doesn't match the result is not modified.
        rule.process(null, null, argRes);
        Assert.assertTrue(expRes.equals(argRes));
        rule.process(inPort, null, argRes);
        expRes.action = Action.JUMP;
        expRes.jumpToChain = jChain;
        Assert.assertTrue(expRes.equals(argRes));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSnatRuleActionDrop() {
        new SnatRule(cond, null, Action.DROP);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSnatRuleActionJump() {
        new SnatRule(cond, null, Action.JUMP);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSnatRuleActionReject() {
        new SnatRule(cond, null, Action.REJECT);
    }

    @Test
    public void testSnatAndReverseRules() {
        Set<NatTarget> nats = new HashSet<NatTarget>();
        nats.add(new NatTarget(0x0b000102, 0x0b00010a, (short) 3366,
                (short) 3399));
        Rule rule = new SnatRule(cond, nats, Action.ACCEPT);
        NatMapping natMap = new MockNatMapping();
        ((NatRule) rule).setNatMapping(natMap);
        // If the condition doesn't match the result is not modified.
        rule.process(null, null, argRes);
        Assert.assertTrue(expRes.equals(argRes));
        // We let the reverse snat rule try reversing everything.
        Rule revRule = new ReverseSnatRule(new Condition(), Action.RETURN);
        ((NatRule) revRule).setNatMapping(natMap);
        // If the condition doesn't match the result is not modified.
        revRule.process(null, null, argRes);
        Assert.assertTrue(expRes.equals(argRes));
        // Now get the Snat rule to match.
        rule.process(inPort, null, argRes);
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
        rule.process(inPort, null, argRes);
        Assert.assertTrue(expRes.equals(argRes));
        // Now use the new ip/port in the return packet.
        argRes.match = pktResponseMatch.clone();
        Assert.assertFalse(pktResponseMatch.getNetworkDestination() == newNwSrc);
        argRes.match.setNetworkDestination(newNwSrc);
        Assert.assertFalse(pktResponseMatch.getTransportDestination() == newTpSrc);
        argRes.match.setTransportDestination(newTpSrc);
        argRes.action = null;
        argRes.trackConnection = false;
        revRule.process(null, null, argRes);
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
        Rule rule = new DnatRule(cond, nats, Action.CONTINUE);
        NatMapping natMap = new MockNatMapping();
        ((NatRule) rule).setNatMapping(natMap);
        // If the condition doesn't match the result is not modified.
        rule.process(null, null, argRes);
        Assert.assertTrue(expRes.equals(argRes));
        // We let the reverse dnat rule try reversing everything.
        Rule revRule = new ReverseDnatRule(new Condition(), Action.ACCEPT);
        ((NatRule) revRule).setNatMapping(natMap);
        // If the condition doesn't match the result is not modified.
        revRule.process(null, null, argRes);
        Assert.assertTrue(expRes.equals(argRes));
        // Now get the Dnat rule to match.
        rule.process(inPort, null, argRes);
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
        rule.process(inPort, null, argRes);
        Assert.assertTrue(expRes.equals(argRes));
        // Now use the new ip/port in the return packet.
        argRes.match = pktResponseMatch.clone();
        Assert.assertFalse(pktResponseMatch.getNetworkSource() == newNwDst);
        argRes.match.setNetworkSource(newNwDst);
        Assert.assertFalse(pktResponseMatch.getTransportSource() == newTpDst);
        argRes.match.setTransportSource(newTpDst);
        argRes.action = null;
        argRes.trackConnection = false;
        revRule.process(null, null, argRes);
        Assert.assertEquals(Action.ACCEPT, argRes.action);
        // The generated response should be the mirror of the original.
        Assert.assertTrue(pktResponseMatch.equals(argRes.match));
        Assert.assertFalse(argRes.trackConnection);
    }

}

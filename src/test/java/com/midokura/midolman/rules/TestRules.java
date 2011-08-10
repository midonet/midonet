package com.midokura.midolman.rules;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;

import org.junit.Test;

import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.routing.MockNatMapping;

public class TestRules {

    static MidoMatch pktMatch;
    static Random rand;
    static UUID inPort;
    static Condition cond;
    RuleResult expRes, argRes;

    @BeforeClass
    public static void setupOnce() {
        pktMatch = new MidoMatch();
        pktMatch.setInputPort((short)5);
        pktMatch.setDataLayerSource("02:11:33:00:11:01");
        pktMatch.setDataLayerDestination("02:11:aa:ee:22:05");
        pktMatch.setNetworkSource(0x0a001406, 32);
        pktMatch.setNetworkDestination(0x0a000b22, 32);
        pktMatch.setNetworkProtocol((byte)8);
        pktMatch.setNetworkTypeOfService((byte)34);
        pktMatch.setTransportSource((short)4321);
        pktMatch.setTransportDestination((short)1234);
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
        expRes = new RuleResult(null, null, pktMatch, false);
        argRes = new RuleResult(null, null, pktMatch, false);
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

    @Test(expected=IllegalArgumentException.class)
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

    @Test(expected=IllegalArgumentException.class)
    public void testLiteralRuleJUMP() {
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

    @Test(expected=IllegalArgumentException.class)
    public void testSnatRuleActionDrop() {
        Rule rule = new SnatRule(cond, null, Action.DROP);
    }

    @Test(expected=IllegalArgumentException.class)
    public void testSnatRuleActionJump() {
        Rule rule = new SnatRule(cond, null, Action.JUMP);
    }

    @Test(expected=IllegalArgumentException.class)
    public void testSnatRuleActionReject() {
        Rule rule = new SnatRule(cond, null, Action.REJECT);
    }

    @Test
    public void testSnatRule() {
        Set<NatTarget> nats = new HashSet<NatTarget>();
        nats.add(new NatTarget(0x0b000108, 0x0b000108, (short)3366,
                (short)3399));
        Rule rule = new SnatRule(cond, nats, Action.ACCEPT);
        ((SnatRule)rule).setNatMapping(new MockNatMapping());
        // If the condition doesn't match the result is not modified.
        rule.process(null, null, argRes);
        Assert.assertTrue(expRes.equals(argRes));
        rule.process(inPort, null, argRes);
        Assert.assertEquals(Action.ACCEPT, argRes.action);
        Assert.assertEquals(0x0b000108, argRes.match.getNetworkSource());
        short newTpSrc = argRes.match.getTransportSource();
        Assert.assertTrue(3366 <= newTpSrc);
        Assert.assertTrue(newTpSrc <= 3399);
        Assert.assertTrue(argRes.trackConnection);
        Assert.assertEquals(Action.ACCEPT, argRes.action);
    }

    @Test
    public void testDnatRule() {
        
    }

    @Test
    public void testReverseSnatRule() {
        
    }

    @Test
    public void testReverseDnatRule() {
        
    }

}

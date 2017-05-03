/*
 * Copyright 2015 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.midolman.rules;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.helpers.NOPLogger;

import org.midonet.midolman.TraceRequiredException;
import org.midonet.midolman.rules.RuleResult.Action;
import org.midonet.midolman.simulation.PacketContext;
import org.midonet.midolman.state.HappyGoLuckyLeaser$;
import org.midonet.midolman.state.TraceState;
import org.midonet.odp.FlowMatch;
import org.midonet.odp.Packet;
import org.midonet.packets.Ethernet;
import org.midonet.packets.ICMP;
import org.midonet.packets.IPv4;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;
import org.midonet.packets.ConnTrackState.ConnTrackKeyStore;
import org.midonet.packets.NatState.NatKeyStore;
import org.midonet.packets.NatState.NatBinding;
import org.midonet.packets.TraceState.TraceKeyStore;
import org.midonet.packets.TCP;
import org.midonet.sdn.state.FlowStateTable;
import org.midonet.sdn.state.FlowStateTransaction;
import org.midonet.sdn.state.OnHeapShardedFlowStateTable;
import org.midonet.util.Range;
import org.midonet.util.concurrent.MockClock;
import org.midonet.util.concurrent.NanoClock;
import org.midonet.util.logging.Logger$;

public class TestRules {

    private FlowMatch pktMatch;
    private FlowMatch pktResponseMatch;
    private Random rand;
    private UUID inPort;
    private UUID ownerId;
    private UUID jumpChainId;
    private String jumpChainName;
    private Condition cond;
    private Set<NatTarget> nats;
    private PacketContext pktCtx;
    private FlowStateTransaction<ConnTrackKeyStore, Boolean> conntrackTx;
    private FlowStateTransaction<NatKeyStore, NatBinding> natTx;
    private FlowStateTransaction<TraceKeyStore, TraceState.TraceContext> traceTx;

    @Before
    public void setup() {
        pktMatch = new FlowMatch();
        pktMatch.setInputPortNumber((short) 5);
        pktMatch.setEthSrc("02:11:33:00:11:01");
        pktMatch.setEthDst("02:11:aa:ee:22:05");
        pktMatch.setEtherType(IPv4.ETHERTYPE);
        pktMatch.setNetworkSrc(IPv4Addr.fromInt(0x0a001406));
        pktMatch.setNetworkDst(IPv4Addr.fromInt(0x0a000b22));
        pktMatch.setNetworkProto((byte) 6); // TCP
        pktMatch.setNetworkTOS((byte) 34);
        pktMatch.setSrcPort(4321);
        pktMatch.setDstPort(1234);
        pktResponseMatch = new FlowMatch();
        pktResponseMatch.setInputPortNumber((short) 5);
        pktResponseMatch.setEthDst("02:11:33:00:11:01");
        pktResponseMatch.setEthSrc("02:11:aa:ee:22:05");
        pktResponseMatch.setEtherType(IPv4.ETHERTYPE);
        pktResponseMatch.setNetworkDst(IPv4Addr.fromInt(0x0a001406));
        pktResponseMatch.setNetworkSrc(IPv4Addr.fromInt(0x0a000b22));
        pktResponseMatch.setNetworkProto((byte) 6); // TCP
        pktResponseMatch.setNetworkTOS((byte) 34);
        pktResponseMatch.setDstPort(4321);
        pktResponseMatch.setSrcPort(1234);
        rand = new Random();
        inPort = new UUID(rand.nextLong(), rand.nextLong());
        ownerId = new UUID(rand.nextLong(), rand.nextLong());
        jumpChainId = new UUID(rand.nextLong(), rand.nextLong());
        jumpChainName = "AJumpChainName";
        // Build a condition that matches the packet.
        cond = new Condition();
        cond.inPortIds = new HashSet<>();
        cond.inPortIds.add(inPort);
        cond.nwSrcIp = new IPv4Subnet(IPv4Addr.fromString("10.0.20.0"), 24);
        cond.nwProto = 15;
        cond.nwProtoInv = true;
        cond.tpSrc = new Range<>(2000, 3000);
        cond.tpSrcInv = true;
        cond.tpDst = new Range<>(1000, 2000);

        nats = new HashSet<>();
        nats.add(new NatTarget(0x0a090807, 0x0a090810, 21333,
                32999));

        NanoClock clock = new MockClock();
        pktCtx = PacketContext.generatedForJava(1, null, pktMatch, null);
        @SuppressWarnings("unchecked")
        OnHeapShardedFlowStateTable<ConnTrackKeyStore, Boolean> shardedConntrack =
            new OnHeapShardedFlowStateTable<ConnTrackKeyStore, Boolean>(clock);
        pktCtx = PacketContext.generatedForJava(1, null, pktMatch, null);
        FlowStateTable<ConnTrackKeyStore, Boolean> conntrackTable =
                shardedConntrack.addShard(
                    Logger$.MODULE$.apply(NOPLogger.NOP_LOGGER));
        OnHeapShardedFlowStateTable<NatKeyStore, NatBinding> shardedNat =
            new OnHeapShardedFlowStateTable<NatKeyStore, NatBinding>(clock);
        FlowStateTable<NatKeyStore, NatBinding> natTable =
                shardedNat.addShard(Logger$.MODULE$.apply(NOPLogger.NOP_LOGGER));
        FlowStateTable<TraceKeyStore, TraceState.TraceContext> traceTable =
            new OnHeapShardedFlowStateTable<TraceKeyStore,TraceState.TraceContext>(clock)
            .addShard(
                    Logger$.MODULE$.apply(NOPLogger.NOP_LOGGER));
        conntrackTx = new FlowStateTransaction<>(conntrackTable);
        natTx = new FlowStateTransaction<>(natTable);
        traceTx = new FlowStateTransaction<>(traceTable);
        pktCtx.initialize(conntrackTx, natTx, HappyGoLuckyLeaser$.MODULE$,
                          traceTx);
        pktCtx.currentDevice_$eq(ownerId);
    }

    private Pair<Action, Boolean> actionAndMatched(Pair<RuleResult, Boolean> pair) {
        return Pair.of(pair.getLeft().action, pair.getRight());
    }

    private void checkMatched(boolean matched, PacketContext pktCtx) {
        Assert.assertEquals(matched,
            pktCtx.traversedRulesMatched()
                  .get(pktCtx.traversedRulesMatched().size() - 1));
    }

    private void checkApplied(boolean applied, PacketContext pktCtx) {
        Assert.assertEquals(applied,
            pktCtx.traversedRulesApplied()
                  .get(pktCtx.traversedRulesApplied().size() - 1));
    }

    @Test
    public void testLiteralRuleAccept() {
        Rule rule = new LiteralRule(cond, Action.ACCEPT);
        // If the condition doesn't match the result is not modified.
        RuleResult res = rule.process(pktCtx);
        Assert.assertEquals(Action.CONTINUE, res.action);
        checkMatched(false, pktCtx);
        checkApplied(false, pktCtx);
        pktCtx.inPortId_$eq(inPort);
        res = rule.process(pktCtx);
        Assert.assertEquals(Action.ACCEPT, res.action);
        checkMatched(true, pktCtx);
        checkApplied(true, pktCtx);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testLiteralRuleContinue() {
        new LiteralRule(cond, Action.CONTINUE);
    }

    @Test
    public void testLiteralRuleDrop() {
        Rule rule = new LiteralRule(cond, Action.DROP);
        // If the condition doesn't match the result is not modified.
        RuleResult res = rule.process(pktCtx);
        Assert.assertEquals(Action.CONTINUE, res.action);
        checkMatched(false, pktCtx);
        checkApplied(false, pktCtx);
        pktCtx.inPortId_$eq(inPort);
        res = rule.process(pktCtx);
        Assert.assertEquals(Action.DROP, res.action);
        checkMatched(true, pktCtx);
        checkApplied(true, pktCtx);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testLiteralRuleJump() {
        new LiteralRule(cond, Action.JUMP);
    }

    @Test
    public void testLiteralRuleReject() {
        Rule rule = new LiteralRule(cond, Action.REJECT);
        // If the condition doesn't match the result is not modified.
        RuleResult res = rule.process(pktCtx);
        Assert.assertEquals(Action.CONTINUE, res.action);
        checkMatched(false, pktCtx);
        checkApplied(false, pktCtx);
        pktCtx.inPortId_$eq(inPort);
        res = rule.process(pktCtx);
        Assert.assertEquals(Action.REJECT, res.action);
        checkMatched(true, pktCtx);
        checkApplied(true, pktCtx);
    }

    @Test
    public void testLiteralRuleReturn() {
        Rule rule = new LiteralRule(cond, Action.RETURN);
        // If the condition doesn't match the result is not modified.
        RuleResult res = rule.process(pktCtx);
        Assert.assertEquals(Action.CONTINUE, res.action);
        checkMatched(false, pktCtx);
        checkApplied(false, pktCtx);
        pktCtx.inPortId_$eq(inPort);
        res = rule.process(pktCtx);
        Assert.assertEquals(Action.RETURN, res.action);
        checkMatched(true, pktCtx);
        checkApplied(true, pktCtx);
    }

    private Ethernet createTracePacket() {
        /* Generate the actual packet as it is used
         * to look up the flow in the trace table
         */
        TCP tcp = new TCP();
        tcp.setSourcePort(pktMatch.getSrcPort());
        tcp.setDestinationPort(pktMatch.getDstPort());
        IPv4 ip = new IPv4();
        ip.setSourceAddress(IPv4Addr.fromInt(0x0a001406));
        ip.setDestinationAddress(IPv4Addr.fromInt(0x0a000b22));
        ip.setProtocol(pktMatch.getNetworkProto());
        ip.setDiffServ(pktMatch.getNetworkTOS());
        ip.setPayload(tcp);
        Ethernet eth = new Ethernet();
        eth.setSourceMACAddress(pktMatch.getEthSrc());
        eth.setDestinationMACAddress(pktMatch.getEthDst());
        eth.setEtherType(pktMatch.getEtherType());
        eth.setPayload(ip);
        return eth;
    }

    @Test
    public void testTraceRule() {
        UUID requestId = UUID.randomUUID();
        UUID requestId2 = UUID.randomUUID();

        Rule rule = new TraceRule(requestId, cond,
                                  Long.MAX_VALUE, UUID.randomUUID());
        Rule rule2 = new TraceRule(requestId2, cond,
                                   Long.MAX_VALUE, UUID.randomUUID());

        Ethernet eth = createTracePacket();
        pktCtx = PacketContext.generatedForJava(1, new Packet(eth, pktMatch),
                                                pktMatch, null);
        pktCtx.initialize(conntrackTx, natTx, HappyGoLuckyLeaser$.MODULE$,
                          traceTx);

        // If the condition doesn't match the result is not modified.
        RuleResult res = rule.process(pktCtx);
        Assert.assertEquals(Action.CONTINUE, res.action);
        checkMatched(false, pktCtx);
        checkApplied(false, pktCtx);

        pktCtx.inPortId_$eq(inPort);
        try {
            rule.process(pktCtx);
            Assert.fail("Processing a trace rule without the"
                        + " trace bit set should error");
        } catch (Exception tre) {
            Assert.assertEquals("Should be trace required exception",
                                tre, TraceRequiredException.instance());
            Assert.assertTrue("Trace is enabled for requestId",
                    pktCtx.tracingEnabled(requestId));
            Assert.assertFalse("Trace is not enabled for requestId2",
                    pktCtx.tracingEnabled(requestId2));
        }

        res = rule.process(pktCtx);
        Assert.assertEquals(Action.CONTINUE, res.action);
        checkMatched(true, pktCtx);
        checkApplied(true, pktCtx);

        try {
            rule2.process(pktCtx);
            Assert.fail("Should throw exception");
        } catch (Exception tre) {
            Assert.assertEquals("Should be trace required exception",
                                tre, TraceRequiredException.instance());
            Assert.assertTrue("Trace is enabled for requestId",
                    pktCtx.tracingEnabled(requestId));
            Assert.assertTrue("Trace is enabled for requestId2",
                    pktCtx.tracingEnabled(requestId2));
        }
    }

    @Test
    public void testTraceRuleLimit() {
        UUID requestId = UUID.randomUUID();

        long limit = 10;
        Rule rule = new TraceRule(requestId, cond, 10, UUID.randomUUID());

        Ethernet eth = createTracePacket();
        PacketContext pktCtx;
        for (int i = 0; i < limit; i++) {
            pktCtx = PacketContext.generatedForJava(1, new Packet(eth, pktMatch),
                                             pktMatch, null);
            pktCtx.initialize(conntrackTx, natTx, HappyGoLuckyLeaser$.MODULE$,
                              traceTx);
            pktCtx.inPortId_$eq(inPort);

            try {
                rule.process(pktCtx);

                Assert.fail("Processing a trace rule without the"
                            + " trace bit set should error");
            } catch (Exception tre) {
                Assert.assertEquals("Should be trace required exception",
                                    tre, TraceRequiredException.instance());
                Assert.assertTrue("Trace is enabled for requestId",
                                  pktCtx.tracingEnabled(requestId));
            }
        }
        pktCtx = PacketContext.generatedForJava(1, new Packet(eth, pktMatch),
                                                pktMatch, null);
        pktCtx.initialize(conntrackTx, natTx, HappyGoLuckyLeaser$.MODULE$,
                          traceTx);
        pktCtx.inPortId_$eq(inPort);

        rule.process(pktCtx);
        Assert.assertFalse("Trace is enabled for requestId",
                           pktCtx.tracingEnabled(requestId));
    }

    @Test
    public void testJumpRule() {
        Rule rule = new JumpRule(cond, jumpChainId, jumpChainName);
        // If the condition doesn't match the result is not modified.
        RuleResult res = rule.process(pktCtx);
        Assert.assertEquals(Action.CONTINUE, res.action);
        checkMatched(false, pktCtx);
        checkApplied(false, pktCtx);
        pktCtx.inPortId_$eq(inPort);
        res = rule.process(pktCtx);
        Assert.assertEquals(Action.JUMP, res.action);
        Assert.assertEquals(jumpChainId, res.jumpToChain);
        checkMatched(true, pktCtx);
        checkApplied(true, pktCtx);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSnatRuleActionDrop() {
        new DynamicForwardNatRule(cond, Action.DROP, null, false, nats);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSnatRuleActionJump() {
        new DynamicForwardNatRule(cond, Action.JUMP, null, false, nats);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSnatRuleActionReject() {
        new DynamicForwardNatRule(cond, Action.REJECT, null, false, nats);
    }

    @Test
    public void testSnatAndReverseRules() {
        Set<NatTarget> nats = new HashSet<>();
        nats.add(new NatTarget(0x0b000102, 0x0b00010a, 3366, 3399));
        Rule rule = new DynamicForwardNatRule(cond, Action.ACCEPT, null, false, nats);
        // If the condition doesn't match the result is not modified.
        RuleResult res = rule.process(pktCtx);
        natTx.commit();
        Assert.assertEquals(Action.CONTINUE, res.action);
        checkMatched(false, pktCtx);
        checkApplied(false, pktCtx);
        // We let the reverse snat rule try reversing everything.
        Rule revRule = new ReverseNatRule(new Condition(), Action.RETURN, false);
        res = revRule.process(pktCtx);
        Assert.assertEquals(Action.CONTINUE, res.action);
        checkMatched(true, pktCtx);
        checkApplied(false, pktCtx);
        // Now get the Snat rule to match.
        pktCtx.inPortId_$eq(inPort);
        res = rule.process(pktCtx);
        Assert.assertEquals(Action.ACCEPT, res.action);
        checkMatched(true, pktCtx);
        checkApplied(true, pktCtx);
        IPv4Addr newNwSrc = (IPv4Addr)(pktCtx.wcmatch().getNetworkSrcIP());
        Assert.assertTrue(0x0b000102 <= newNwSrc.toInt());
        Assert.assertTrue(newNwSrc.toInt() <= 0x0b00010a);
        int newTpSrc = pktCtx.wcmatch().getSrcPort();
        Assert.assertTrue(3366 <= newTpSrc);
        Assert.assertTrue(newTpSrc <= 3399);
        // Now verify that the rest of the packet hasn't changed.
        FlowMatch expected = pktCtx.origMatch().clone();
        expected.setNetworkSrc(newNwSrc);
        expected.setSrcPort(newTpSrc);
        Assert.assertEquals(expected, pktCtx.wcmatch());
        // Verify we get the same mapping if we re-process the original match.
        pktCtx.wcmatch().reset(pktCtx.origMatch());
        rule.process(pktCtx);
        Assert.assertEquals(expected, pktCtx.wcmatch());
        // Now use the new ip/port in the return packet.
        pktCtx.wcmatch().reset(pktResponseMatch);
        Assert.assertNotSame(pktResponseMatch.getNetworkDstIP(),
                             newNwSrc);
        pktCtx.wcmatch().setNetworkDst(newNwSrc);
        Assert.assertNotSame(pktResponseMatch.getDstPort(), newTpSrc);
        pktCtx.wcmatch().setDstPort(newTpSrc);
        res = revRule.process(pktCtx);
        Assert.assertEquals(Action.RETURN, res.action);
        checkMatched(true, pktCtx);
        checkApplied(true, pktCtx);
        // The generated response should be the mirror of the original.
        Assert.assertEquals(pktResponseMatch, pktCtx.wcmatch());
    }

    @Test
    public void testDnatAndReverseRule() {
        Set<NatTarget> nats = new HashSet<>();
        nats.add(new NatTarget(0x0c000102, 0x0c00010a, 1030, 1050));
        Rule rule = new DynamicForwardNatRule(cond, Action.CONTINUE, null, true, nats);
        // If the condition doesn't match the result is not modified.
        RuleResult res = rule.process(pktCtx);
        Assert.assertEquals(res.action, Action.CONTINUE);
        checkMatched(false, pktCtx);
        checkApplied(false, pktCtx);
        Assert.assertEquals(pktCtx.origMatch(), pktCtx.wcmatch());
        // We let the reverse dnat rule try reversing everything.
        Rule revRule = new ReverseNatRule(new Condition(), Action.ACCEPT, true);
        res = revRule.process(pktCtx);
        // If the condition doesn't match the result is not modified.
        Assert.assertEquals(res.action, Action.CONTINUE);
        checkMatched(true, pktCtx);
        checkApplied(false, pktCtx);
        Assert.assertEquals(pktCtx.origMatch(), pktCtx.wcmatch());
        // Now get the Dnat rule to match.
        pktCtx.inPortId_$eq(inPort);
        res = rule.process(pktCtx);
        Assert.assertEquals(Action.CONTINUE, res.action);
        checkMatched(true, pktCtx);
        checkApplied(true, pktCtx);
        int newNwDst = ((IPv4Addr) pktCtx.wcmatch().getNetworkDstIP()).toInt();
        Assert.assertTrue(0x0c000102 <= newNwDst);
        Assert.assertTrue(newNwDst <= 0x0c00010a);
        int newTpDst = pktCtx.wcmatch().getDstPort();
        Assert.assertTrue(1030 <= newTpDst);
        Assert.assertTrue(newTpDst <= 1050);
        // Now verify that the rest of the packet hasn't changed.
        FlowMatch expected = pktCtx.origMatch().clone();
        expected.setNetworkDst(IPv4Addr.fromInt(newNwDst));
        expected.setDstPort(newTpDst);
        Assert.assertEquals(pktCtx.wcmatch(), expected);
        // Verify we get the same mapping if we re-process the original match.
        pktCtx.wcmatch().reset(pktCtx.origMatch());
        rule.process(pktCtx);
        Assert.assertEquals(pktCtx.wcmatch(), expected);
        // Now use the new ip/port in the return packet.
        pktCtx.wcmatch().reset(pktResponseMatch.clone());
        Assert.assertTrue(IPv4Addr.fromInt(newNwDst).canEqual(
                          pktResponseMatch.getNetworkSrcIP()));
        Assert.assertFalse(IPv4Addr.fromInt(newNwDst).equals(
                           pktResponseMatch.getNetworkSrcIP()));

        pktCtx.wcmatch().setNetworkSrc(IPv4Addr.fromInt(newNwDst));
        Assert.assertNotSame(pktResponseMatch.getSrcPort(), newTpDst);
        pktCtx.wcmatch().setSrcPort(newTpDst);
        pktCtx.inPortId_$eq(null);
        res = revRule.process(pktCtx);
        Assert.assertEquals(Action.ACCEPT, res.action);
        checkMatched(true, pktCtx);
        checkApplied(true, pktCtx);
        // The generated response should be the mirror of the original.
        Assert.assertEquals(pktResponseMatch, pktCtx.wcmatch());
    }

    @Test
    public void testDnatAndReverseRuleDeleteMapping() {
        Set<NatTarget> nats = new HashSet<NatTarget>();
        nats.add(new NatTarget(0x0c000102, 0x0c00010a, 1030, 1050));
        Rule rule = new DynamicForwardNatRule(cond, Action.CONTINUE, null, true,
                nats);

        // Now get the Dnat rule to match.
        pktCtx.inPortId_$eq(inPort);
        RuleResult res = rule.process(pktCtx);
        Assert.assertEquals(Action.CONTINUE, res.action);
        checkMatched(true, pktCtx);
        checkApplied(true, pktCtx);
        int firstNwDst = ((IPv4Addr) pktCtx.wcmatch().getNetworkDstIP()).toInt();
        Assert.assertTrue(0x0c000102 <= firstNwDst);
        Assert.assertTrue(firstNwDst <= 0x0c00010a);
        int firstTpDst = pktCtx.wcmatch().getDstPort();
        Assert.assertTrue(1030 <= firstTpDst);
        Assert.assertTrue(firstTpDst <= 1050);

        // Now verify that the rest of the packet hasn't changed.
        FlowMatch expected = pktCtx.origMatch().clone();
        expected.setNetworkDst(IPv4Addr.fromInt(firstNwDst));
        expected.setDstPort(firstTpDst);
        Assert.assertEquals(pktCtx.wcmatch(), expected);

        // Verify we get the same mapping if we re-process the original match.
        pktCtx.wcmatch().reset(pktCtx.origMatch());
        res = rule.process(pktCtx);
        int secondNwDst = ((IPv4Addr) pktCtx.wcmatch().getNetworkDstIP()).toInt();
        int secondTpDst = pktCtx.wcmatch().getDstPort();
        Assert.assertEquals(expected, pktCtx.wcmatch());
        Assert.assertEquals(Action.CONTINUE, res.action);
        checkMatched(true, pktCtx);
        checkApplied(true, pktCtx);
        Assert.assertEquals(firstNwDst, secondNwDst);
        Assert.assertEquals(firstTpDst, secondTpDst);

        // Delete the DNAT entry
        natTx.flush();

        Set<NatTarget> newNats = new HashSet<>();
        newNats.add(new NatTarget(0x0c00010b, 0x0c00010b, 1060, 1060));
        rule = new DynamicForwardNatRule(cond, Action.CONTINUE, null, true, newNats);

        // Verify we get a NEW mapping if we re-process the original match.
        pktCtx.wcmatch().reset(pktCtx.origMatch());
        rule.process(pktCtx);
        int thirdNwDst = ((IPv4Addr) pktCtx.wcmatch().getNetworkDstIP()).toInt();
        int thirdTpDst = pktCtx.wcmatch().getDstPort();
        Assert.assertNotEquals(expected, pktCtx.wcmatch());
        Assert.assertNotSame(firstNwDst, thirdNwDst);
        Assert.assertNotSame(firstTpDst, thirdTpDst);
    }

    /**
     * Test static dnat, as used for floating IPs
     */
    @Test
    public void testStaticDnat() {
        IPv4Addr fixedIp = IPv4Addr.fromString("10.10.10.10");
        IPv4Addr floatingIp = IPv4Addr.fromString("1.1.1.1");
        IPv4Addr externalIp = IPv4Addr.fromString("2.2.2.2");

        Set<NatTarget> targets = new HashSet<>();
        targets.add(new NatTarget(fixedIp.toInt(),
                                  fixedIp.toInt(), 0, 0));

        cond = new Condition();
        cond.nwDstIp = new IPv4Subnet(floatingIp, 32);

        pktCtx.wcmatch().setNetworkSrc(externalIp);
        pktCtx.wcmatch().setNetworkDst(floatingIp);

        Rule rule = new StaticForwardNatRule(cond, Action.CONTINUE,
                                             null, true, targets);
        RuleResult res = rule.process(pktCtx);

        Assert.assertEquals(Action.CONTINUE, res.action);
        checkMatched(true, pktCtx);
        checkApplied(true, pktCtx);

        Assert.assertEquals(externalIp,
                            pktCtx.wcmatch().getNetworkSrcIP());
        Assert.assertEquals(fixedIp,
                            pktCtx.wcmatch().getNetworkDstIP());
        Assert.assertEquals(pktCtx.diffBaseMatch().getDstPort(),
                            pktCtx.wcmatch().getDstPort());
        Assert.assertEquals(pktCtx.diffBaseMatch().getSrcPort(),
                            pktCtx.wcmatch().getSrcPort());
    }

    /**
     * Test static dnat, as used for floating IPs
     */
    @Test
    public void testStaticDnatAnyCondition() {
        IPv4Addr fixedIp = IPv4Addr.fromString("10.10.10.10");
        IPv4Addr floatingIp = IPv4Addr.fromString("1.1.1.1");
        IPv4Addr externalIp = IPv4Addr.fromString("2.2.2.2");

        Set<NatTarget> targets = new HashSet<>();
        targets.add(new NatTarget(fixedIp.toInt(),
                                  fixedIp.toInt(), 0, 0));

        cond = new Condition();
        cond.nwSrcIp = IPv4Subnet.fromCidr("0.0.0.0/0");

        pktCtx.wcmatch().setNetworkSrc(externalIp);
        pktCtx.wcmatch().setNetworkDst(floatingIp);

        Rule rule = new StaticForwardNatRule(cond, Action.CONTINUE,
                                             null, true, targets);
        RuleResult res = rule.process(pktCtx);

        Assert.assertEquals(Action.CONTINUE, res.action);
        checkMatched(true, pktCtx);
        checkApplied(true, pktCtx);

        Assert.assertEquals(externalIp,
                            pktCtx.wcmatch().getNetworkSrcIP());
        Assert.assertEquals(fixedIp,
                            pktCtx.wcmatch().getNetworkDstIP());
        Assert.assertEquals(pktCtx.diffBaseMatch().getDstPort(),
                            pktCtx.wcmatch().getDstPort());
        Assert.assertEquals(pktCtx.diffBaseMatch().getSrcPort(),
                            pktCtx.wcmatch().getSrcPort());
    }

    /**
     * Test that snat rules will also apply dnat to icmp data to undo the
     * dnat applied by corresponding dnat rules.
     */
    @Test
    public void testStaticSnatFixupIcmpData() throws Exception {
        IPv4Addr fixedIp = IPv4Addr.fromString("10.10.10.10");
        IPv4Addr floatingIp = IPv4Addr.fromString("1.1.1.1");
        IPv4Addr externalIp = IPv4Addr.fromString("2.2.2.2");

        Set<NatTarget> targets = new HashSet<>();
        targets.add(new NatTarget(floatingIp.toInt(), floatingIp.toInt(),
                                  0, 0));
        cond = new Condition();
        cond.nwSrcIp = new IPv4Subnet(fixedIp, 32);

        IPv4 data = new IPv4();
        data.setSourceAddress(externalIp);
        data.setDestinationAddress(fixedIp);

        pktCtx.wcmatch().setNetworkSrc(fixedIp);
        pktCtx.wcmatch().setNetworkDst(externalIp);
        pktCtx.wcmatch().setNetworkProto(ICMP.PROTOCOL_NUMBER);
        pktCtx.wcmatch().setSrcPort(ICMP.TYPE_PARAMETER_PROBLEM);
        pktCtx.wcmatch().setIcmpData(data.serialize());

        Rule rule = new StaticForwardNatRule(cond, Action.CONTINUE,
                                             null, false, targets);
        RuleResult res = rule.process(pktCtx);

        Assert.assertEquals(Action.CONTINUE, res.action);
        checkMatched(true, pktCtx);
        checkApplied(true, pktCtx);

        Assert.assertEquals(floatingIp,
                            pktCtx.wcmatch().getNetworkSrcIP());
        Assert.assertEquals(externalIp,
                            pktCtx.wcmatch().getNetworkDstIP());
        Assert.assertEquals(ICMP.TYPE_PARAMETER_PROBLEM,
                            pktCtx.wcmatch().getSrcPort());

        IPv4 data2 = new IPv4();
        data2.deserialize(ByteBuffer.wrap(pktCtx.wcmatch().getIcmpData()));
        Assert.assertEquals(data2.getSourceIPAddress(), externalIp);
        Assert.assertEquals(data2.getDestinationIPAddress(), floatingIp);
    }

    /**
     * Test that dnat rules will also apply snat to icmp data to undo the
     * snat applied by corresponding snat rules.
     */
    @Test
    public void testStaticDnatFixupIcmpData() throws Exception {
        IPv4Addr fixedIp = IPv4Addr.fromString("10.10.10.10");
        IPv4Addr floatingIp = IPv4Addr.fromString("1.1.1.1");
        IPv4Addr externalIp = IPv4Addr.fromString("2.2.2.2");

        Set<NatTarget> targets = new HashSet<>();
        targets.add(new NatTarget(fixedIp.toInt(), fixedIp.toInt(),
                                  0, 0));
        cond = new Condition();
        cond.nwDstIp = new IPv4Subnet(floatingIp, 32);

        IPv4 data = new IPv4();
        data.setSourceAddress(floatingIp);
        data.setDestinationAddress(externalIp);

        pktCtx.wcmatch().setNetworkSrc(externalIp);
        pktCtx.wcmatch().setNetworkDst(floatingIp);
        pktCtx.wcmatch().setNetworkProto(ICMP.PROTOCOL_NUMBER);
        pktCtx.wcmatch().setSrcPort(ICMP.TYPE_UNREACH);
        pktCtx.wcmatch().setIcmpData(data.serialize());

        Rule rule = new StaticForwardNatRule(cond, Action.CONTINUE,
                                             null, true, targets);
        RuleResult res = rule.process(pktCtx);

        Assert.assertEquals(Action.CONTINUE, res.action);
        checkMatched(true, pktCtx);
        checkApplied(true, pktCtx);

        Assert.assertEquals(externalIp,
                            pktCtx.wcmatch().getNetworkSrcIP());
        Assert.assertEquals(fixedIp,
                            pktCtx.wcmatch().getNetworkDstIP());
        Assert.assertEquals(ICMP.TYPE_UNREACH,
                            pktCtx.wcmatch().getSrcPort());

        IPv4 data2 = new IPv4();
        data2.deserialize(ByteBuffer.wrap(pktCtx.wcmatch().getIcmpData()));
        Assert.assertEquals(data2.getSourceIPAddress(), fixedIp);
        Assert.assertEquals(data2.getDestinationIPAddress(), externalIp);
    }

    /**
     * Test that a dnat rule, which matches on the destination address
     * contained in the icmp data, will apply correctly, undoing the dnat done
     * by another rule in the opposite direction.
     */
    @Test
    public void testStaticDnatFixupIcmpDataMatchingOnIcmpData() throws Exception {
        IPv4Addr fixedIp = IPv4Addr.fromString("10.10.10.10");
        IPv4Addr floatingIp = IPv4Addr.fromString("1.1.1.1");
        IPv4Addr externalIp = IPv4Addr.fromString("2.2.2.2");
        IPv4Addr errorRouterIp = IPv4Addr.fromString("20.20.20.1");

        Set<NatTarget> targets = new HashSet<>();
        targets.add(new NatTarget(floatingIp.toInt(),
                                  floatingIp.toInt(), 0, 0));

        cond = new Condition();
        cond.icmpDataDstIp = new IPv4Subnet(fixedIp, 32);

        IPv4 data = new IPv4();
        data.setSourceAddress(externalIp);
        data.setDestinationAddress(fixedIp);
        pktCtx.wcmatch().setNetworkProto(ICMP.PROTOCOL_NUMBER);
        pktCtx.wcmatch().setNetworkSrc(errorRouterIp);
        pktCtx.wcmatch().setNetworkDst(externalIp);
        pktCtx.wcmatch().setSrcPort(ICMP.TYPE_TIME_EXCEEDED);
        pktCtx.wcmatch().setIcmpData(data.serialize());

        Rule rule = new StaticForwardNatRule(cond, Action.CONTINUE,
                                             null, true, targets);
        RuleResult res = rule.process(pktCtx);

        Assert.assertEquals(Action.CONTINUE, res.action);
        checkMatched(true, pktCtx);
        checkApplied(true, pktCtx);

        Assert.assertEquals(errorRouterIp,
                            pktCtx.wcmatch().getNetworkSrcIP());
        Assert.assertEquals(externalIp,
                            pktCtx.wcmatch().getNetworkDstIP());
        Assert.assertEquals(pktCtx.diffBaseMatch().getDstPort(),
                            pktCtx.wcmatch().getDstPort());
        Assert.assertEquals(ICMP.TYPE_TIME_EXCEEDED,
                            pktCtx.wcmatch().getSrcPort());

        IPv4 data2 = new IPv4();
        data2.deserialize(ByteBuffer.wrap(pktCtx.wcmatch().getIcmpData()));
        Assert.assertEquals(data2.getSourceIPAddress(), externalIp);
        Assert.assertEquals(data2.getDestinationIPAddress(), floatingIp);
    }

    /**
     * Test that static snat works, as used for floating IPs
     */
    @Test
    public void testStaticSnat() {
        IPv4Addr fixedIp = IPv4Addr.fromString("10.10.10.10");
        IPv4Addr floatingIp = IPv4Addr.fromString("1.1.1.1");
        IPv4Addr externalIp = IPv4Addr.fromString("2.2.2.2");

        Set<NatTarget> targets = new HashSet<>();
        targets.add(new NatTarget(floatingIp.toInt(),
                                  floatingIp.toInt(), 0, 0));
        cond = new Condition();
        cond.nwSrcIp = new IPv4Subnet(fixedIp, 32);

        pktCtx.wcmatch().setNetworkSrc(fixedIp);
        pktCtx.wcmatch().setNetworkDst(externalIp);

        Rule rule = new StaticForwardNatRule(cond, Action.CONTINUE,
                                             null, false, targets);
        RuleResult res = rule.process(pktCtx);

        Assert.assertEquals(Action.CONTINUE, res.action);
        checkMatched(true, pktCtx);
        checkApplied(true, pktCtx);

        Assert.assertEquals(floatingIp,
                            pktCtx.wcmatch().getNetworkSrcIP());
        Assert.assertEquals(externalIp,
                            pktCtx.wcmatch().getNetworkDstIP());
        Assert.assertEquals(pktCtx.diffBaseMatch().getDstPort(),
                            pktCtx.wcmatch().getDstPort());
        Assert.assertEquals(pktCtx.diffBaseMatch().getSrcPort(),
                            pktCtx.wcmatch().getSrcPort());
    }

    @Test
    public void testStaticSnatAnyCondition() throws Exception {
        IPv4Addr fixedIp = IPv4Addr.fromString("10.10.10.10");
        IPv4Addr floatingIp = IPv4Addr.fromString("1.1.1.1");
        IPv4Addr externalIp = IPv4Addr.fromString("2.2.2.2");

        Set<NatTarget> targets = new HashSet<>();
        targets.add(new NatTarget(floatingIp.toInt(),
                                  floatingIp.toInt(), 0, 0));
        cond = new Condition();
        cond.nwDstIp = IPv4Subnet.fromCidr("0.0.0.0/0");

        pktCtx.wcmatch().setNetworkSrc(fixedIp);
        pktCtx.wcmatch().setNetworkDst(externalIp);

        Rule rule = new StaticForwardNatRule(cond, Action.CONTINUE,
                                             null, false, targets);
        RuleResult res = rule.process(pktCtx);

        Assert.assertEquals(Action.CONTINUE, res.action);
        checkMatched(true, pktCtx);
        checkApplied(true, pktCtx);

        Assert.assertEquals(floatingIp,
                            pktCtx.wcmatch().getNetworkSrcIP());
        Assert.assertEquals(externalIp,
                            pktCtx.wcmatch().getNetworkDstIP());
        Assert.assertEquals(pktCtx.diffBaseMatch().getDstPort(),
                            pktCtx.wcmatch().getDstPort());
        Assert.assertEquals(pktCtx.diffBaseMatch().getSrcPort(),
                            pktCtx.wcmatch().getSrcPort());
    }

}

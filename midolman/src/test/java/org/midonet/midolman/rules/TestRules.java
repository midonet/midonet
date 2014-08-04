/*
 * Copyright 2014 Midokura SARL
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

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import scala.Option;
import scala.util.Left;

import akka.actor.ActorSystem$;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.Guice;

import org.apache.zookeeper.CreateMode;

import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import org.slf4j.helpers.NOPLogger;
import com.typesafe.scalalogging.Logger$;

import org.midonet.midolman.guice.serialization.SerializationModule;
import org.midonet.midolman.rules.RuleResult.Action;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.simulation.PacketContext;
import org.midonet.midolman.state.ConnTrackState;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.HappyGoLuckyLeaser$;
import org.midonet.midolman.state.MockDirectory;
import org.midonet.midolman.state.NatState;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.ZkManager;
import org.midonet.midolman.state.zkManagers.FiltersZkManager;
import org.midonet.midolman.version.DataWriteVersion;
import org.midonet.midolman.version.guice.VersionModule;
import org.midonet.packets.IPv4;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;
import org.midonet.sdn.flows.WildcardMatch;
import org.midonet.sdn.state.FlowStateTable;
import org.midonet.sdn.state.FlowStateTransaction;
import org.midonet.sdn.state.ShardedFlowStateTable;
import org.midonet.util.Range;
import org.midonet.util.eventloop.MockReactor;
import org.midonet.util.eventloop.Reactor;

public class TestRules {

    static WildcardMatch pktMatch;
    static WildcardMatch pktResponseMatch;
    static Random rand;
    static UUID inPort;
    static UUID ownerId;
    static UUID jumpChainId;
    static String jumpChainName;
    static Condition cond;
    static Set<NatTarget> nats;
    PacketContext pktCtx;
    FlowStateTransaction<ConnTrackState.ConnTrackKey, Boolean> conntrackTx;
    FlowStateTransaction<NatState.NatKey, NatState.NatBinding> natTx;

    @BeforeClass
    public static void setupOnce() {
        pktMatch = new WildcardMatch();
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
        pktResponseMatch = new WildcardMatch();
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

        Guice.createInjector(
            new TestModule("/midonet"),
            new VersionModule(),
            new SerializationModule()
        );
    }

    public static class TestModule extends AbstractModule {

        private final String basePath;

        public TestModule(String basePath) {
            this.basePath = basePath;
        }

        @Override
        protected void configure() {
            bind(Reactor.class).toInstance(new MockReactor());
            bind(PathBuilder.class).toInstance(new PathBuilder(basePath));
        }

        @Provides @Singleton
        public Directory provideDirectory(PathBuilder paths) {
            Directory directory = new MockDirectory();
            try {
                directory.add(paths.getBasePath(), null, CreateMode.PERSISTENT);
                directory.add(paths.getWriteVersionPath(),
                        DataWriteVersion.CURRENT.getBytes(),
                        CreateMode.PERSISTENT);
                directory.add(paths.getFiltersPath(), null,
                        CreateMode.PERSISTENT);
            } catch (Exception ex) {
                throw new RuntimeException("Could not initialize zk", ex);
            }
            return directory;
        }

        @Provides @Singleton
        public ZkManager provideZkManager(Directory directory) {
            return new ZkManager(directory, basePath);
        }

        @Provides @Singleton
        public FiltersZkManager provideFiltersZkManager(ZkManager zkManager,
                                                        PathBuilder paths,
                                                        Serializer serializer) {
            FiltersZkManager zk = new FiltersZkManager(zkManager, paths,
                    serializer);
            try {
                zk.create(ownerId);
            } catch (Exception e) {
                throw new RuntimeException(
                        "Could not initialize FiltersZkManager", e);
            }
            return zk;
        }
    }

    @Before
    public void setup() {
        pktCtx = new PacketContext(new Left<Object, UUID>(1),
                                   null, Option.empty(), pktMatch, ActorSystem$.MODULE$.create());
        @SuppressWarnings("unchecked")
        ShardedFlowStateTable<ConnTrackState.ConnTrackKey, Boolean> shardedConntrack =
                ShardedFlowStateTable.create();
        pktCtx = new PacketContext(new Left<Object, UUID>(1),
                null, Option.empty(), pktMatch, ActorSystem$.MODULE$.create());
        FlowStateTable<ConnTrackState.ConnTrackKey, Boolean> conntrackTable =
                shardedConntrack.addShard(Logger$.MODULE$.apply(NOPLogger.NOP_LOGGER));
        ShardedFlowStateTable<NatState.NatKey, NatState.NatBinding> shardedNat =
                ShardedFlowStateTable.create();
        FlowStateTable<NatState.NatKey, NatState.NatBinding> natTable =
                shardedNat.addShard(Logger$.MODULE$.apply(NOPLogger.NOP_LOGGER));
        conntrackTx = new FlowStateTransaction<>(conntrackTable);
        natTx = new FlowStateTransaction<>(natTable);
        pktCtx.state().initialize(conntrackTx, natTx, HappyGoLuckyLeaser$.MODULE$);
    }

    @Test
    public void testLiteralRuleAccept() {
        Rule rule = new LiteralRule(cond, Action.ACCEPT);
        // If the condition doesn't match the result is not modified.
        RuleResult res = new RuleResult(null, null);
        rule.process(pktCtx, res, ownerId, false);
        Assert.assertEquals(null, res.action);
        pktCtx.inPortId_$eq(inPort);
        rule.process(pktCtx, res, ownerId, false);
        Assert.assertEquals(Action.ACCEPT, res.action);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testLiteralRuleContinue() {
        new LiteralRule(cond, Action.CONTINUE);
    }

    @Test
    public void testLiteralRuleDrop() {
        Rule rule = new LiteralRule(cond, Action.DROP);
        // If the condition doesn't match the result is not modified.
        RuleResult res = new RuleResult(null, null);
        rule.process(pktCtx, res, ownerId, false);
        Assert.assertEquals(null, res.action);
        pktCtx.inPortId_$eq(inPort);
        rule.process(pktCtx, res, ownerId, false);
        Assert.assertEquals(Action.DROP, res.action);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testLiteralRuleJump() {
        new LiteralRule(cond, Action.JUMP);
    }

    @Test
    public void testLiteralRuleReject() {
        Rule rule = new LiteralRule(cond, Action.REJECT);
        // If the condition doesn't match the result is not modified.
        RuleResult res = new RuleResult(null, null);
        rule.process(pktCtx, res, ownerId, false);
        Assert.assertEquals(null, res.action);
        pktCtx.inPortId_$eq(inPort);
        rule.process(pktCtx, res, ownerId, false);
        Assert.assertEquals(Action.REJECT, res.action);
    }

    @Test
    public void testLiteralRuleReturn() {
        Rule rule = new LiteralRule(cond, Action.RETURN);
        // If the condition doesn't match the result is not modified.
        RuleResult res = new RuleResult(null, null);
        rule.process(pktCtx, res, ownerId, false);
        Assert.assertEquals(null, res.action);
        pktCtx.inPortId_$eq(inPort);
        rule.process(pktCtx, res, ownerId, false);
        Assert.assertEquals(Action.RETURN, res.action);
    }

    @Test
    public void testJumpRule() {
        Rule rule = new JumpRule(cond, jumpChainId, jumpChainName);
        // If the condition doesn't match the result is not modified.
        RuleResult res = new RuleResult(null, null);
        rule.process(pktCtx, res, ownerId, false);
        Assert.assertEquals(null, res.action);
        pktCtx.inPortId_$eq(inPort);
        rule.process(pktCtx, res, ownerId, false);
        Assert.assertEquals(Action.JUMP, res.action);
        Assert.assertEquals(jumpChainId, res.jumpToChain);
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
        Set<NatTarget> nats = new HashSet<>();
        nats.add(new NatTarget(0x0b000102, 0x0b00010a, 3366, 3399));
        Rule rule = new ForwardNatRule(cond, Action.ACCEPT, null, 0, false,
                nats);
        // If the condition doesn't match the result is not modified.
        RuleResult res = new RuleResult(null, null);
        rule.process(pktCtx, res, ownerId, false);
        natTx.commit();
        Assert.assertEquals(null, res.action);
        // We let the reverse snat rule try reversing everything.
        Rule revRule = new ReverseNatRule(new Condition(), Action.RETURN, false);
        // If the condition doesn't match the result is not modified.
        revRule.process(pktCtx, res, ownerId, false);
        Assert.assertEquals(null, res.action);
        // Now get the Snat rule to match.
        pktCtx.inPortId_$eq(inPort);
        rule.process(pktCtx, res, ownerId, false);
        Assert.assertEquals(Action.ACCEPT, res.action);
        IPv4Addr newNwSrc = (IPv4Addr)(pktCtx.wcmatch().getNetworkSourceIP());
        Assert.assertTrue(0x0b000102 <= newNwSrc.toInt());
        Assert.assertTrue(newNwSrc.toInt() <= 0x0b00010a);
        int newTpSrc = pktCtx.wcmatch().getSrcPort();
        Assert.assertTrue(3366 <= newTpSrc);
        Assert.assertTrue(newTpSrc <= 3399);
        // Now verify that the rest of the packet hasn't changed.
        WildcardMatch expected = pktCtx.origMatch().clone();
        expected.setNetworkSrc(newNwSrc);
        expected.setSrcPort(newTpSrc);
        Assert.assertEquals(expected, pktCtx.wcmatch());
        // Verify we get the same mapping if we re-process the original match.
        res = new RuleResult(null, null);
        pktCtx.wcmatch().reset(pktCtx.origMatch());
        rule.process(pktCtx, res, ownerId, false);
        Assert.assertEquals(expected, pktCtx.wcmatch());
        // Now use the new ip/port in the return packet.
        pktCtx.wcmatch().reset(pktResponseMatch);
        Assert.assertNotSame(pktResponseMatch.getNetworkDestinationIP(),
                             newNwSrc);
        pktCtx.wcmatch().setNetworkDst(newNwSrc);
        Assert.assertNotSame(pktResponseMatch.getDstPort(), newTpSrc);
        pktCtx.wcmatch().setDstPort(newTpSrc);
        res = new RuleResult(null, null);
        revRule.process(pktCtx, res, ownerId, false);
        Assert.assertEquals(Action.RETURN, res.action);
        // The generated response should be the mirror of the original.
        Assert.assertEquals(pktResponseMatch, pktCtx.wcmatch());
    }

    @Test
    public void testDnatAndReverseRule() {
        Set<NatTarget> nats = new HashSet<>();
        nats.add(new NatTarget(0x0c000102, 0x0c00010a, 1030, 1050));
        Rule rule = new ForwardNatRule(cond, Action.CONTINUE, null, 0, true,
                nats);
        // If the condition doesn't match the result is not modified.
        RuleResult res = new RuleResult(null, null);
        rule.process(pktCtx, res, ownerId, false);
        Assert.assertEquals(res.action, null);
        Assert.assertEquals(pktCtx.origMatch(), pktCtx.wcmatch());
        // We let the reverse dnat rule try reversing everything.
        Rule revRule = new ReverseNatRule(new Condition(), Action.ACCEPT, true);
        // If the condition doesn't match the result is not modified.
        Assert.assertEquals(res.action, null);
        Assert.assertEquals(pktCtx.origMatch(), pktCtx.wcmatch());
        // Now get the Dnat rule to match.
        pktCtx.inPortId_$eq(inPort);
        rule.process(pktCtx, res, ownerId, false);
        Assert.assertEquals(Action.CONTINUE, res.action);
        int newNwDst = ((IPv4Addr) pktCtx.wcmatch().getNetworkDestinationIP()).toInt();
        Assert.assertTrue(0x0c000102 <= newNwDst);
        Assert.assertTrue(newNwDst <= 0x0c00010a);
        int newTpDst = pktCtx.wcmatch().getDstPort();
        Assert.assertTrue(1030 <= newTpDst);
        Assert.assertTrue(newTpDst <= 1050);
        // Now verify that the rest of the packet hasn't changed.
        WildcardMatch expected = pktCtx.origMatch().clone();
        expected.setNetworkDst(IPv4Addr.fromInt(newNwDst));
        expected.setDstPort(newTpDst);
        Assert.assertEquals(pktCtx.wcmatch(), expected);
        // Verify we get the same mapping if we re-process the original match.
        res = new RuleResult(null, null);
        pktCtx.wcmatch().reset(pktCtx.origMatch());
        rule.process(pktCtx, res, ownerId, false);
        Assert.assertEquals(pktCtx.wcmatch(), expected);
        // Now use the new ip/port in the return packet.
        pktCtx.wcmatch().reset(pktResponseMatch.clone());
        Assert.assertTrue(IPv4Addr.fromInt(newNwDst).canEqual(
                          pktResponseMatch.getNetworkSourceIP()));
        Assert.assertFalse(IPv4Addr.fromInt(newNwDst).equals(
                           pktResponseMatch.getNetworkSourceIP()));

        pktCtx.wcmatch().setNetworkSrc(IPv4Addr.fromInt(newNwDst));
        Assert.assertNotSame(pktResponseMatch.getSrcPort(), newTpDst);
        pktCtx.wcmatch().setSrcPort(newTpDst);
        res.action = null;
        pktCtx.inPortId_$eq(null);
        revRule.process(pktCtx, res, ownerId, false);
        Assert.assertEquals(Action.ACCEPT, res.action);

        // The generated response should be the mirror of the original.
        Assert.assertEquals(pktResponseMatch, pktCtx.wcmatch());
    }

    @Test
    public void testDnatAndReverseRuleDeleteMapping() {
        Set<NatTarget> nats = new HashSet<NatTarget>();
        nats.add(new NatTarget(0x0c000102, 0x0c00010a, 1030, 1050));
        Rule rule = new ForwardNatRule(cond, Action.CONTINUE, null, 0, true,
                nats);

        // Now get the Dnat rule to match.
        pktCtx.inPortId_$eq(inPort);
        RuleResult res = new RuleResult(null, null);
        rule.process(pktCtx, res, ownerId, false);
        Assert.assertEquals(Action.CONTINUE, res.action);
        int firstNwDst = ((IPv4Addr) pktCtx.wcmatch().getNetworkDestinationIP()).toInt();
        Assert.assertTrue(0x0c000102 <= firstNwDst);
        Assert.assertTrue(firstNwDst <= 0x0c00010a);
        int firstTpDst = pktCtx.wcmatch().getDstPort();
        Assert.assertTrue(1030 <= firstTpDst);
        Assert.assertTrue(firstTpDst <= 1050);

        // Now verify that the rest of the packet hasn't changed.
        WildcardMatch expected = pktCtx.origMatch().clone();
        expected.setNetworkDst(IPv4Addr.fromInt(firstNwDst));
        expected.setDstPort(firstTpDst);
        Assert.assertEquals(pktCtx.wcmatch(), expected);

        // Verify we get the same mapping if we re-process the original match.
        res = new RuleResult(null, null);
        pktCtx.wcmatch().reset(pktCtx.origMatch());
        rule.process(pktCtx, res, ownerId, false);
        int secondNwDst = ((IPv4Addr) pktCtx.wcmatch().getNetworkDestinationIP()).toInt();
        int secondTpDst = pktCtx.wcmatch().getDstPort();
        Assert.assertEquals(expected, pktCtx.wcmatch());
        Assert.assertEquals(Action.CONTINUE, res.action);
        Assert.assertEquals(firstNwDst, secondNwDst);
        Assert.assertEquals(firstTpDst, secondTpDst);

        // Delete the DNAT entry
        natTx.flush();

        // Verify we get a NEW mapping if we re-process the original match.
        res = new RuleResult(null, null);
        pktCtx.wcmatch().reset(pktCtx.origMatch());
        rule.process(pktCtx, res, ownerId, false);
        int thirdNwDst = ((IPv4Addr) pktCtx.wcmatch().getNetworkDestinationIP()).toInt();
        int thirdTpDst = pktCtx.wcmatch().getDstPort();
        Assert.assertNotEquals(expected, pktCtx.wcmatch());
        Assert.assertNotSame(firstNwDst, thirdNwDst);
        Assert.assertNotSame(firstTpDst, thirdTpDst);
    }
}

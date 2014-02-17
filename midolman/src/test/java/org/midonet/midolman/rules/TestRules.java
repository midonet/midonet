/*
 * Copyright 2011 Midokura KK
 */

package org.midonet.midolman.rules;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.Injector;
import com.google.inject.Guice;
import org.apache.zookeeper.CreateMode;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.midonet.cache.Cache;
import org.midonet.cache.MockCache;
import org.midonet.midolman.guice.serialization.SerializationModule;
import org.midonet.midolman.layer4.NatLeaseManager;
import org.midonet.midolman.layer4.NatMapping;
import org.midonet.midolman.rules.RuleResult.Action;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.MockDirectory;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.ZkManager;
import org.midonet.midolman.state.zkManagers.FiltersZkManager;
import org.midonet.midolman.version.DataWriteVersion;
import org.midonet.midolman.version.guice.VersionModule;
import org.midonet.midolman.vrn.ForwardInfo;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;
import org.midonet.sdn.flows.WildcardMatch;
import org.midonet.util.Range;
import org.midonet.util.eventloop.MockReactor;
import org.midonet.util.eventloop.Reactor;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;


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
    static NatMapping natMapping;
    RuleResult expRes, argRes;
    ForwardInfo fwdInfo;
    private static Injector injector;

    @BeforeClass
    public static void setupOnce() {
        pktMatch = new WildcardMatch();
        pktMatch.setInputPort((short) 5);
        pktMatch.setDataLayerSource("02:11:33:00:11:01");
        pktMatch.setDataLayerDestination("02:11:aa:ee:22:05");
        pktMatch.setNetworkSource(IPv4Addr.fromInt(0x0a001406));
        pktMatch.setNetworkDestination(IPv4Addr.fromInt(0x0a000b22));
        pktMatch.setNetworkProtocol((byte) 6); // TCP
        pktMatch.setNetworkTypeOfService((byte) 34);
        pktMatch.setTransportSource(4321);
        pktMatch.setTransportDestination(1234);
        pktResponseMatch = new WildcardMatch();
        pktResponseMatch.setInputPort((short) 5);
        pktResponseMatch.setDataLayerDestination("02:11:33:00:11:01");
        pktResponseMatch.setDataLayerSource("02:11:aa:ee:22:05");
        pktResponseMatch.setNetworkDestination(IPv4Addr.fromInt(0x0a001406));
        pktResponseMatch.setNetworkSource(IPv4Addr.fromInt(0x0a000b22));
        pktResponseMatch.setNetworkProtocol((byte) 6); // TCP
        pktResponseMatch.setNetworkTypeOfService((byte) 34);
        pktResponseMatch.setTransportDestination(4321);
        pktResponseMatch.setTransportSource(1234);
        rand = new Random();
        inPort = new UUID(rand.nextLong(), rand.nextLong());
        ownerId = new UUID(rand.nextLong(), rand.nextLong());
        jumpChainId = new UUID(rand.nextLong(), rand.nextLong());
        jumpChainName = "AJumpChainName";
        // Build a condition that matches the packet.
        cond = new Condition();
        cond.inPortIds = new HashSet<UUID>();
        cond.inPortIds.add(inPort);
        cond.nwSrcIp = new IPv4Subnet(IPv4Addr.fromString("10.0.20.0"), 24);
        cond.nwProto = 15;
        cond.nwProtoInv = true;
        cond.tpSrc = new Range<Integer>(2000, 3000);
        cond.tpSrcInv = true;
        cond.tpDst = new Range<Integer>(1000, 2000);

        nats = new HashSet<NatTarget>();
        nats.add(new NatTarget(0x0a090807, 0x0a090810, 21333,
                32999));

        injector = Guice.createInjector(
                new TestModule("/midonet"),
                new VersionModule(),
                new SerializationModule()
        );

        natMapping = injector.getInstance(NatLeaseManager.class);
    }

    public static class TestModule extends AbstractModule {

        private final String basePath;

        public TestModule(String basePath) {
            this.basePath = basePath;
        }

        @Override
        protected void configure() {
            bind(Cache.class).toInstance(new MockCache());
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

        @Provides @Singleton
        public NatLeaseManager provideNatLeaseManager(
                FiltersZkManager zk, Cache cache, Reactor reactor) {
                return new NatLeaseManager(zk, ownerId, cache, reactor);
        }

    }

    @Before
    public void setup() {

        expRes = new RuleResult(null, null, pktMatch.clone());
        argRes = new RuleResult(null, null, pktMatch.clone());
        fwdInfo = new ForwardInfo(false, null, null);
        fwdInfo.flowMatch = new WildcardMatch();
    }

    @Test
    public void testLiteralRuleAccept() {
        Rule rule = new LiteralRule(cond, Action.ACCEPT);
        // If the condition doesn't match the result is not modified.
        rule.process(fwdInfo, argRes, null, false);
        Assert.assertTrue(expRes.equals(argRes));
        fwdInfo.inPortId = inPort;
        rule.process(fwdInfo, argRes, null, false);
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
        rule.process(fwdInfo, argRes, null, false);
        Assert.assertTrue(expRes.equals(argRes));
        fwdInfo.inPortId = inPort;
        rule.process(fwdInfo, argRes, null, false);
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
        rule.process(fwdInfo, argRes, null, false);
        Assert.assertTrue(expRes.equals(argRes));
        fwdInfo.inPortId = inPort;
        rule.process(fwdInfo, argRes, null, false);
        expRes.action = Action.REJECT;
        Assert.assertTrue(expRes.equals(argRes));
    }

    @Test
    public void testLiteralRuleReturn() {
        Rule rule = new LiteralRule(cond, Action.RETURN);
        // If the condition doesn't match the result is not modified.
        rule.process(fwdInfo, argRes, null, false);
        Assert.assertTrue(expRes.equals(argRes));
        fwdInfo.inPortId = inPort;
        rule.process(fwdInfo, argRes, null, false);
        expRes.action = Action.RETURN;
        Assert.assertTrue(expRes.equals(argRes));
    }

    @Test
    public void testJumpRule() {
        Rule rule = new JumpRule(cond, jumpChainId, jumpChainName);
        // If the condition doesn't match the result is not modified.
        rule.process(fwdInfo, argRes, null, false);
        Assert.assertTrue(expRes.equals(argRes));
        fwdInfo.inPortId = inPort;
        rule.process(fwdInfo, argRes, null, false);
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
        nats.add(new NatTarget(0x0b000102, 0x0b00010a, 3366, 3399));
        Rule rule = new ForwardNatRule(cond, Action.ACCEPT, null, 0, false,
                nats);
        // If the condition doesn't match the result is not modified.
        rule.process(fwdInfo, argRes, natMapping, false);
        Assert.assertTrue(expRes.equals(argRes));
        // We let the reverse snat rule try reversing everything.
        Rule revRule = new ReverseNatRule(new Condition(), Action.RETURN, false);
        // If the condition doesn't match the result is not modified.
        revRule.process(fwdInfo, argRes, natMapping, false);
        Assert.assertTrue(expRes.equals(argRes));
        // Now get the Snat rule to match.
        fwdInfo.inPortId = inPort;
        rule.process(fwdInfo, argRes, natMapping, false);
        Assert.assertEquals(Action.ACCEPT, argRes.action);
        IPv4Addr newNwSrc = (IPv4Addr)(argRes.pmatch.getNetworkSourceIP());
        Assert.assertTrue(0x0b000102 <= newNwSrc.toInt());
        Assert.assertTrue(newNwSrc.toInt() <= 0x0b00010a);
        int newTpSrc = argRes.pmatch.getTransportSource();
        Assert.assertTrue(3366 <= newTpSrc);
        Assert.assertTrue(newTpSrc <= 3399);
        // Now verify that the rest of the packet hasn't changed.
        expRes.pmatch.setNetworkSource(newNwSrc);
        expRes.pmatch.setTransportSource(newTpSrc);
        Assert.assertTrue(expRes.pmatch.equals(argRes.pmatch));
        // Verify we get the same mapping if we re-process the original match.
        expRes = argRes;
        argRes = new RuleResult(null, null, pktMatch.clone());
        rule.process(fwdInfo, argRes, natMapping, false);
        Assert.assertEquals(expRes, argRes);
        // Now use the new ip/port in the return packet.
        argRes.pmatch = pktResponseMatch.clone();
        Assert.assertFalse(pktResponseMatch.getNetworkDestinationIP().equals(
                newNwSrc));
        argRes.pmatch.setNetworkDestination(newNwSrc);
        Assert.assertFalse(pktResponseMatch.getTransportDestination() == newTpSrc);
        argRes.pmatch.setTransportDestination(newTpSrc);
        argRes.action = null;
        revRule.process(fwdInfo, argRes, natMapping, false);
        Assert.assertEquals(Action.RETURN, argRes.action);
        // The generated response should be the mirror of the original.
        Assert.assertTrue(pktResponseMatch.equals(argRes.pmatch));
    }

    @Test
    public void testDnatAndReverseRule() {
        Set<NatTarget> nats = new HashSet<NatTarget>();
        nats.add(new NatTarget(0x0c000102, 0x0c00010a, (int) 1030,
                (int) 1050));
        Rule rule = new ForwardNatRule(cond, Action.CONTINUE, null, 0, true,
                nats);
        // If the condition doesn't match the result is not modified.
        rule.process(fwdInfo, argRes, natMapping, false);
        Assert.assertEquals(expRes, argRes);
        // We let the reverse dnat rule try reversing everything.
        Rule revRule = new ReverseNatRule(new Condition(), Action.ACCEPT, true);
        // If the condition doesn't match the result is not modified.
        revRule.process(fwdInfo, argRes, natMapping, false);
        Assert.assertTrue(expRes.equals(argRes));
        // Now get the Dnat rule to match.
        fwdInfo.inPortId = inPort;
        rule.process(fwdInfo, argRes, natMapping, false);
        Assert.assertEquals(Action.CONTINUE, argRes.action);
        int newNwDst = ((IPv4Addr)argRes.pmatch.getNetworkDestinationIP()).toInt();
        Assert.assertTrue(0x0c000102 <= newNwDst);
        Assert.assertTrue(newNwDst <= 0x0c00010a);
        int newTpDst = argRes.pmatch.getTransportDestination();
        Assert.assertTrue(1030 <= newTpDst);
        Assert.assertTrue(newTpDst <= 1050);
        // Now verify that the rest of the packet hasn't changed.
        expRes.pmatch.setNetworkDestination(IPv4Addr.fromInt(newNwDst));
        expRes.pmatch.setTransportDestination(newTpDst);
        Assert.assertTrue(expRes.pmatch.equals(argRes.pmatch));
        // Verify we get the same mapping if we re-process the original match.
        expRes = argRes;
        argRes = new RuleResult(null, null, pktMatch.clone());
        rule.process(fwdInfo, argRes, natMapping, false);
        Assert.assertTrue(expRes.equals(argRes));
        // Now use the new ip/port in the return packet.
        argRes.pmatch = pktResponseMatch.clone();
        Assert.assertTrue(IPv4Addr.fromInt(newNwDst).canEqual(
                          pktResponseMatch.getNetworkSourceIP()));
        Assert.assertFalse(IPv4Addr.fromInt(newNwDst).equals(
                           pktResponseMatch.getNetworkSourceIP()));
        argRes.pmatch.setNetworkSource(IPv4Addr.fromInt(newNwDst));
        Assert.assertFalse(pktResponseMatch.getTransportSource() == newTpDst);
        argRes.pmatch.setTransportSource(newTpDst);
        argRes.action = null;
        fwdInfo.inPortId = null;
        revRule.process(fwdInfo, argRes, natMapping, false);
        Assert.assertEquals(Action.ACCEPT, argRes.action);
        // The generated response should be the mirror of the original.
        Assert.assertTrue(pktResponseMatch.equals(argRes.pmatch));
    }

}

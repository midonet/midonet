/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.rules;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.Vector;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import com.midokura.midolman.layer4.MockNatMapping;
import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.rules.RuleResult.Action;
import com.midokura.midolman.state.ChainZkManager;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.MockDirectory;
import com.midokura.midolman.state.RouterZkManager;
import com.midokura.midolman.state.RuleIndexOutOfBoundsException;
import com.midokura.midolman.state.RuleZkManager;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkPathManager;
import com.midokura.midolman.state.ZkStateSerializationException;
import com.midokura.midolman.state.ChainZkManager.ChainConfig;

public class TestRuleEngine {

    /* TODO(abel): fix this.
    static Random rand;
    static UUID rtrId;
    static MidoMatch pktMatch;
    static UUID inPort;
    static Condition cond;

    ChainZkManager chainMgr;
    RuleZkManager ruleMgr;
    RuleEngine engine;
    MockNatMapping natMap;
    List<Rule> chain1;
    List<Rule> chain2;
    List<Rule> chain3;
    UUID c1Id;
    UUID c2Id;
    UUID c3Id;
    UUID delRule1;
    UUID delRule2;
    UUID delRule3;

    private static class MyLiteralRule extends LiteralRule {
        private static final long serialVersionUID = 0L;
        int timesApplied;

        public MyLiteralRule(Condition condition, Action action, UUID chainId,
                int position) {
            super(condition, action, chainId, position);
            timesApplied = 0;
        }

        // Default constructor for the JSON serialization.
        public MyLiteralRule() {
            super();
        }

        @Override
        public void apply(MidoMatch flowMatch, UUID inPortId, UUID outPortId,
                RuleResult res) {
            timesApplied++;
            super.apply(flowMatch, inPortId, outPortId, res);
        }
    }

    private static class MyRevSnatRule extends ReverseNatRule {
        private static final long serialVersionUID = 0L;
        int timesApplied;

        public MyRevSnatRule(Condition condition, Action action, UUID chainId,
                int position) {
            super(condition, action, chainId, position, false);
            timesApplied = 0;
        }

        // Default constructor for the JSON serialization.
        public MyRevSnatRule() {
            super();
        }

        @Override
        public void apply(MidoMatch flowMatch, UUID inPortId, UUID outPortId,
                RuleResult res) {
            timesApplied++;
            super.apply(flowMatch, inPortId, outPortId, res);
        }
    }

    @BeforeClass
    public static void setupOnce() throws Exception {
        rand = new Random();
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
        // Build a condition that matches the packet.
        inPort = new UUID(rand.nextLong(), rand.nextLong());
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
    public void setup() throws StateAccessException,
            ZkStateSerializationException, KeeperException,
            InterruptedException, RuleIndexOutOfBoundsException {
        String basePath = "/midolman";
        ZkPathManager pathMgr = new ZkPathManager(basePath);
        Directory dir = new MockDirectory();
        dir.add(pathMgr.getBasePath(), null, CreateMode.PERSISTENT);
        dir.add(pathMgr.getRoutersPath(), null, CreateMode.PERSISTENT);
        // Add the paths for rules and chains
        dir.add(pathMgr.getChainsPath(), null, CreateMode.PERSISTENT);
        dir.add(pathMgr.getRulesPath(), null, CreateMode.PERSISTENT);
        RouterZkManager routerMgr = new RouterZkManager(dir, basePath);
        chainMgr = new ChainZkManager(dir, basePath);
        ruleMgr = new RuleZkManager(dir, basePath);

        // TODO(pino): is a random router ID ok here?
        rtrId = routerMgr.create();
        natMap = new MockNatMapping();
        engine = new RuleEngine(dir, basePath, rtrId, natMap);
        c1Id = chainMgr.create(new ChainConfig("Chain1", rtrId));
        c2Id = chainMgr.create(new ChainConfig("Chain2", rtrId));
        c3Id = chainMgr.create(new ChainConfig("Chain3", rtrId));
        chain1 = new Vector<Rule>();
        chain2 = new Vector<Rule>();
        chain3 = new Vector<Rule>();
        Rule r = new MyRevSnatRule(new Condition(), Action.CONTINUE, c1Id, 1);
        // Remember the id's of the first 2 rules of this chain for use in
        // subsequent unit tests.
        delRule1 = ruleMgr.create(r);
        chain1.add(r);
        Condition cond = new Condition();
        cond.conjunctionInv = true;
        // This rule should never be applied.
        r = new MyLiteralRule(cond, Action.DROP, c1Id, 2);
        delRule2 = ruleMgr.create(r);
        chain1.add(r);
        r = new JumpRule(new Condition(), "Chain2", c1Id, 3);
        ruleMgr.create(r);
        chain1.add(r);
        r = new MyLiteralRule(new Condition(), Action.ACCEPT, c1Id, 4);
        ruleMgr.create(r);
        chain1.add(r);
        // This rule should never be applied.
        r = new MyLiteralRule(new Condition(), Action.REJECT, c1Id, 5);
        ruleMgr.create(r);
        chain1.add(r);

        r = new MyRevSnatRule(new Condition(), Action.CONTINUE, c2Id, 1);
        // Remember the id of the next rule for use in the tests.
        delRule3 = ruleMgr.create(r);
        chain2.add(r);
        r = new JumpRule(new Condition(), "Chain3", c2Id, 2);
        ruleMgr.create(r);
        chain2.add(r);
        r = new MyRevSnatRule(new Condition(), Action.CONTINUE, c2Id, 3);
        ruleMgr.create(r);
        chain2.add(r);
        r = new MyLiteralRule(new Condition(), Action.RETURN, c2Id, 4);
        ruleMgr.create(r);
        chain2.add(r);

        r = new MyRevSnatRule(new Condition(), Action.CONTINUE, c3Id, 1);
        ruleMgr.create(r);
        chain3.add(r);
        r = new MyRevSnatRule(new Condition(), Action.CONTINUE, c3Id, 2);
        ruleMgr.create(r);
        chain3.add(r);
        r = new MyLiteralRule(new Condition(), Action.RETURN, c3Id, 3);
        ruleMgr.create(r);
        chain3.add(r);

        Assert.assertEquals(3, engine.ruleChains.size());
        Assert.assertEquals("Chain1", engine.chainIdToName.get(c1Id));
        Assert.assertEquals("Chain2", engine.chainIdToName.get(c2Id));
        Assert.assertEquals("Chain3", engine.chainIdToName.get(c3Id));
        Assert.assertTrue(engine.ruleChains.containsKey(c1Id));
        Assert.assertEquals(chain1.size(), engine.ruleChains.get(c1Id).size());
        Assert.assertTrue(chain1.equals(engine.ruleChains.get(c1Id)));
        Assert.assertTrue(engine.ruleChains.containsKey(c2Id));
        Assert.assertEquals(chain2.size(), engine.ruleChains.get(c2Id).size());
        Assert.assertTrue(chain2.equals(engine.ruleChains.get(c2Id)));
        Assert.assertTrue(engine.ruleChains.containsKey(c3Id));
        Assert.assertEquals(chain3.size(), engine.ruleChains.get(c3Id).size());
        Assert.assertTrue(chain3.equals(engine.ruleChains.get(c3Id)));
    }

    @Test
    public void testLiteralRuleChains() throws IOException, KeeperException,
            InterruptedException {
        // Get pointers to engine's chains since those instances are invoked.
        List<Rule> c1 = engine.ruleChains.get(c1Id);
        List<Rule> c2 = engine.ruleChains.get(c2Id);
        List<Rule> c3 = engine.ruleChains.get(c3Id);
        MidoMatch emptyPacket = new MidoMatch();
        RuleResult res = engine.applyChain("Chain1",
                new MidoMatch(), emptyPacket, null, null);
        Assert.assertTrue(Action.ACCEPT.equals(res.action));
        Assert.assertNull(res.jumpToChain);
        Assert.assertEquals(emptyPacket, res.match);
        Assert.assertTrue(emptyPacket.equals(new MidoMatch()));
        Assert.assertFalse(res.trackConnection);
        MyRevSnatRule rRev = (MyRevSnatRule) c1.get(0);
        Assert.assertEquals(1, rRev.timesApplied);
        MyLiteralRule rLit = (MyLiteralRule) c1.get(1);
        Assert.assertEquals(0, rLit.timesApplied);
        rLit = (MyLiteralRule) c1.get(3);
        Assert.assertEquals(1, rLit.timesApplied);
        rLit = (MyLiteralRule) c1.get(4);
        Assert.assertEquals(0, rLit.timesApplied);
        rRev = (MyRevSnatRule) c2.get(0);
        Assert.assertEquals(1, rRev.timesApplied);
        rRev = (MyRevSnatRule) c2.get(2);
        Assert.assertEquals(1, rRev.timesApplied);
        rLit = (MyLiteralRule) c2.get(3);
        Assert.assertEquals(1, rLit.timesApplied);
        rRev = (MyRevSnatRule) c3.get(0);
        Assert.assertEquals(1, rRev.timesApplied);
        rRev = (MyRevSnatRule) c3.get(1);
        Assert.assertEquals(1, rRev.timesApplied);
        rLit = (MyLiteralRule) c3.get(2);
        Assert.assertEquals(1, rLit.timesApplied);
    }

    @Test
    public void testRuleChainUpdates() throws StateAccessException,
            ZkStateSerializationException {
        // Delete the first 2 rules from chain1 and the first rule from chain2.
        chain1.remove(0);
        ruleMgr.delete(delRule1);
        chain1.remove(0);
        ruleMgr.delete(delRule2);
        chain2.remove(0);
        ruleMgr.delete(delRule3);
        // Delete chain3 completely.
        chainMgr.delete(c3Id);

        Assert.assertEquals(2, engine.ruleChains.size());
        Assert.assertEquals("Chain1", engine.chainIdToName.get(c1Id));
        Assert.assertEquals("Chain2", engine.chainIdToName.get(c2Id));
        Assert.assertFalse(engine.ruleChains.containsKey(c3Id));
        Assert.assertFalse(engine.chainIdToName.containsKey(c3Id));
        Assert.assertFalse(engine.chainNameToUUID.containsKey("Chain3"));

        Assert.assertTrue(engine.ruleChains.containsKey(c1Id));
        Assert.assertEquals(chain1.size(), engine.ruleChains.get(c1Id).size());
        for (int i = 0; i < chain1.size(); i++)
            Assert.assertEquals(chain1.get(i), engine.ruleChains.get(c1Id).get(
                    i));
        Assert.assertTrue(chain1.equals(engine.ruleChains.get(c1Id)));
        Assert.assertTrue(engine.ruleChains.containsKey(c2Id));
        Assert.assertEquals(chain2.size(), engine.ruleChains.get(c2Id).size());
        Assert.assertTrue(chain2.equals(engine.ruleChains.get(c2Id)));
    }

    @Test
    public void testDnatRules() throws StateAccessException,
            ZkStateSerializationException, RuleIndexOutOfBoundsException {
        UUID c4Id = chainMgr.create(new ChainConfig("Chain4", rtrId));
        Set<NatTarget> nats = new HashSet<NatTarget>();
        nats.add(new NatTarget(0x0c000102, 0x0c00010a, (short) 1030,
                (short) 1050));
        List<Rule> chain4 = new Vector<Rule>();
        Rule r = new ForwardNatRule(cond, Action.CONTINUE, c4Id, 1, true, nats);
        ruleMgr.create(r);
        chain4.add(r);
        r = new ReverseNatRule(new Condition(), Action.RETURN, c4Id, 2, true);
        ruleMgr.create(r);
        chain4.add(r);

        Assert.assertTrue(natMap.recentlyFreedSnatTargets.isEmpty());
        Assert.assertTrue(natMap.snatTargets.isEmpty());
        Assert.assertEquals(4, engine.ruleChains.size());
        Assert.assertTrue(engine.ruleChains.containsKey(c4Id));
        Assert.assertTrue(chain4.equals(engine.ruleChains.get(c4Id)));
        RuleResult res = engine.applyChain("Chain4", pktMatch, pktMatch,
                inPort, null);
        Assert.assertTrue(Action.ACCEPT.equals(res.action));
        Assert.assertNull(res.jumpToChain);
        Assert.assertTrue(res.trackConnection);
        Assert.assertFalse(res.match.equals(pktMatch));
        int ip = res.match.getNetworkDestination();
        Assert.assertTrue(0x0c000102 <= ip);
        Assert.assertTrue(ip <= 0x0c00010a);
        short port = res.match.getTransportDestination();
        Assert.assertTrue(1030 <= port);
        Assert.assertTrue(port <= 1050);
        // Now build a response packet as it would be emitted by the receiver.
        MidoMatch respPkt = pktMatch.clone();
        respPkt.setNetworkSource(ip);
        respPkt.setTransportSource(port);
        respPkt.setNetworkDestination(pktMatch.getNetworkSource());
        respPkt.setTransportDestination(pktMatch.getTransportSource());
        res = engine.applyChain("Chain4", respPkt, respPkt, null, null);
        // Pkt is the response as seen by the original sender.
        Assert.assertTrue(Action.ACCEPT.equals(res.action));
        Assert.assertNull(res.jumpToChain);
        Assert.assertFalse(res.trackConnection);
        Assert.assertFalse(res.match.equals(respPkt));
        Assert.assertEquals(0x0a000b22, res.match.getNetworkSource());
        Assert.assertEquals(1234, res.match.getTransportSource());
        // Verify that only the source ip/port were changed in the response.
        respPkt.setNetworkSource(0x0a000b22);
        respPkt.setTransportSource((short) 1234);
        Assert.assertEquals(respPkt, res.match);
    }

    @Test
    public void testSnatRules() throws StateAccessException,
            ZkStateSerializationException, RuleIndexOutOfBoundsException {
        UUID c4Id = chainMgr.create(new ChainConfig("Chain4", rtrId));
        Set<NatTarget> nats = new HashSet<NatTarget>();
        nats.add(new NatTarget(0x0c000102, 0x0c00010a, (short) 1030,
                (short) 1050));
        List<Rule> chain4 = new Vector<Rule>();
        Rule r = new ForwardNatRule(cond, Action.CONTINUE, c4Id, 1, false, nats);
        ruleMgr.create(r);
        chain4.add(r);
        r = new ReverseNatRule(new Condition(), Action.RETURN, c4Id, 2, false);
        ruleMgr.create(r);
        chain4.add(r);

        Assert.assertTrue(natMap.recentlyFreedSnatTargets.isEmpty());
        Assert.assertTrue(nats.equals(natMap.snatTargets));
        Assert.assertEquals(4, engine.ruleChains.size());
        Assert.assertTrue(engine.ruleChains.containsKey(c4Id));
        Assert.assertTrue(chain4.equals(engine.ruleChains.get(c4Id)));
        RuleResult res = engine.applyChain("Chain4", pktMatch, pktMatch,
                inPort, null);
        Assert.assertEquals(Action.ACCEPT, res.action);
        Assert.assertNull(res.jumpToChain);
        Assert.assertTrue(res.trackConnection);
        Assert.assertFalse(res.match.equals(pktMatch));
        int ip = res.match.getNetworkSource();
        Assert.assertTrue(0x0c000102 <= ip);
        Assert.assertTrue(ip <= 0x0c00010a);
        short port = res.match.getTransportSource();
        Assert.assertTrue(1030 <= port);
        Assert.assertTrue(port <= 1050);
        // Now build a response packet as it would be emitted by the receiver.
        MidoMatch respPkt = pktMatch.clone();
        respPkt.setNetworkSource(pktMatch.getNetworkDestination());
        respPkt.setTransportSource(pktMatch.getTransportDestination());
        respPkt.setNetworkDestination(ip);
        respPkt.setTransportDestination(port);
        res = engine.applyChain("Chain4", respPkt, respPkt, null, null);
        // Pkt is the response as seen by the original sender.
        Assert.assertTrue(Action.ACCEPT.equals(res.action));
        Assert.assertNull(res.jumpToChain);
        Assert.assertFalse(res.trackConnection);
        Assert.assertFalse(res.match.equals(respPkt));
        Assert.assertEquals(0x0a001406, res.match.getNetworkDestination());
        Assert.assertEquals(4321, res.match.getTransportDestination());
        // Verify that only destination ip/port were changed in the response.
        respPkt.setNetworkDestination(0x0a001406);
        respPkt.setTransportDestination((short) 4321);
        Assert.assertEquals(respPkt, res.match);
    }
    * END OF TODO(abel): Fix this.
    */
}

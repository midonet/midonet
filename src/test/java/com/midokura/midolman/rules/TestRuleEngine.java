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
import org.junit.Ignore;

import org.junit.Test;

import com.midokura.midolman.layer4.MockNatMapping;
import com.midokura.midolman.layer4.NatMapping;
import com.midokura.midolman.openflow.MidoMatch;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.MockDirectory;
import com.midokura.midolman.state.RouterDirectory;

public class TestRuleEngine {

    static Random rand;
    static UUID rtrId;
    static MidoMatch pktMatch;
    static UUID inPort;
    static Condition cond;
    RouterDirectory rtrDir;
    RuleEngine engine;
    MockNatMapping natMap;
    List<Rule> chain1;
    List<Rule> chain2;
    List<Rule> chain3;

    private static class MyLiteralRule extends LiteralRule {
        private static final long serialVersionUID = 0L;
        int timesApplied;

        public MyLiteralRule(Condition condition, Action action) {
            super(condition, action);
            timesApplied = 0;
        }

        @Override
        public void apply(UUID inPortId, UUID outPortId, RuleResult res) {
            timesApplied++;
            super.apply(inPortId, outPortId, res);
        }
    }

    private static class MyRevSnatRule extends ReverseSnatRule {
        private static final long serialVersionUID = 0L;
        int timesApplied;

        public MyRevSnatRule(Condition condition, Action action) {
            super(condition, action);
            timesApplied = 0;
        }

        @Override
        public void apply(UUID inPortId, UUID outPortId, RuleResult res) {
            timesApplied++;
            super.apply(inPortId, outPortId, res);
        }
    }

    @BeforeClass
    public static void setupOnce() throws Exception {
        rand = new Random();
        rtrId = new UUID(rand.nextLong(), rand.nextLong());
        // Build a packet to test the rules.
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
    public void setup() throws KeeperException, InterruptedException,
            IOException, ClassNotFoundException {
        Directory dir = new MockDirectory();
        dir.add("/midolman", null, CreateMode.PERSISTENT);
        dir.add("/midolman/routers", null, CreateMode.PERSISTENT);
        Directory subDir = dir.getSubDirectory("/midolman/routers");
        rtrDir = new RouterDirectory(subDir);
        rtrDir.addRouter(rtrId);
        natMap = new MockNatMapping();
        engine = new RuleEngine(rtrDir, rtrId, natMap);
        chain1 = new Vector<Rule>();
        chain2 = new Vector<Rule>();
        chain3 = new Vector<Rule>();
        chain1.add(new MyRevSnatRule(new Condition(), Action.CONTINUE));
        Condition cond = new Condition();
        cond.conjunctionInv = true;
        // This rule should never be applied.
        chain1.add(new MyLiteralRule(cond, Action.DROP));
        chain1.add(new JumpRule(new Condition(), "Chain2"));
        chain1.add(new MyLiteralRule(new Condition(), Action.ACCEPT));
        // This rule should never be applied.
        chain1.add(new MyLiteralRule(new Condition(), Action.REJECT));
        chain2.add(new MyRevSnatRule(new Condition(), Action.CONTINUE));
        chain2.add(new JumpRule(new Condition(), "Chain3"));
        chain2.add(new MyRevSnatRule(new Condition(), Action.CONTINUE));
        chain2.add(new MyLiteralRule(new Condition(), Action.RETURN));
        chain3.add(new MyRevSnatRule(new Condition(), Action.CONTINUE));
        chain3.add(new MyRevSnatRule(new Condition(), Action.CONTINUE));
        chain3.add(new MyLiteralRule(new Condition(), Action.RETURN));
        Assert.assertTrue(engine.ruleChains.isEmpty());
        rtrDir.addRuleChain(rtrId, "Chain1", chain1);
        Assert.assertEquals(1, engine.ruleChains.size());
        Assert.assertTrue(engine.ruleChains.containsKey("Chain1"));
        Assert.assertTrue(chain1.equals(engine.ruleChains.get("Chain1")));
        rtrDir.addRuleChain(rtrId, "Chain2", chain2);
        Assert.assertEquals(2, engine.ruleChains.size());
        Assert.assertTrue(engine.ruleChains.containsKey("Chain2"));
        Assert.assertTrue(chain2.equals(engine.ruleChains.get("Chain2")));
        rtrDir.addRuleChain(rtrId, "Chain3", chain3);
        Assert.assertEquals(3, engine.ruleChains.size());
        Assert.assertTrue(engine.ruleChains.containsKey("Chain3"));
        Assert.assertTrue(chain3.equals(engine.ruleChains.get("Chain3")));
    }

    @Test
    public void testLiteralRuleChains() throws IOException, KeeperException,
            InterruptedException {
        // Get pointers to engine's chains since those instances are invoked.
        List<Rule> c1 = engine.ruleChains.get("Chain1");
        List<Rule> c2 = engine.ruleChains.get("Chain2");
        List<Rule> c3 = engine.ruleChains.get("Chain3");
        MidoMatch emptyPacket = new MidoMatch();
        RuleResult res = engine.applyChain("Chain1", emptyPacket, null, null);
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
    public void testRuleChainUpdates() throws IOException, KeeperException,
            InterruptedException {
        chain1.remove(0);
        chain1.remove(0);
        chain2.remove(0);
        rtrDir.setRuleChain(rtrId, "Chain1", chain1);
        rtrDir.setRuleChain(rtrId, "Chain2", chain2);
        rtrDir.deleteRuleChain(rtrId, "Chain3");
        Assert.assertEquals(2, engine.ruleChains.size());
        Assert.assertTrue(engine.ruleChains.containsKey("Chain1"));
        Assert.assertTrue(chain1.equals(engine.ruleChains.get("Chain1")));
        Assert.assertTrue(engine.ruleChains.containsKey("Chain2"));
        Assert.assertTrue(chain2.equals(engine.ruleChains.get("Chain2")));
    }

    @Test
    public void testDnatRules() throws IOException, KeeperException,
            InterruptedException {
        Set<NatTarget> nats = new HashSet<NatTarget>();
        nats.add(new NatTarget(0x0c000102, 0x0c00010a, (short) 1030,
                (short) 1050));
        List<Rule> chain4 = new Vector<Rule>();
        chain4.add(new DnatRule(cond, nats, Action.CONTINUE));
        chain4.add(new ReverseDnatRule(new Condition(), Action.RETURN));
        rtrDir.addRuleChain(rtrId, "Chain4", chain4);
        Assert.assertTrue(natMap.recentlyFreedSnatTargets.isEmpty());
        Assert.assertTrue(natMap.snatTargets.isEmpty());
        Assert.assertEquals(4, engine.ruleChains.size());
        Assert.assertTrue(engine.ruleChains.containsKey("Chain4"));
        Assert.assertTrue(chain4.equals(engine.ruleChains.get("Chain4")));
        MidoMatch pkt = pktMatch.clone();
        RuleResult res = engine.applyChain("Chain4", pkt, inPort, null);
        Assert.assertTrue(Action.ACCEPT.equals(res.action));
        Assert.assertNull(res.jumpToChain);
        Assert.assertTrue(res.trackConnection);
        Assert.assertEquals(pkt, res.match);
        Assert.assertFalse(pkt.equals(pktMatch));
        int ip = pkt.getNetworkDestination();
        Assert.assertTrue(0x0c000102 <= ip);
        Assert.assertTrue(ip <= 0x0c00010a);
        short port = pkt.getTransportDestination();
        Assert.assertTrue(1030 <= port);
        Assert.assertTrue(port <= 1050);
        // Now build a response packet as it would be emitted by the receiver. 
        MidoMatch respPkt = pktMatch.clone();
        respPkt.setNetworkSource(ip);
        respPkt.setTransportSource(port);
        respPkt.setNetworkDestination(pktMatch.getNetworkSource());
        respPkt.setTransportDestination(pktMatch.getTransportSource());
        pkt = respPkt.clone();
        res = engine.applyChain("Chain4", pkt, null, null);
        // Pkt is the response as seen by the original sender.
        Assert.assertTrue(Action.ACCEPT.equals(res.action));
        Assert.assertNull(res.jumpToChain);
        Assert.assertFalse(res.trackConnection);
        Assert.assertEquals(pkt, res.match);
        Assert.assertFalse(pkt.equals(respPkt));
        Assert.assertEquals(0x0a000b22, pkt.getNetworkSource());
        Assert.assertEquals(1234, pkt.getTransportSource());
        // Verify that only the source ip/port were changed in the response.
        respPkt.setNetworkSource(0x0a000b22);
        respPkt.setTransportSource((short)1234);
        Assert.assertTrue(pkt.equals(respPkt));
    }

    @Test
    public void testSnatRules() throws IOException, KeeperException,
            InterruptedException {
        Set<NatTarget> nats = new HashSet<NatTarget>();
        nats.add(new NatTarget(0x0c000102, 0x0c00010a, (short) 1030,
                (short) 1050));
        List<Rule> chain4 = new Vector<Rule>();
        chain4.add(new SnatRule(cond, nats, Action.CONTINUE));
        chain4.add(new ReverseSnatRule(new Condition(), Action.RETURN));
        rtrDir.addRuleChain(rtrId, "Chain4", chain4);
        Assert.assertTrue(natMap.recentlyFreedSnatTargets.isEmpty());
        Assert.assertTrue(nats.equals(natMap.snatTargets));
        Assert.assertEquals(4, engine.ruleChains.size());
        Assert.assertTrue(engine.ruleChains.containsKey("Chain4"));
        Assert.assertTrue(chain4.equals(engine.ruleChains.get("Chain4")));
        MidoMatch pkt = pktMatch.clone();
        RuleResult res = engine.applyChain("Chain4", pkt, inPort, null);
        Assert.assertTrue(Action.ACCEPT.equals(res.action));
        Assert.assertNull(res.jumpToChain);
        Assert.assertTrue(res.trackConnection);
        Assert.assertEquals(pkt, res.match);
        Assert.assertFalse(pkt.equals(pktMatch));
        int ip = pkt.getNetworkSource();
        Assert.assertTrue(0x0c000102 <= ip);
        Assert.assertTrue(ip <= 0x0c00010a);
        short port = pkt.getTransportSource();
        Assert.assertTrue(1030 <= port);
        Assert.assertTrue(port <= 1050);
        // Now build a response packet as it would be emitted by the receiver. 
        MidoMatch respPkt = pktMatch.clone();
        respPkt.setNetworkSource(pktMatch.getNetworkDestination());
        respPkt.setTransportSource(pktMatch.getTransportDestination());
        respPkt.setNetworkDestination(ip);
        respPkt.setTransportDestination(port);
        pkt = respPkt.clone();
        res = engine.applyChain("Chain4", pkt, null, null);
        // Pkt is the response as seen by the original sender.
        Assert.assertTrue(Action.ACCEPT.equals(res.action));
        Assert.assertNull(res.jumpToChain);
        Assert.assertFalse(res.trackConnection);
        Assert.assertEquals(pkt, res.match);
        Assert.assertFalse(pkt.equals(respPkt));
        Assert.assertEquals(0x0a001406, pkt.getNetworkDestination());
        Assert.assertEquals(4321, pkt.getTransportDestination());
        // Verify that only destination ip/port were changed in the response.
        respPkt.setNetworkDestination(0x0a001406);
        respPkt.setTransportDestination((short)4321);
        Assert.assertTrue(pkt.equals(respPkt));
    }
}

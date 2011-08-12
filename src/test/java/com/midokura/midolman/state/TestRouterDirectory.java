package com.midokura.midolman.state;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.Vector;

import org.apache.zookeeper.KeeperException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.midokura.midolman.layer3.Route;
import com.midokura.midolman.layer3.Route.NextHop;
import com.midokura.midolman.rules.Action;
import com.midokura.midolman.rules.Condition;
import com.midokura.midolman.rules.DnatRule;
import com.midokura.midolman.rules.JumpRule;
import com.midokura.midolman.rules.LiteralRule;
import com.midokura.midolman.rules.NatTarget;
import com.midokura.midolman.rules.ReverseDnatRule;
import com.midokura.midolman.rules.ReverseSnatRule;
import com.midokura.midolman.rules.Rule;
import com.midokura.midolman.rules.SnatRule;

public class TestRouterDirectory {

    RouterDirectory rtrDir;
    Random rand;

    @Before
    public void setUp() {
        Directory dir = new MockDirectory();
        rtrDir = new RouterDirectory(dir);
        rand = new Random();
    }

    @Test
    public void testAddGetDeleteRoutes() throws IOException, KeeperException,
            InterruptedException, ClassNotFoundException {
        UUID rtrId = new UUID(rand.nextLong(), rand.nextLong());
        rtrDir.addRouter(rtrId);
        StringBuilder strb = new StringBuilder("10.0.1.0,24,10.0.2.0,24,");
        strb.append(NextHop.BLACKHOLE.toString()).append(",,,1000,myattrs");
        Route rt1 = Route.fromString(strb.toString());
        rtrDir.addRoute(rtrId, rt1);
        strb = new StringBuilder("10.4.0.0,16,10.5.0.0,16,");
        strb.append(NextHop.REJECT.toString()).append(",,,2000,mymanyattrs");
        Route rt2 = Route.fromString(strb.toString());
        rtrDir.addRoute(rtrId, rt2);
        Collection<Route> routes = rtrDir.getRoutes(rtrId);
        Assert.assertTrue(routes.contains(rt1));
        Assert.assertTrue(routes.contains(rt2));
        Assert.assertEquals(2, routes.size());
        rtrDir.deleteRoute(rtrId, rt1);
        routes = rtrDir.getRoutes(rtrId);
        Assert.assertTrue(routes.contains(rt2));
        Assert.assertEquals(1, routes.size());
    }

    private static class MyWatcher implements Runnable {
        public int timesCalled = 0;

        @Override
        public void run() {
            timesCalled++;
        }
    }

    @Test
    public void testAddGetDeleteChains() throws IOException, KeeperException,
            InterruptedException, ClassNotFoundException {
        UUID rtrId = new UUID(rand.nextLong(), rand.nextLong());
        rtrDir.addRouter(rtrId);
        MyWatcher chainsWatcher = new MyWatcher();
        Collection<String> chainNames = rtrDir.getRuleChainNames(rtrId,
                chainsWatcher);
        Assert.assertTrue(chainNames.isEmpty());
        List<Rule> chain1 = new Vector<Rule>();
        chain1.add(new LiteralRule(new Condition(), Action.ACCEPT));
        chain1.add(new LiteralRule(new Condition(), Action.DROP));
        chain1.add(new LiteralRule(new Condition(), Action.REJECT));
        chain1.add(new LiteralRule(new Condition(), Action.RETURN));
        rtrDir.addRuleChain(rtrId, "Chain1", chain1);
        Assert.assertEquals(1, chainsWatcher.timesCalled);
        chainNames = rtrDir.getRuleChainNames(rtrId, chainsWatcher);
        Assert.assertEquals(1, chainNames.size());
        Assert.assertTrue(chainNames.contains("Chain1"));
        List<Rule> chain2 = new Vector<Rule>();
        chain2.add(new JumpRule(new Condition(), "Chain1"));
        chain2.add(new ReverseDnatRule(new Condition(), Action.RETURN));
        chain2.add(new ReverseSnatRule(new Condition(), Action.ACCEPT));
        Set<NatTarget> nats = new HashSet<NatTarget>();
        nats.add(new NatTarget(0x82010104, 0x82010104, (short) 1111,
                (short) 1113));
        chain2.add(new DnatRule(new Condition(), nats, Action.CONTINUE));
        nats = new HashSet<NatTarget>();
        nats.add(new NatTarget(0x840a0a02, 0x840a0a0b, (short) 3000,
                (short) 4000));
        chain2.add(new SnatRule(new Condition(), nats, Action.RETURN));
        rtrDir.addRuleChain(rtrId, "Chain2", chain2);
        Assert.assertEquals(2, chainsWatcher.timesCalled);
        chainNames = rtrDir.getRuleChainNames(rtrId, chainsWatcher);
        Assert.assertEquals(2, chainNames.size());
        Assert.assertTrue(chainNames.contains("Chain1"));
        Assert.assertTrue(chainNames.contains("Chain2"));

        MyWatcher rulesWatcher1 = new MyWatcher();
        List<Rule> storedRules = rtrDir.getRuleChain(rtrId, "Chain1",
                rulesWatcher1);
        Assert.assertTrue(chain1.equals(storedRules));
        chain1.remove(3);
        rtrDir.setRuleChain(rtrId,  "Chain1", chain1);
        Assert.assertEquals(1, rulesWatcher1.timesCalled);
        storedRules = rtrDir.getRuleChain(rtrId, "Chain1", rulesWatcher1);
        Assert.assertTrue(chain1.equals(storedRules));
        chain1.remove(2);
        rtrDir.setRuleChain(rtrId,  "Chain1", chain1);
        Assert.assertEquals(2, rulesWatcher1.timesCalled);
        storedRules = rtrDir.getRuleChain(rtrId, "Chain1", rulesWatcher1);
        Assert.assertTrue(chain1.equals(storedRules));

        MyWatcher rulesWatcher2 = new MyWatcher();
        storedRules = rtrDir.getRuleChain(rtrId, "Chain2", rulesWatcher2);
        Assert.assertTrue(chain2.equals(storedRules));
        chain2.remove(0);
        chain2.remove(0);
        rtrDir.setRuleChain(rtrId,  "Chain2", chain2);
        Assert.assertEquals(1, rulesWatcher2.timesCalled);
        storedRules = rtrDir.getRuleChain(rtrId, "Chain2", rulesWatcher2);
        Assert.assertTrue(chain2.equals(storedRules));

        rtrDir.deleteRuleChain(rtrId, "Chain1");
        Assert.assertEquals(3, chainsWatcher.timesCalled);
        Assert.assertEquals(3, rulesWatcher1.timesCalled);
        chainNames = rtrDir.getRuleChainNames(rtrId, chainsWatcher);
        Assert.assertEquals(1, chainNames.size());
        Assert.assertTrue(chainNames.contains("Chain2"));

        rtrDir.deleteRuleChain(rtrId, "Chain2");
        Assert.assertEquals(4, chainsWatcher.timesCalled);
        Assert.assertEquals(2, rulesWatcher2.timesCalled);
        chainNames = rtrDir.getRuleChainNames(rtrId, chainsWatcher);
        Assert.assertTrue(chainNames.isEmpty());
    }
}

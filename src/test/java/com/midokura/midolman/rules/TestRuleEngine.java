package com.midokura.midolman.rules;

import java.io.IOException;
import java.util.List;
import java.util.Random;
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
    static RouterDirectory rtrDir;
    static UUID rtrId;
    RuleEngine engine;
    NatMapping natMap;

    @BeforeClass
    public static void setupOnce() throws Exception {
        Directory dir = new MockDirectory();
        dir.add("/midolman", null, CreateMode.PERSISTENT);
        dir.add("/midolman/routers", null, CreateMode.PERSISTENT);
        Directory subDir = dir.getSubDirectory("/midolman/routers");
        rtrDir = new RouterDirectory(subDir);
        rand = new Random();
        rtrId = new UUID(rand.nextLong(), rand.nextLong());
        rtrDir.addRouter(rtrId);
    }

    @Before
    public void setup() throws KeeperException, InterruptedException,
            IOException, ClassNotFoundException {
        natMap = new MockNatMapping();
        engine = new RuleEngine(rtrDir, rtrId, natMap);
    }

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

    @Ignore
    @Test
    public void testLiteralRuleChains() throws IOException, KeeperException,
            InterruptedException {
        List<Rule> chain1 = new Vector<Rule>();
        List<Rule> chain2 = new Vector<Rule>();
        List<Rule> chain3 = new Vector<Rule>();
        chain1.add(new MyLiteralRule(new Condition(), Action.CONTINUE));
        Condition cond = new Condition();
        cond.conjunctionInv = true;
        // This rule should never be applied.
        chain1.add(new MyLiteralRule(cond, Action.DROP));
        chain1.add(new JumpRule(new Condition(), "Chain2"));
        chain1.add(new MyLiteralRule(new Condition(), Action.ACCEPT));
        // This rule should never be applied.
        chain1.add(new MyLiteralRule(new Condition(), Action.REJECT));
        chain2.add(new MyLiteralRule(new Condition(), Action.CONTINUE));
        chain2.add(new JumpRule(new Condition(), "Chain3"));
        chain2.add(new MyLiteralRule(new Condition(), Action.CONTINUE));
        chain2.add(new MyLiteralRule(new Condition(), Action.RETURN));
        chain3.add(new MyLiteralRule(new Condition(), Action.CONTINUE));
        chain3.add(new MyLiteralRule(new Condition(), Action.CONTINUE));
        chain3.add(new MyLiteralRule(new Condition(), Action.RETURN));
        rtrDir.addRuleChain(rtrId, "Chain1", chain1);
        rtrDir.addRuleChain(rtrId, "Chain2", chain2);
        rtrDir.addRuleChain(rtrId, "Chain3", chain3);
        // Verify that the rule engine has the three chains.
        engine.applyChain("Chain1", new MidoMatch(), null, null);
        MyLiteralRule r = (MyLiteralRule) chain1.get(0);
        Assert.assertEquals(1, r.timesApplied);
        r = (MyLiteralRule) chain1.get(1);
        Assert.assertEquals(0, r.timesApplied);
        r = (MyLiteralRule) chain1.get(3);
        Assert.assertEquals(1, r.timesApplied);
        r = (MyLiteralRule) chain1.get(4);
        Assert.assertEquals(0, r.timesApplied);
        r = (MyLiteralRule) chain2.get(0);
        Assert.assertEquals(1, r.timesApplied);
        r = (MyLiteralRule) chain2.get(2);
        Assert.assertEquals(1, r.timesApplied);
        r = (MyLiteralRule) chain2.get(3);
        Assert.assertEquals(1, r.timesApplied);
        r = (MyLiteralRule) chain1.get(0);
        Assert.assertEquals(1, r.timesApplied);
        r = (MyLiteralRule) chain2.get(1);
        Assert.assertEquals(1, r.timesApplied);
        r = (MyLiteralRule) chain3.get(2);
        Assert.assertEquals(1, r.timesApplied);
    }
}

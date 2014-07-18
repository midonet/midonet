/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.storage;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.test.TestingServer;
import org.apache.zookeeper.KeeperException;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import org.midonet.cluster.data.storage.FieldBinding.DeleteAction;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.midonet.cluster.data.storage.FieldBinding.DeleteAction.CLEAR;

public class ZookeeperObjectMapperTest {

    private static class Bridge {
        public UUID id;
        public String name;
        public UUID inChainId;
        public UUID outChainId;
        public List<UUID> portIds;

        Bridge() {}

        Bridge(String name, UUID inChainId, UUID outChainId) {
            this.id = UUID.randomUUID();
            this.name = name;
            this.inChainId = inChainId;
            this.outChainId = outChainId;
        }
    }

    private static class Router {
        public UUID id;
        public String name;
        public UUID inChainId;
        public UUID outChainId;
        public List<UUID> portIds;

        Router() {}

        Router(String name, UUID inChainId, UUID outChainId) {
            this.id = UUID.randomUUID();
            this.name = name;
            this.inChainId = inChainId;
            this.outChainId = outChainId;
        }
    }

    private static class Port {
        public UUID id;
        public String name;
        public UUID peerId;
        public UUID bridgeId;
        public UUID routerId;
        public UUID inChainId;
        public UUID outChainId;
        public List<UUID> ruleIds;

        Port() {}

        Port(String name, UUID bridgeId, UUID routerId) {
            this(name, bridgeId, routerId, null, null, null);
        }

        Port(String name, UUID bridgeId, UUID routerId,
             UUID peerId, UUID inChainId, UUID outChainId) {
            this.id = UUID.randomUUID();
            this.name = name;
            this.peerId = peerId;
            this.bridgeId = bridgeId;
            this.routerId = routerId;
            this.inChainId = inChainId;
            this.outChainId = outChainId;
        }
    }

    private static class Chain {
        public UUID id;
        public String name;
        public List<UUID> ruleIds;
        public List<UUID> bridgeIds;
        public List<UUID> routerIds;
        public List<UUID> portIds;

        Chain() {}

        public Chain(String name) {
            this.id = UUID.randomUUID();
            this.name = name;
        }
    }

    private static class Rule {
        public UUID id;
        public UUID chainId;
        public String name;
        public List<UUID> portIds;
        public List<String> strings; // Needed for a declareBinding() test.

        Rule() {}

        public Rule(String name, UUID chainId, UUID... portIds) {
            this.id = UUID.randomUUID();
            this.name = name;
            this.chainId = chainId;
            this.portIds = Arrays.asList(portIds);
        }
    }

    private static class NoIdField {
        public UUID notId;
        public List<UUID> refIds;
    }

    private ZookeeperObjectMapper orm;
    private static TestingServer testingServer;

    private static final int zkPort = 12181; // Avoid conflicting with real ZK.
    private static final String zkConnectionString = "127.0.0.1:" + zkPort;
    private static final String zkRootDir = "/zkomtest";

    @BeforeClass
    public static void classSetup() throws Exception {
        testingServer = new TestingServer(zkPort);
    }

    @AfterClass
    public static void classTeardown() throws Exception {
        testingServer.close();
    }

    @Before
    public void setup() throws Exception {
        RetryPolicy retryPolicy = new ExponentialBackoffRetry(1000, 3);
        CuratorFramework client =
            CuratorFrameworkFactory.newClient(zkConnectionString, retryPolicy);

        orm = new ZookeeperObjectMapper(zkRootDir, client);

        orm.declareBinding(Bridge.class, "inChainId", DeleteAction.CLEAR,
                           Chain.class, "bridgeIds", DeleteAction.CLEAR);
        orm.declareBinding(Bridge.class, "outChainId", DeleteAction.CLEAR,
                           Chain.class, "bridgeIds", DeleteAction.CLEAR);

        orm.declareBinding(Router.class, "inChainId", DeleteAction.CLEAR,
                           Chain.class, "routerIds", DeleteAction.CLEAR);
        orm.declareBinding(Router.class, "outChainId", DeleteAction.CLEAR,
                           Chain.class, "routerIds", DeleteAction.CLEAR);

        orm.declareBinding(Port.class, "bridgeId", DeleteAction.CLEAR,
                           Bridge.class, "portIds", DeleteAction.ERROR);
        orm.declareBinding(Port.class, "routerId", DeleteAction.CLEAR,
                           Router.class, "portIds", DeleteAction.ERROR);
        orm.declareBinding(Port.class, "inChainId", DeleteAction.CLEAR,
                           Chain.class, "portIds", DeleteAction.CLEAR);
        orm.declareBinding(Port.class, "outChainId", DeleteAction.CLEAR,
                           Chain.class, "portIds", DeleteAction.CLEAR);
        orm.declareBinding(Port.class, "peerId", DeleteAction.CLEAR,
                           Port.class, "peerId", DeleteAction.CLEAR);

        orm.declareBinding(Chain.class, "ruleIds", DeleteAction.CASCADE,
                           Rule.class, "chainId", DeleteAction.CLEAR);

        orm.declareBinding(Rule.class, "portIds", DeleteAction.CLEAR,
                           Port.class, "ruleIds", DeleteAction.CLEAR);

        try {
            client.delete().deletingChildrenIfNeeded().forPath(zkRootDir);
        } catch (KeeperException.NoNodeException ex) {
            // Won't exist the first time.
        }

        client.create().forPath(zkRootDir);
        for (String s : new String[]{
                "Bridge", "Router", "Port", "Chain", "Rule"})
            client.create().forPath(zkRootDir + "/" + s);
    }

    @Test
    public void testOrm() throws Exception {
        // Create two chains.
        Chain chain1 = new Chain("chain1");
        Chain chain2 = new Chain("chain2");
        createObjects(chain1, chain2);

        // Add bridge referencing the two chains.
        Bridge bridge = new Bridge("bridge1", chain1.id, chain2.id);
        orm.create(bridge);

        // Chains should have backrefs to the bridge.
        assertThat(orm.get(Chain.class, chain1.id).bridgeIds,
                contains(bridge.id));
        assertThat(orm.get(Chain.class, chain2.id).bridgeIds,
                contains(bridge.id));

        // Add a router referencing chain1 twice.
        Router router = new Router("router1", chain1.id, chain1.id);
        orm.create(router);

        // Chain1 should have two references to the router.
        assertThat(orm.get(Chain.class, chain1.id).routerIds,
                contains(router.id, router.id));

        // Add two ports each to bridge and router, linking two of them.
        Port bPort1 = new Port("bridge-port1", bridge.id, null);
        Port bPort2 = new Port("bridge-port2", bridge.id, null);
        Port rPort1 = new Port("router-port1", null, router.id);
        Port rPort2 = new Port("router-port2", null, router.id,
                               bPort2.id, null, null);
        createObjects(bPort1, bPort2, rPort1, rPort2);

        // The ports' IDs should show up in their parents' portIds lists,
        // and bPort2 should have its peerId set.
        assertThat(orm.get(Bridge.class, bridge.id).portIds,
                   contains(bPort1.id, bPort2.id));
        assertThat(orm.get(Router.class, router.id).portIds,
                   contains(rPort1.id, rPort2.id));
        assertEquals(rPort2.id, orm.get(Port.class, bPort2.id).peerId);

        // Should not be able to link bPort1 to rPort2 because rPort2 is
        // already linked.
        bPort1.peerId = rPort2.id;
        try {
            orm.update(bPort1);
            fail("Linking to an already-linked port should not be allowed.");
        } catch (ReferenceConflictException ex) {}

        // Link bPort1 and rPort1 with an update.
        bPort1.peerId = rPort1.id;
        orm.update(bPort1);
        assertEquals(bPort1.id, orm.get(Port.class, rPort1.id).peerId);

        // Add some rules to the chains.
        Rule c1Rule1 = new Rule("chain1-rule1", chain1.id,
                                bPort1.id, bPort2.id);
        Rule c1Rule2 = new Rule("chain1-rule2", chain1.id,
                                bPort2.id, rPort1.id);
        Rule c2Rule1 = new Rule("chain2-rule1", chain2.id,
                                rPort1.id, bPort1.id);
        createObjects(c1Rule1, c1Rule2, c2Rule1);

        assertThat(orm.get(Chain.class, chain1.id).ruleIds,
                contains(c1Rule1.id, c1Rule2.id));
        assertThat(orm.get(Chain.class, chain2.id).ruleIds,
                contains(c2Rule1.id));

        assertPortsRuleIds(bPort1, c1Rule1.id, c2Rule1.id);
        assertPortsRuleIds(bPort2, c1Rule1.id, c1Rule2.id);
        assertPortsRuleIds(rPort1, c1Rule2.id, c2Rule1.id);

        // Try some updates on c2Rule1's ports.
        c2Rule1.portIds = Arrays.asList(bPort2.id, rPort1.id, rPort2.id);
        orm.update(c2Rule1);
        assertPortsRuleIds(bPort1, c1Rule1.id);
        assertPortsRuleIds(bPort2, c1Rule1.id, c1Rule2.id, c2Rule1.id);
        assertPortsRuleIds(rPort1, c1Rule2.id, c2Rule1.id);
        assertPortsRuleIds(rPort2, c2Rule1.id);

        c2Rule1.portIds = Arrays.asList(rPort1.id, bPort1.id);
        orm.update(c2Rule1);
        assertPortsRuleIds(bPort1, c1Rule1.id, c2Rule1.id);
        assertPortsRuleIds(bPort2, c1Rule1.id, c1Rule2.id);
        assertPortsRuleIds(rPort1, c1Rule2.id, c2Rule1.id);
        assertThat(orm.get(Port.class, rPort2.id).ruleIds, empty());

        // Should not be able to delete the bridge while it has ports.
        try {
            orm.delete(Bridge.class, bridge.id);
            fail("Delete should fail while bridge has ports.");
        } catch (ObjectReferencedException ex) {}

        // Delete a bridge port and verify that references to it are cleared.
        orm.delete(Port.class, bPort1.id);
        assertFalse(orm.exists(Port.class, bPort1.id));
        assertThat(orm.get(Bridge.class, bridge.id).portIds,
                contains(bPort2.id));
        assertThat(orm.get(Rule.class, c1Rule1.id).portIds,
                contains(bPort2.id));
        assertThat(orm.get(Rule.class, c2Rule1.id).portIds,
                contains(rPort1.id));

        // Delete the other bridge port.
        orm.delete(Port.class, bPort2.id);
        assertFalse(orm.exists(Port.class, bPort2.id));
        assertThat(orm.get(Bridge.class, bridge.id).portIds, empty());
        assertNull(orm.get(Port.class, rPort2.id).peerId);
        assertThat(orm.get(Rule.class, c1Rule1.id).portIds, empty());
        assertThat(orm.get(Rule.class, c1Rule2.id).portIds,
                contains(rPort1.id));

        // Delete the bridge and verify references to it are cleared.
        orm.delete(Bridge.class, bridge.id);
        assertThat(orm.get(Chain.class, chain1.id).bridgeIds, empty());
        assertThat(orm.get(Chain.class, chain2.id).bridgeIds, empty());

        // Delete a chain and verify that the delete cascades to rules.
        orm.delete(Chain.class, chain1.id);
        assertFalse(orm.exists(Chain.class, chain1.id));
        assertFalse(orm.exists(Rule.class, c1Rule1.id));
        assertFalse(orm.exists(Rule.class, c1Rule2.id));

        // Additionally, the cascading delete of c1Rule2 should have cleared
        // rPort1's reference to it.
        assertPortsRuleIds(rPort1, c2Rule1.id);
    }

    @Test(expected = ObjectReferencedException.class)
    public void testCascadeToDeleteError() throws Exception {
        orm.clearBindings();
        orm.declareBinding(Bridge.class, "inChainId", DeleteAction.CASCADE,
                           Chain.class, "bridgeIds", DeleteAction.CLEAR);
        orm.declareBinding(Chain.class, "ruleIds", DeleteAction.ERROR,
                           Rule.class, "chainId", DeleteAction.CLEAR);

        Chain chain = new Chain("chain");
        Rule rule = new Rule("rule", chain.id);
        Bridge bridge = new Bridge("bridge", chain.id, null);
        createObjects(chain, rule, bridge);

        orm.delete(Bridge.class, bridge.id);
    }

    @Test
    public void testCreateBindingForClassWithNoId() throws Exception {
        try {
            orm.declareBinding(NoIdField.class, "notId", CLEAR,
                Bridge.class, "portIds", CLEAR);
            fail("Should not allow binding of class with no id field.");
        } catch (IllegalArgumentException ex) {
            assertEquals("id", ex.getCause().getMessage());
        }
    }

    @Test
    public void testCreateBindingWithUnrecognizedFieldName() throws Exception {
        try {
            orm.declareBinding(Bridge.class, "noSuchField", CLEAR,
                    Port.class, "bridgeId", CLEAR);
            fail("Should not allow binding with unrecognized field name.");
        } catch (IllegalArgumentException ex) {
            assertEquals("noSuchField", ex.getCause().getMessage());
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCreateBindingWithWrongScalarRefType() throws Exception {
        orm.declareBinding(Bridge.class, "name", CLEAR,
                Port.class, "bridgeId", CLEAR);
        fail("Should not allow ref from String to UUID.");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCreateBindingWithWrongListRefType() throws Exception {
        orm.declareBinding(Chain.class, "ruleIds", CLEAR,
                Rule.class, "strings", CLEAR);
        fail("Should not allow ref from List<String> to UUID.");
    }

    @Test
    public void testGetNonExistingItem() throws Exception {
        UUID id = UUID.randomUUID();
        try {
            orm.get(Bridge.class, id);
            fail("Should not be able to get non-existing object.");
        } catch (NotFoundException ex) {
            assertEquals(Bridge.class, ex.getClazz());
            assertEquals(id, ex.getId());
        }
    }

    @Test
    public void testCreateWithExistingId() throws Exception {
        Chain chain = new Chain("chain");
        orm.create(chain);
        try {
            orm.create(chain);
            fail("Should not be able to create object with in-use ID.");
        } catch (ObjectExistsException ex) {
            assertEquals(Chain.class, ex.getClazz());
            assertEquals(chain.id, ex.getId());
        }
    }

    @Test
    public void testCreateWithMissingReference() throws Exception {
        Rule rule = new Rule("rule", UUID.randomUUID());
        try {
            orm.create(rule);
            fail("Should not be able to create object with reference missing.");
        } catch (NotFoundException ex) {
            assertEquals(Chain.class, ex.getClazz());
            assertEquals(rule.chainId, ex.getId());
        }
    }

    @Test
    public void testUpdateWithNonExistingId() throws Exception {
        Chain chain = new Chain("chain");
        try {
            orm.update(chain);
            fail("Should not be able to update nonexisting item.");
        } catch (NotFoundException ex) {
            assertEquals(Chain.class, ex.getClazz());
            assertEquals(chain.id, ex.getId());
        }
    }

    @Test
    public void testUpdateWithMissingReference() throws Exception {
        Rule rule = new Rule("rule", null);
        orm.create(rule);

        rule.chainId = UUID.randomUUID();
        try {
            orm.update(rule);
            fail("Should not be able to update with missing reference.");
        } catch (NotFoundException ex) {
            assertEquals(Chain.class, ex.getClazz());
            assertEquals(rule.chainId, ex.getId());
        }
    }

    @Test
    public void testUpdateWithReferenceConflict() throws Exception {
        Rule rule = new Rule("rule", null);
        orm.create(rule);

        Chain chain1 = new Chain("chain1");
        chain1.ruleIds = Arrays.asList(rule.id);
        orm.create(chain1);

        Chain chain2 = new Chain("chain2");
        orm.create(chain2);

        chain2.ruleIds = Arrays.asList(rule.id);
        try {
            orm.update(chain2);
            fail("Should not be able to steal rule from another chain.");
        } catch (ReferenceConflictException ex) {
            assertThat(ex.getReferencingObj(), instanceOf(Rule.class));
            assertEquals("chainId", ex.getReferencingField().getName());
            assertEquals(Chain.class, ex.getReferencedClass());
            assertEquals(chain1.id, ex.getReferencedId());
        }
    }

    @Test
    public void testDeleteNonExistingObject() throws Exception {
        UUID id = UUID.randomUUID();
        try {
            orm.delete(Bridge.class, id);
        } catch (NotFoundException ex) {
            assertEquals(Bridge.class, ex.getClazz());
            assertEquals(id, ex.getId());
        }
    }

    @Test
    public void testGetAllWithEmptyResult() throws Exception {
        List<Chain> chains = orm.getAll(Chain.class);
        assertThat(chains, empty());
    }

    @Test
    public void testGetAllWithMultipleObjects() throws Exception {
        Chain chain1 = new Chain("chain1");
        Chain chain2 = new Chain("chain2");
        createObjects(chain1, chain2);
        List<Chain> chains = orm.getAll(Chain.class);
        assertEquals(2, chains.size());
    }

    private void assertPortsRuleIds(Port port, UUID... ruleIds)
            throws Exception {
        assertThat(orm.get(Port.class, port.id).ruleIds, contains(ruleIds));
    }

    private void createObjects(Object... objects) throws Exception {
        for (Object object : objects) {
            orm.create(object);
        }
    }
}

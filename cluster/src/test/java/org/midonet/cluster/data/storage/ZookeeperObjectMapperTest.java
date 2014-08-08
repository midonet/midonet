/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.storage;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.google.protobuf.Message;
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
import org.midonet.cluster.models.Commons;
import org.midonet.cluster.models.Devices;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ZookeeperObjectMapperTest {

    protected static class Bridge {
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

    protected static class Router {
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

    protected static class Port {
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

    protected static class Chain {
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

    protected static class Rule {
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

    protected static class NoIdField {
        public UUID notId;
        public List<UUID> refIds;
    }

    private ZookeeperObjectMapper orm;
    private static TestingServer testingServer;

    private static final int zkPort = 12181; // Avoid conflicting with real ZK.
    private static final String zkConnectionString = "127.0.0.1:" + zkPort;
    private static final String zkRootDir = "/zkomtest";

    private static final Commons.UUID bridgeUuid = createRandomUuidproto();
    private static final Commons.UUID chainUuid = createRandomUuidproto();

    private static Commons.UUID createRandomUuidproto() {
        UUID uuid = UUID.randomUUID();
        return Commons.UUID.newBuilder().setMsb(uuid.getMostSignificantBits())
                                        .setLsb(uuid.getLeastSignificantBits())
                                        .build();
    }

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

        this.initBindings();

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

    /* Initializes Bindings for ZOM. */
    private void initBindings() {
        // Bindings for POJOs.
        addPojoBinding(Bridge.class, "inChainId", DeleteAction.CLEAR,
                       Chain.class, "bridgeIds", DeleteAction.CLEAR);
        addPojoBinding(Bridge.class, "outChainId", DeleteAction.CLEAR,
                       Chain.class, "bridgeIds", DeleteAction.CLEAR);

        addPojoBinding(Router.class, "inChainId", DeleteAction.CLEAR,
                       Chain.class, "routerIds", DeleteAction.CLEAR);
        addPojoBinding(Router.class, "outChainId", DeleteAction.CLEAR,
                       Chain.class, "routerIds", DeleteAction.CLEAR);

        addPojoBinding(Port.class, "bridgeId", DeleteAction.CLEAR,
                       Bridge.class, "portIds", DeleteAction.ERROR);
        addPojoBinding(Port.class, "routerId", DeleteAction.CLEAR,
                       Router.class, "portIds", DeleteAction.ERROR);
        addPojoBinding(Port.class, "inChainId", DeleteAction.CLEAR,
                       Chain.class, "portIds", DeleteAction.CLEAR);
        addPojoBinding(Port.class, "outChainId", DeleteAction.CLEAR,
                       Chain.class, "portIds", DeleteAction.CLEAR);
        addPojoBinding(Port.class, "peerId", DeleteAction.CLEAR,
                       Port.class, "peerId", DeleteAction.CLEAR);

        addPojoBinding(Chain.class, "ruleIds", DeleteAction.CASCADE,
                       Rule.class, "chainId", DeleteAction.CLEAR);

        addPojoBinding(Rule.class, "portIds", DeleteAction.CLEAR,
                       Port.class, "ruleIds", DeleteAction.CLEAR);

        // Bindings for proto-backed objects.
        addProtoBinding(Devices.Bridge.getDefaultInstance(),
                        "inbound_filter_id",
                        DeleteAction.CLEAR,
                        Devices.Chain.getDefaultInstance(),
                        "bridge_ids",
                        DeleteAction.CLEAR);
        addProtoBinding(Devices.Bridge.getDefaultInstance(),
                        "outbound_filter_id",
                        DeleteAction.CLEAR,
                        Devices.Chain.getDefaultInstance(),
                        "bridge_ids",
                        DeleteAction.CLEAR);
    }

    private void addPojoBinding(
            Class<?> leftClass, String leftFld, DeleteAction leftAction,
            Class<?> rightClass, String rightFld, DeleteAction rightAction) {
        orm.addBindings(PojoFieldBinding.createBindings(
                leftClass, leftFld, leftAction,
                rightClass, rightFld, rightAction));
    }

    private void addProtoBinding(
            Message leftMsg, String leftFld, DeleteAction leftAction,
            Message rightMsg, String rightFld, DeleteAction rightAction) {
        orm.addBindings(ProtoFieldBinding.createBindings(
                leftMsg, leftFld, leftAction,
                rightMsg, rightFld, rightAction));
    }

    /* A helper method for creating a proto Bridge. */
    private Devices.Bridge createProtoBridge(Commons.UUID bridgeId,
                                             String name,
                                             boolean adminStateUp,
                                             long tunnelKey,
                                             Commons.UUID inFilterId,
                                             Commons.UUID outFilterId,
                                             Commons.UUID vxLanPortId) {
        Devices.Bridge.Builder bridgeBuilder = Devices.Bridge.newBuilder();
        bridgeBuilder.setId(bridgeId);
        bridgeBuilder.setName(name);
        bridgeBuilder.setAdminStateUp(adminStateUp);
        bridgeBuilder.setTunnelKey(tunnelKey);
        if (inFilterId != null)
            bridgeBuilder.setInboundFilterId(inFilterId);
        if (outFilterId != null)
            bridgeBuilder.setOutboundFilterId(outFilterId);
        if (vxLanPortId != null)
            bridgeBuilder.setVxLanPortId(vxLanPortId);

        return bridgeBuilder.build();
    }

    /* A helper method for creating a proto Bridge. */
    private Devices.Bridge createProtoBridge(Commons.UUID bridgeId,
                                             String name,
                                             boolean adminStateUp,
                                             long tunnelKey) {
        return this.createProtoBridge(bridgeId,
                                      name,
                                      adminStateUp,
                                      tunnelKey,
                                      null,
                                      null,
                                      null);
    }

    /* A helper method for creating a proto Bridge. */
    private Devices.Bridge createProtoBridge(Commons.UUID bridgeId,
                                             String name,
                                             Commons.UUID inFilterId,
                                             Commons.UUID outFilterId) {
        return this.createProtoBridge(bridgeId,
                                      name,
                                      true,  // Admin state default true
                                      -1,    // fake tunnel key
                                      inFilterId,
                                      outFilterId,
                                      null);  // A fake VxLanPort ID.
    }

    /* A helper method for creating a proto Chain. */
    private Devices.Chain createProtoChain(Commons.UUID chainId, String name) {
        Devices.Chain.Builder chainBuilder = Devices.Chain.newBuilder();
        chainBuilder.setId(chainId);
        chainBuilder.setName(name);
        return chainBuilder.build();
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
        addPojoBinding(Bridge.class, "inChainId", DeleteAction.CASCADE,
                       Chain.class, "bridgeIds", DeleteAction.CLEAR);
        addPojoBinding(Chain.class, "ruleIds", DeleteAction.ERROR,
                       Rule.class, "chainId", DeleteAction.CLEAR);

        Chain chain = new Chain("chain");
        Rule rule = new Rule("rule", chain.id);
        Bridge bridge = new Bridge("bridge", chain.id, null);
        createObjects(chain, rule, bridge);

        orm.delete(Bridge.class, bridge.id);
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
    public void testCreateProtoBridge() throws Exception {
        Devices.Bridge bridge =
                this.createProtoBridge(bridgeUuid, "test_bridge", true, 10);
        orm.create(bridge);

        Devices.Bridge bridgeOut = orm.get(Devices.Bridge.class, bridgeUuid);
        assertEquals("The retrieved proto object is equal to the original.",
                     bridge, bridgeOut);
    }

    @Test
    public void testCreateProtoBridgeWithExistingId() throws Exception {
        Devices.Bridge bridge =
                this.createProtoBridge(bridgeUuid, "test_bridge", true, 10);
        orm.create(bridge);
        try {
            orm.create(bridge);
            fail("Should not be able to create object with in-use ID.");
        } catch (ObjectExistsException ex) {
            assertEquals(Devices.Bridge.class, ex.getClazz());
            assertEquals(bridgeUuid, ex.getId());
        }
    }

    @Test
    public void testCreateProtoBridgeWithInChains()
            throws Exception {

        Devices.Chain inChain =
                this.createProtoChain(chainUuid, "in_chain");
        orm.create(inChain);

        // Add bridge referencing an in-bound chain.
        Devices.Bridge bridge =
                this.createProtoBridge(bridgeUuid, "bridge", chainUuid, null);
        orm.create(bridge);

        Devices.Bridge bridgeOut = orm.get(Devices.Bridge.class, bridgeUuid);
        assertEquals("The retrieved proto object is equal to the original.",
                     bridge, bridgeOut);

        // Chains should have backrefs to the bridge.
        Devices.Chain in = orm.get(Devices.Chain.class, chainUuid);
        assertThat(in.getBridgeIdsList(), contains(bridgeUuid));
    }

    @Test
    public void testUpdateProtoBridge() throws Exception {
        Devices.Bridge bridge =
                this.createProtoBridge(bridgeUuid, "test_bridge", true, 10);
        orm.create(bridge);

        // Changes the tunnel key value.
        Devices.Bridge.Builder updateBldr = Devices.Bridge.newBuilder(bridge);
        updateBldr.setTunnelKey(20);
        Devices.Bridge updatedBridge = updateBldr.build();

        // Update the bridge data in ZooKeeper.
        orm.update(updatedBridge);

        Devices.Bridge retrieved = orm.get(Devices.Bridge.class, bridgeUuid);
        assertEquals("The retrieved proto object is equal to the updated "
                     + "bridge.",
                     updatedBridge, retrieved);
    }

    @Test
    public void testUpdateProtoBridgeWithInChain() throws Exception {
        Devices.Bridge bridge =
                this.createProtoBridge(bridgeUuid, "test_bridge", true, 10);
        orm.create(bridge);

        Devices.Chain inChain =
                this.createProtoChain(chainUuid, "in_chain");
        orm.create(inChain);

        // Update the bridge with an in-bound chain.
        Devices.Bridge updatedBridge =
                bridge.toBuilder().setInboundFilterId(chainUuid).build();
        orm.update(updatedBridge);

        Devices.Bridge bridgeOut = orm.get(Devices.Bridge.class, bridgeUuid);
        assertEquals("The retrieved bridge is updated with the chain.",
                     chainUuid, bridgeOut.getInboundFilterId());

        // Chains should have back refs to the bridge.
        Devices.Chain in = orm.get(Devices.Chain.class, chainUuid);
        assertThat(in.getBridgeIdsList(), contains(bridgeUuid));
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
    public void testUpdateProtoBridgeWithNonExistingId() throws Exception {
        Devices.Bridge bridge =
                this.createProtoBridge(bridgeUuid, "test_bridge", true, 10);
        try {
            orm.update(bridge);
            fail("Should not be able to update nonexisting item.");
        } catch (NotFoundException ex) {
            assertEquals(Devices.Bridge.class, ex.getClazz());
            assertEquals(bridgeUuid, ex.getId());
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
            assertEquals("chainId", ex.getReferencingFieldName());
            assertEquals(Chain.class.getSimpleName(), ex.getReferencedClass());
            assertEquals(chain1.id.toString(), ex.getReferencedId());
        }
    }

    @Test
    public void testDeleteProtoBridge() throws Exception {
        Devices.Bridge bridge =
                this.createProtoBridge(bridgeUuid, "test_bridge", true, 10);
        // Persists a bridge in ZooKeeper
        orm.create(bridge);

        // Delete the bridge data in ZooKeeper.
        orm.delete(Devices.Bridge.class, bridgeUuid);

        // Get on the bridge should throw a NotFoundException.
        try {
            orm.get(Devices.Bridge.class, bridgeUuid);
            fail("The deleted bridge is returned.");
        } catch (NotFoundException nfe) {
            // The bridge has been properly deleted.
        }
    }

    @Test
    public void testDeleteProtoBridgeWithInChain() throws Exception {
        Devices.Chain inChain =
                this.createProtoChain(chainUuid, "in_chain");
        orm.create(inChain);

        // Add bridge referencing an in-bound chain.
        Devices.Bridge bridge =
                this.createProtoBridge(bridgeUuid, "bridge", chainUuid, null);
        orm.create(bridge);

        orm.delete(Devices.Bridge.class, bridgeUuid);
        // Get on the bridge should throw a NotFoundException.
        try {
            orm.get(Devices.Bridge.class, bridgeUuid);
            fail("The deleted bridge is returned.");
        } catch (NotFoundException nfe) {
            // The bridge has been properly deleted.
        }

        // Chains should not have the backrefs to the bridge.
        Devices.Chain in = orm.get(Devices.Chain.class, chainUuid);
        assertTrue(in.getBridgeIdsList().isEmpty());
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

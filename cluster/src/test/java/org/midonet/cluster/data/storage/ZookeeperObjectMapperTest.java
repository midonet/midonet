/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.storage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import scala.None$;

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

import rx.Observable;
import rx.Observer;

import org.midonet.cluster.data.storage.FieldBinding.DeleteAction;
import org.midonet.cluster.models.Commons;
import org.midonet.cluster.models.Devices;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ZookeeperObjectMapperTest {

    protected static class PojoBridge {
        public UUID id;
        public String name;
        public UUID inChainId;
        public UUID outChainId;
        public List<UUID> portIds;

        PojoBridge() {}

        PojoBridge(UUID id, String name, UUID inChainId, UUID outChainId) {
            this.id = id;
            this.name = name;
            this.inChainId = inChainId;
            this.outChainId = outChainId;
        }

        PojoBridge(String name, UUID inChainId, UUID outChainId) {
            this(UUID.randomUUID(), name, inChainId, outChainId);
        }
    }

    protected static class PojoRouter {
        public UUID id;
        public String name;
        public UUID inChainId;
        public UUID outChainId;
        public List<UUID> portIds;

        PojoRouter() {}

        PojoRouter(UUID id, String name, UUID inChainId, UUID outChainId) {
            this.id = id;
            this.name = name;
            this.inChainId = inChainId;
            this.outChainId = outChainId;
        }

        PojoRouter(String name, UUID inChainId, UUID outChainId) {
            this(UUID.randomUUID(), name, inChainId, outChainId);
        }
    }

    protected static class PojoPort {
        public UUID id;
        public String name;
        public UUID peerId;
        public UUID bridgeId;
        public UUID routerId;
        public UUID inChainId;
        public UUID outChainId;
        public List<UUID> ruleIds;

        PojoPort() {}

        PojoPort(String name, UUID bridgeId, UUID routerId) {
            this(name, bridgeId, routerId, null, null, null);
        }

        PojoPort(String name, UUID bridgeId, UUID routerId,
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

    protected static class PojoChain {
        public UUID id;
        public String name;
        public List<UUID> ruleIds;
        public List<UUID> bridgeIds;
        public List<UUID> routerIds;
        public List<UUID> portIds;

        PojoChain() {}

        public PojoChain(String name) {
            this.id = UUID.randomUUID();
            this.name = name;
        }
    }

    protected static class PojoRule {
        public UUID id;
        public UUID chainId;
        public String name;
        public List<UUID> portIds;
        public List<String> strings; // Needed for a declareBinding() test.

        PojoRule() {}

        public PojoRule(String name, UUID chainId, UUID... portIds) {
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

    private ZookeeperObjectMapper zom;
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
        client.start();
        zom = new ZookeeperObjectMapper(zkRootDir, client);

        // Clear ZK data from last test.
        try {
            client.delete().deletingChildrenIfNeeded().forPath(zkRootDir);
        } catch (KeeperException.NoNodeException ex) {
            // Won't exist for first test.
        }

        for (Class<?> clazz : new Class<?>[]{
            PojoBridge.class, PojoRouter.class, PojoPort.class, PojoChain.class,
            PojoRule.class, Devices.Bridge.class, Devices.Chain.class}) {
            zom.registerClass(clazz);
        }

        this.initBindings();
    }

    /* Initializes Bindings for ZOM. */
    private void initBindings() {
        // Bindings for POJOs.
        zom.declareBinding(PojoBridge.class, "inChainId", DeleteAction.CLEAR,
                           PojoChain.class, "bridgeIds", DeleteAction.CLEAR);
        zom.declareBinding(PojoBridge.class, "outChainId", DeleteAction.CLEAR,
                           PojoChain.class, "bridgeIds", DeleteAction.CLEAR);

        zom.declareBinding(PojoRouter.class, "inChainId", DeleteAction.CLEAR,
                           PojoChain.class, "routerIds", DeleteAction.CLEAR);
        zom.declareBinding(PojoRouter.class, "outChainId", DeleteAction.CLEAR,
                           PojoChain.class, "routerIds", DeleteAction.CLEAR);

        zom.declareBinding(PojoPort.class, "bridgeId", DeleteAction.CLEAR,
                           PojoBridge.class, "portIds", DeleteAction.ERROR);
        zom.declareBinding(PojoPort.class, "routerId", DeleteAction.CLEAR,
                           PojoRouter.class, "portIds", DeleteAction.ERROR);
        zom.declareBinding(PojoPort.class, "inChainId", DeleteAction.CLEAR,
                           PojoChain.class, "portIds", DeleteAction.CLEAR);
        zom.declareBinding(PojoPort.class, "outChainId", DeleteAction.CLEAR,
                           PojoChain.class, "portIds", DeleteAction.CLEAR);
        zom.declareBinding(PojoPort.class, "peerId", DeleteAction.CLEAR,
                           PojoPort.class, "peerId", DeleteAction.CLEAR);

        zom.declareBinding(PojoChain.class, "ruleIds", DeleteAction.CASCADE,
                           PojoRule.class, "chainId", DeleteAction.CLEAR);

        zom.declareBinding(PojoRule.class, "portIds", DeleteAction.CLEAR,
                           PojoPort.class, "ruleIds", DeleteAction.CLEAR);

        // Bindings for proto-backed objects.
        zom.declareBinding(
            Devices.Bridge.class, "inbound_filter_id", DeleteAction.CLEAR,
            Devices.Chain.class, "bridge_ids", DeleteAction.CLEAR);
        zom.declareBinding(
            Devices.Bridge.class, "outbound_filter_id", DeleteAction.CLEAR,
            Devices.Chain.class, "bridge_ids", DeleteAction.CLEAR);
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
        PojoChain chain1 = new PojoChain("chain1");
        PojoChain chain2 = new PojoChain("chain2");
        createObjects(chain1, chain2);

        // Add bridge referencing the two chains.
        PojoBridge bridge = new PojoBridge("bridge1", chain1.id, chain2.id);
        zom.create(bridge);

        // Chains should have backrefs to the bridge.
        assertThat(zom.get(PojoChain.class, chain1.id).bridgeIds,
                contains(bridge.id));
        assertThat(zom.get(PojoChain.class, chain2.id).bridgeIds,
                contains(bridge.id));

        // Add a router referencing chain1 twice.
        PojoRouter router = new PojoRouter("router1", chain1.id, chain1.id);
        zom.create(router);

        // Chain1 should have two references to the router.
        assertThat(zom.get(PojoChain.class, chain1.id).routerIds,
                   contains(router.id, router.id));

        // Add two ports each to bridge and router, linking two of them.
        PojoPort bPort1 = new PojoPort("bridge-port1", bridge.id, null);
        PojoPort bPort2 = new PojoPort("bridge-port2", bridge.id, null);
        PojoPort rPort1 = new PojoPort("router-port1", null, router.id);
        PojoPort rPort2 = new PojoPort("router-port2", null, router.id,
                               bPort2.id, null, null);
        createObjects(bPort1, bPort2, rPort1, rPort2);

        // The ports' IDs should show up in their parents' portIds lists,
        // and bPort2 should have its peerId set.
        assertThat(zom.get(PojoBridge.class, bridge.id).portIds,
                   contains(bPort1.id, bPort2.id));
        assertThat(zom.get(PojoRouter.class, router.id).portIds,
                   contains(rPort1.id, rPort2.id));
        assertEquals(rPort2.id, zom.get(PojoPort.class, bPort2.id).peerId);

        // Should not be able to link bPort1 to rPort2 because rPort2 is
        // already linked.
        bPort1.peerId = rPort2.id;
        try {
            zom.update(bPort1);
            fail("Linking to an already-linked port should not be allowed.");
        } catch (ReferenceConflictException ex) {/* Expected */}

        // Link bPort1 and rPort1 with an update.
        bPort1.peerId = rPort1.id;
        zom.update(bPort1);
        assertEquals(bPort1.id, zom.get(PojoPort.class, rPort1.id).peerId);

        // Add some rules to the chains.
        PojoRule c1Rule1 = new PojoRule("chain1-rule1", chain1.id,
                                bPort1.id, bPort2.id);
        PojoRule c1Rule2 = new PojoRule("chain1-rule2", chain1.id,
                                bPort2.id, rPort1.id);
        PojoRule c2Rule1 = new PojoRule("chain2-rule1", chain2.id,
                                rPort1.id, bPort1.id);
        createObjects(c1Rule1, c1Rule2, c2Rule1);

        assertThat(zom.get(PojoChain.class, chain1.id).ruleIds,
                contains(c1Rule1.id, c1Rule2.id));
        assertThat(zom.get(PojoChain.class, chain2.id).ruleIds,
                contains(c2Rule1.id));

        assertPortsRuleIds(bPort1, c1Rule1.id, c2Rule1.id);
        assertPortsRuleIds(bPort2, c1Rule1.id, c1Rule2.id);
        assertPortsRuleIds(rPort1, c1Rule2.id, c2Rule1.id);

        // Try some updates on c2Rule1's ports.
        c2Rule1.portIds = Arrays.asList(bPort2.id, rPort1.id, rPort2.id);
        zom.update(c2Rule1);
        assertPortsRuleIds(bPort1, c1Rule1.id);
        assertPortsRuleIds(bPort2, c1Rule1.id, c1Rule2.id, c2Rule1.id);
        assertPortsRuleIds(rPort1, c1Rule2.id, c2Rule1.id);
        assertPortsRuleIds(rPort2, c2Rule1.id);

        c2Rule1.portIds = Arrays.asList(rPort1.id, bPort1.id);
        zom.update(c2Rule1);
        assertPortsRuleIds(bPort1, c1Rule1.id, c2Rule1.id);
        assertPortsRuleIds(bPort2, c1Rule1.id, c1Rule2.id);
        assertPortsRuleIds(rPort1, c1Rule2.id, c2Rule1.id);
        assertThat(zom.get(PojoPort.class, rPort2.id).ruleIds, empty());

        // Should not be able to delete the bridge while it has ports.
        try {
            zom.delete(PojoBridge.class, bridge.id);
            fail("Delete should fail while bridge has ports.");
        } catch (ObjectReferencedException ex) { /* Expected */
        }

        // Delete a bridge port and verify that references to it are cleared.
        zom.delete(PojoPort.class, bPort1.id);
        assertFalse(zom.exists(PojoPort.class, bPort1.id));
        assertThat(zom.get(PojoBridge.class, bridge.id).portIds,
                contains(bPort2.id));
        assertThat(zom.get(PojoRule.class, c1Rule1.id).portIds,
                contains(bPort2.id));
        assertThat(zom.get(PojoRule.class, c2Rule1.id).portIds,
                contains(rPort1.id));

        // Delete the other bridge port.
        zom.delete(PojoPort.class, bPort2.id);
        assertFalse(zom.exists(PojoPort.class, bPort2.id));
        assertThat(zom.get(PojoBridge.class, bridge.id).portIds, empty());
        assertNull(zom.get(PojoPort.class, rPort2.id).peerId);
        assertThat(zom.get(PojoRule.class, c1Rule1.id).portIds, empty());
        assertThat(zom.get(PojoRule.class, c1Rule2.id).portIds,
                contains(rPort1.id));

        // Delete the bridge and verify references to it are cleared.
        zom.delete(PojoBridge.class, bridge.id);
        assertThat(zom.get(PojoChain.class, chain1.id).bridgeIds, empty());
        assertThat(zom.get(PojoChain.class, chain2.id).bridgeIds, empty());

        // Delete a chain and verify that the delete cascades to rules.
        zom.delete(PojoChain.class, chain1.id);
        assertFalse(zom.exists(PojoChain.class, chain1.id));
        assertFalse(zom.exists(PojoRule.class, c1Rule1.id));
        assertFalse(zom.exists(PojoRule.class, c1Rule2.id));

        // Additionally, the cascading delete of c1Rule2 should have cleared
        // rPort1's reference to it.
        assertPortsRuleIds(rPort1, c2Rule1.id);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRegisterClassWithNoIdField() {
        zom.registerClass(NoIdField.class);
    }

    @Test(expected = IllegalStateException.class)
    public void testRegisterDuplicateClass() {
        zom.registerClass(PojoBridge.class);
    }

    @Test(expected = AssertionError.class)
    public void testBindUnregisteredClass() {
        zom.declareBinding(
            Devices.Router.class, "inbound_filter_id", DeleteAction.CLEAR,
            Devices.Chain.class, "router_ids", DeleteAction.CLEAR);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testBindPojoToProtobufClass() {
        zom.declareBinding(
            PojoBridge.class, "inChainId", DeleteAction.CLEAR,
            Devices.Chain.class, "bridge_ids", DeleteAction.CLEAR);
    }

    @Test(expected = ObjectReferencedException.class)
    public void testCascadeToDeleteError() throws Exception {
        zom.clearBindings();
        zom.declareBinding(PojoBridge.class, "inChainId", DeleteAction.CASCADE,
                           PojoChain.class, "bridgeIds", DeleteAction.CLEAR);
        zom.declareBinding(PojoChain.class, "ruleIds", DeleteAction.ERROR,
                           PojoRule.class, "chainId", DeleteAction.CLEAR);

        PojoChain chain = new PojoChain("chain");
        PojoRule rule = new PojoRule("rule", chain.id);
        PojoBridge bridge = new PojoBridge("bridge", chain.id, null);
        createObjects(chain, rule, bridge);

        zom.delete(PojoBridge.class, bridge.id);
    }

    @Test
    public void testGetNonExistingItem() throws Exception {
        UUID id = UUID.randomUUID();
        try {
            zom.get(PojoBridge.class, id);
            fail("Should not be able to get non-existing object.");
        } catch (NotFoundException ex) {
            assertEquals(PojoBridge.class, ex.clazz());
            assertEquals(id, ex.id());
        }
    }

    @Test(expected = AssertionError.class)
    public void testCreateForUnregisteredClass() throws Exception {
        zom.create(Devices.Router.getDefaultInstance());
    }

    @Test
    public void testCreateWithExistingId() throws Exception {
        PojoChain chain = new PojoChain("chain");
        zom.create(chain);
        try {
            zom.create(chain);
            fail("Should not be able to create object with in-use ID.");
        } catch (ObjectExistsException ex) {
            assertEquals(PojoChain.class, ex.clazz());
            assertEquals(chain.id, ex.id());
        }
    }

    @Test
    public void testCreateWithMissingReference() throws Exception {
        PojoRule rule = new PojoRule("rule", UUID.randomUUID());
        try {
            zom.create(rule);
            fail("Should not be able to create object with reference missing.");
        } catch (NotFoundException ex) {
            assertEquals(PojoChain.class, ex.clazz());
            assertEquals(rule.chainId, ex.id());
        }
    }

    @Test
    public void testCreateProtoBridge() throws Exception {
        Devices.Bridge bridge =
                this.createProtoBridge(bridgeUuid, "test_bridge", true, 10);
        zom.create(bridge);

        Devices.Bridge bridgeOut = zom.get(Devices.Bridge.class, bridgeUuid);
        assertEquals("The retrieved proto object is equal to the original.",
                     bridge, bridgeOut);
    }

    @Test
    public void testCreateProtoBridgeWithExistingId() throws Exception {
        Devices.Bridge bridge =
                this.createProtoBridge(bridgeUuid, "test_bridge", true, 10);
        zom.create(bridge);
        try {
            zom.create(bridge);
            fail("Should not be able to create object with in-use ID.");
        } catch (ObjectExistsException ex) {
            assertEquals(Devices.Bridge.class, ex.clazz());
            assertEquals(bridgeUuid, ex.id());
        }
    }

    @Test
    public void testCreateProtoBridgeWithInChains()
            throws Exception {

        Devices.Chain inChain =
                this.createProtoChain(chainUuid, "in_chain");
        zom.create(inChain);

        // Add bridge referencing an in-bound chain.
        Devices.Bridge bridge =
                this.createProtoBridge(bridgeUuid, "bridge", chainUuid, null);
        zom.create(bridge);

        Devices.Bridge bridgeOut = zom.get(Devices.Bridge.class, bridgeUuid);
        assertEquals("The retrieved proto object is equal to the original.",
                     bridge, bridgeOut);

        // Chains should have backrefs to the bridge.
        Devices.Chain in = zom.get(Devices.Chain.class, chainUuid);
        assertThat(in.getBridgeIdsList(), contains(bridgeUuid));
    }

    @Test(expected = AssertionError.class)
    public void testUpdateForUnregisteredClass() throws Exception {
        zom.update(Devices.Router.getDefaultInstance());
    }

    @Test
    public void testUpdateProtoBridge() throws Exception {
        Devices.Bridge bridge =
                this.createProtoBridge(bridgeUuid, "test_bridge", true, 10);
        zom.create(bridge);

        // Changes the tunnel key value.
        Devices.Bridge.Builder updateBldr = Devices.Bridge.newBuilder(bridge);
        updateBldr.setTunnelKey(20);
        Devices.Bridge updatedBridge = updateBldr.build();

        // Update the bridge data in ZooKeeper.
        zom.update(updatedBridge);

        Devices.Bridge retrieved = zom.get(Devices.Bridge.class, bridgeUuid);
        assertEquals("The retrieved proto object is equal to the updated "
                     + "bridge.",
                     updatedBridge, retrieved);
    }

    @Test
    public void testUpdateProtoBridgeWithInChain() throws Exception {
        Devices.Bridge bridge =
                this.createProtoBridge(bridgeUuid, "test_bridge", true, 10);
        zom.create(bridge);

        Devices.Chain inChain =
                this.createProtoChain(chainUuid, "in_chain");
        zom.create(inChain);

        // Update the bridge with an in-bound chain.
        Devices.Bridge updatedBridge =
                bridge.toBuilder().setInboundFilterId(chainUuid).build();
        zom.update(updatedBridge);

        Devices.Bridge bridgeOut = zom.get(Devices.Bridge.class, bridgeUuid);
        assertEquals("The retrieved bridge is updated with the chain.",
                     chainUuid, bridgeOut.getInboundFilterId());

        // Chains should have back refs to the bridge.
        Devices.Chain in = zom.get(Devices.Chain.class, chainUuid);
        assertThat(in.getBridgeIdsList(), contains(bridgeUuid));
    }

    @Test
    public void testUpdateWithNonExistingId() throws Exception {
        PojoChain chain = new PojoChain("chain");
        try {
            zom.update(chain);
            fail("Should not be able to update nonexisting item.");
        } catch (NotFoundException ex) {
            assertEquals(PojoChain.class, ex.clazz());
            assertEquals(chain.id, ex.id());
        }
    }

    @Test
    public void testUpdateProtoBridgeWithNonExistingId() throws Exception {
        Devices.Bridge bridge =
                this.createProtoBridge(bridgeUuid, "test_bridge", true, 10);
        try {
            zom.update(bridge);
            fail("Should not be able to update nonexisting item.");
        } catch (NotFoundException ex) {
            assertEquals(Devices.Bridge.class, ex.clazz());
            assertEquals(bridgeUuid, ex.id());
        }
    }

    @Test
    public void testUpdateWithMissingReference() throws Exception {
        PojoRule rule = new PojoRule("rule", null);
        zom.create(rule);

        rule.chainId = UUID.randomUUID();
        try {
            zom.update(rule);
            fail("Should not be able to update with missing reference.");
        } catch (NotFoundException ex) {
            assertEquals(PojoChain.class, ex.clazz());
            assertEquals(rule.chainId, ex.id());
        }
    }

    @Test
    public void testUpdateWithReferenceConflict() throws Exception {
        PojoRule rule = new PojoRule("rule", null);
        zom.create(rule);

        PojoChain chain1 = new PojoChain("chain1");
        chain1.ruleIds = Arrays.asList(rule.id);
        zom.create(chain1);

        PojoChain chain2 = new PojoChain("chain2");
        zom.create(chain2);

        chain2.ruleIds = Arrays.asList(rule.id);
        try {
            zom.update(chain2);
            fail("Should not be able to steal rule from another chain.");
        } catch (ReferenceConflictException ex) {
            assertEquals(PojoRule.class.getSimpleName(), ex.referencingClass());
            assertEquals("chainId", ex.referencingFieldName());
            assertEquals(PojoChain.class.getSimpleName(), ex.referencedClass());
            assertEquals(chain1.id.toString(), ex.referencedId());
        }
    }

    @Test(expected = AssertionError.class)
    public void testDeleteForUnregisteredClass() throws Exception {
        zom.delete(Devices.Router.class, UUID.randomUUID());
    }

    @Test
    public void testDeleteProtoBridge() throws Exception {
        Devices.Bridge bridge =
                this.createProtoBridge(bridgeUuid, "test_bridge", true, 10);
        // Persists a bridge in ZooKeeper
        zom.create(bridge);

        // Delete the bridge data in ZooKeeper.
        zom.delete(Devices.Bridge.class, bridgeUuid);

        // Get on the bridge should throw a NotFoundException.
        try {
            zom.get(Devices.Bridge.class, bridgeUuid);
            fail("The deleted bridge is returned.");
        } catch (NotFoundException nfe) {
            // The bridge has been properly deleted.
        }
    }

    @Test
    public void testDeleteProtoBridgeWithInChain() throws Exception {
        Devices.Chain inChain =
                this.createProtoChain(chainUuid, "in_chain");
        zom.create(inChain);

        // Add bridge referencing an in-bound chain.
        Devices.Bridge bridge =
                this.createProtoBridge(bridgeUuid, "bridge", chainUuid, null);
        zom.create(bridge);

        zom.delete(Devices.Bridge.class, bridgeUuid);
        // Get on the bridge should throw a NotFoundException.
        try {
            zom.get(Devices.Bridge.class, bridgeUuid);
            fail("The deleted bridge is returned.");
        } catch (NotFoundException nfe) {
            // The bridge has been properly deleted.
        }

        // Chains should not have the backrefs to the bridge.
        Devices.Chain in = zom.get(Devices.Chain.class, chainUuid);
        assertTrue(in.getBridgeIdsList().isEmpty());
    }

    @Test
    public void testDeleteNonExistingObject() throws Exception {
        UUID id = UUID.randomUUID();
        try {
            zom.delete(PojoBridge.class, id);
        } catch (NotFoundException ex) {
            assertEquals(PojoBridge.class, ex.clazz());
            assertEquals(id, ex.id());
        }
    }

    @Test
    public void testGetAllWithEmptyResult() throws Exception {
        List<PojoChain> chains = zom.getAll(PojoChain.class);
        assertTrue(chains.isEmpty());
    }

    @Test
    public void testGetAllWithMultipleObjects() throws Exception {
        zom.getAll(PojoChain.class);
        PojoChain chain1 = new PojoChain("chain1");
        PojoChain chain2 = new PojoChain("chain2");
        zom.create(chain1);
        zom.getAll(PojoChain.class);
        zom.create(chain2);
        List<PojoChain> chains = zom.getAll(PojoChain.class);
        assertEquals(2, chains.size());
    }

    @Test
    public void testSubscriberGetsInitialValue() throws Exception {
        PojoChain chain = new PojoChain("chain");
        zom.create(chain);

        ObjectSubscription<PojoChain> sub = subscribe(PojoChain.class, chain.id);
        assertEquals(1, sub.updates);
        assertNotNull(sub.t);
        assertEquals(chain.name, sub.t.name);
    }

    @Test
    public void testSubscriberGetsUpdates() throws Exception {
        PojoChain chain = new PojoChain("chain");
        zom.create(chain);
        ObjectSubscription<PojoChain> sub = subscribe(PojoChain.class, chain.id);

        chain.name = "renamedChain";
        updateAndWait(chain, sub);
        assertEquals(2, sub.updates);
        assertEquals(chain.name, sub.t.name);
    }

    @Test
    public void testSubscriberGetsDelete() throws Exception {
        PojoChain chain = new PojoChain("chain");
        zom.create(chain);
        ObjectSubscription<PojoChain> sub = subscribe(PojoChain.class, chain.id);
        deleteAndWait(PojoChain.class, chain.id, sub);
        assertNull(sub.t);
    }

    @Test
    public void testSubscribeToNonexistentObject() throws Throwable {
        UUID id = UUID.randomUUID();
        ObjectSubscription<PojoChain> sub = subscribe(PojoChain.class, id);
        Thread.sleep(100);
        assertThat(sub.ex, instanceOf(NotFoundException.class));
        NotFoundException ex = (NotFoundException)sub.ex;
        assertEquals(ex.clazz(), PojoChain.class);
        assertEquals(None$.MODULE$, ex.id());
    }

    @Test
    public void testSecondSubscriberGetsLatestVersion() throws Exception {
        PojoChain chain = new PojoChain("chain");
        zom.create(chain);
        ObjectSubscription<PojoChain> sub1 = subscribe(PojoChain.class, chain.id);

        chain.name = "renamedChain";
        updateAndWait(chain, sub1);

        ObjectSubscription<PojoChain> sub2 = subscribe(PojoChain.class, chain.id);
        assertEquals(1, sub2.updates);
        assertNotNull(sub2.t);
        assertEquals("renamedChain", sub2.t.name);
    }

    @Test
    public void testClassSubscriberGetsCurrentList() throws Exception {
        PojoChain chain1 = new PojoChain("chain1");
        PojoChain chain2 = new PojoChain("chain2");
        createObjects(chain1, chain2);
        ClassSubscription<PojoChain> sub = subscribe(PojoChain.class);
        Thread.sleep(100);
        assertEquals(2, sub.subs.size());
    }

    @Test
    public void testClassSubscriberGetsNewObject() throws Exception {
        PojoChain chain1 = new PojoChain("chain1");
        zom.create(chain1);
        ClassSubscription<PojoChain> sub = subscribe(PojoChain.class);
        Thread.sleep(100);
        assertEquals(1, sub.subs.size());
        assertEquals("chain1", sub.subs.get(0).t.name);

        PojoChain chain2 = new PojoChain("chain2");
        createAndWait(chain2, sub);
        assertEquals(2, sub.subs.size());
    }

    @Test
    public void testSecondClassSubscriberGetsCurrentList() throws Exception {
        PojoChain chain1 = new PojoChain("chain1");
        zom.create(chain1);
        ClassSubscription<PojoChain> sub = subscribe(PojoChain.class);
        Thread.sleep(100);
        assertEquals(1, sub.subs.size());

        PojoChain chain2 = new PojoChain("chain2");
        createAndWait(chain2, sub);
        ClassSubscription<PojoChain> sub2 = subscribe(PojoChain.class);
        assertEquals(2, sub2.subs.size());
    }

    @Test
    public void testClassObservableIgnoresDeletedInstances() throws Exception {
        ClassSubscription<PojoChain> sub1 = subscribe(PojoChain.class);
        PojoChain chain1 = new PojoChain("chain1");
        PojoChain chain2 = new PojoChain("chain2");
        createAndWait(chain1, sub1);
        createAndWait(chain2, sub1);
        assertEquals(2, sub1.subs.size());

        ObjectSubscription<PojoChain> chain1Sub =
            (sub1.subs.get(0).t.id.equals(chain1.id)) ?
            sub1.subs.get(0) : sub1.subs.get(1);
        deleteAndWait(PojoChain.class, chain1.id, chain1Sub);
        assertNull(chain1Sub.t);

        ClassSubscription<PojoChain> sub2 = subscribe(PojoChain.class);
        assertEquals(1, sub2.subs.size());
        assertEquals("chain2", sub2.subs.get(0).t.name);
    }

    private <T> ObjectSubscription<T> subscribe(
        Class<T> clazz, Object id) throws Exception{

        ObjectSubscription<T> sub = new ObjectSubscription<>();
        zom.subscribe(clazz, id, sub);
        return sub;
    }

    private <T> ClassSubscription<T> subscribe(Class<T> clazz) {
        ClassSubscription<T> sub = new ClassSubscription<>();
        zom.subscribeAll(clazz, sub);
        return sub;
    }

    private class ObjectSubscription<T> implements Observer<T> {

        public int updates = 0;
        public T t = null;
        public Throwable ex = null;

        @Override
        public synchronized void onCompleted() {
            t = null;
            this.notifyAll();
        }

        @Override
        public synchronized void onError(Throwable e) {
            ex = e;
            this.notifyAll();
        }

        @Override
        public synchronized void onNext(T t) {
            updates++;
            this.t = t;
            this.notifyAll();
        }
    }

    private class ClassSubscription<T> implements Observer<Observable<T>> {

        List<ObjectSubscription<T>> subs = new ArrayList<>();

        @Override
        public synchronized void onCompleted() {
            fail("Class subscription should not complete.");
        }

        @Override
        public synchronized void onError(Throwable e) {
            throw new RuntimeException(
                "Got exception from class subscription", e);
        }

        @Override
        public synchronized void onNext(Observable<T> tObservable) {
            ObjectSubscription<T> sub = new ObjectSubscription<>();
            tObservable.subscribe(sub);
            subs.add(sub);
            this.notifyAll();
        }
    }

    private void createAndWait(Object o, Object lock) throws Exception {
        synchronized (lock) {
            zom.create(o);
            lock.wait();
        }
    }

    private void updateAndWait(Object o, Object lock) throws Exception {
        synchronized (lock) {
            zom.update(o);
            lock.wait();
        }
    }

    private void deleteAndWait(Class<?> clazz, Object id, Object lock)
        throws Exception {
        synchronized (lock) {
            zom.delete(clazz, id);
            lock.wait();
        }
    }

    private void assertPortsRuleIds(PojoPort port, UUID... ruleIds)
            throws Exception {
        assertThat(zom.get(PojoPort.class, port.id).ruleIds, contains(ruleIds));
    }

    private void createObjects(Object... objects) throws Exception {
        for (Object object : objects) {
            zom.create(object);
        }
    }
}

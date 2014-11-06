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
package org.midonet.cluster.data.storage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import scala.None$;
import scala.collection.Iterator;
import scala.collection.Seq;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import scala.util.Failure;
import scala.util.Try;

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
import static org.midonet.cluster.models.Topology.Chain;
import static org.midonet.cluster.models.Topology.Network;
import static org.midonet.cluster.models.Topology.Router;
import static scala.concurrent.Await.ready;

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

    private static final int ZK_PORT = 12181; // Avoid conflicting with real ZK.
    private static final String ZK_CONNECTION_STRING = "127.0.0.1:" + ZK_PORT;
    private static final String ZK_ROOT_DIR = "/zkomtest";

    private static final Commons.UUID NETWORK_UUID = createRandomUuidproto();
    private static final Commons.UUID CHAIN_UUID = createRandomUuidproto();
    private static final String NETWORK_NAME = "test_network";

    private static Commons.UUID createRandomUuidproto() {
        UUID uuid = UUID.randomUUID();
        return Commons.UUID.newBuilder().setMsb(uuid.getMostSignificantBits())
                                        .setLsb(uuid.getLeastSignificantBits())
                                        .build();
    }

    public static <T> T sync(Future<T> f) throws Exception {
        ready(f, Duration.create(10, TimeUnit.SECONDS));
        Try<T> tryValue = f.value().get();
        if (tryValue.isFailure()) {
            Throwable problem = ((Failure<T>)tryValue).exception();
            if (problem instanceof Exception) {
                throw (Exception)problem;
            } else {
                throw new RuntimeException(problem);
            }
        } else {
            return tryValue.get();
        }
    }

    private static <T> List<T> syncAll(Seq<Future<T>> fs) throws Exception {
        List<T> _fs = new ArrayList<>(fs.size());
        Iterator<Future<T>> it = fs.iterator();
        while(it.hasNext()) {
            _fs.add(sync(it.next()));
        }
        return _fs;
    }

    private static <T> List<T> syncAll(Future<Seq<Future<T>>> f)
            throws Exception {
        return syncAll(sync(f));
    }

    @BeforeClass
    public static void classSetup() throws Exception {
        testingServer = new TestingServer(ZK_PORT);
    }

    @AfterClass
    public static void classTeardown() throws Exception {
        testingServer.close();
    }

    @Before
    public void setup() throws Exception {
        zom = createZoom();

        for (Class<?> clazz : new Class<?>[]{
            PojoBridge.class, PojoRouter.class, PojoPort.class, PojoChain.class,
            PojoRule.class, Network.class, Chain.class}) {
            zom.registerClass(clazz);
        }

        this.initBindings();

        zom.build();
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
            Network.class, "inbound_filter_id", DeleteAction.CLEAR,
            Chain.class, "network_ids", DeleteAction.CLEAR);
        zom.declareBinding(
            Network.class, "outbound_filter_id", DeleteAction.CLEAR,
            Chain.class, "network_ids", DeleteAction.CLEAR);
    }

    private ZookeeperObjectMapper createZoom() throws Exception {
        RetryPolicy retryPolicy = new ExponentialBackoffRetry(1000, 3);
        CuratorFramework client =
            CuratorFrameworkFactory.newClient(ZK_CONNECTION_STRING, retryPolicy);
        client.start();

        // Clear ZK data from last test.
        try {
            client.delete().deletingChildrenIfNeeded().forPath(ZK_ROOT_DIR);
        } catch (KeeperException.NoNodeException ex) {
            // Won't exist for first test.
        }

        return new ZookeeperObjectMapper(ZK_ROOT_DIR, client);
    }

    /* A helper method for creating a proto Network. */
    private Network createProtoNetwork(Commons.UUID networkId,
                                       String name,
                                       boolean adminStateUp,
                                       int tunnelKey,
                                       Commons.UUID inFilterId,
                                       Commons.UUID outFilterId,
                                       Commons.UUID vxLanPortId) {
        Network.Builder networkBuilder = Network.newBuilder();
        networkBuilder.setId(networkId);
        networkBuilder.setName(name);
        networkBuilder.setAdminStateUp(adminStateUp);
        networkBuilder.setTunnelKey(tunnelKey);

        if (inFilterId != null)
            networkBuilder.setInboundFilterId(inFilterId);
        if (outFilterId != null)
            networkBuilder.setOutboundFilterId(outFilterId);
        if (vxLanPortId != null)
            networkBuilder.setVxlanPortId(vxLanPortId);

        return networkBuilder.build();
    }

    /* A helper method for creating a proto Network. */
    private Network createProtoNetwork(Commons.UUID networkId,
                                       String name,
                                       boolean adminStateUp,
                                       int tunnelKey) {
        return this.createProtoNetwork(networkId, name, adminStateUp, tunnelKey,
                                       null, null, null);
    }

    /* A helper method for creating a proto Network. */
    private Network createProtoNetwork(Commons.UUID networkId,
                                       String name,
                                       Commons.UUID inFilterId,
                                       Commons.UUID outFilterId) {
        return this.createProtoNetwork(networkId,
                                       name,
                                       true,  // Admin state default true
                                       -1,    // fake tunnel key
                                       inFilterId,
                                       outFilterId,
                                       null);  // A fake VxLanPort ID.
    }

    /* A helper method for creating a proto Chain. */
    private Chain createProtoChain(Commons.UUID chainId, String name) {
        Chain.Builder chainBuilder = Chain.newBuilder();
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
        assertThat(sync(zom.get(PojoChain.class, chain1.id)).bridgeIds,
                   contains(bridge.id));
        assertThat(sync(zom.get(PojoChain.class, chain2.id)).bridgeIds,
                   contains(bridge.id));

        // Add a router referencing chain1 twice.
        PojoRouter router = new PojoRouter("router1", chain1.id, chain1.id);
        zom.create(router);

        // Chain1 should have two references to the router.
        assertThat(sync(zom.get(PojoChain.class, chain1.id)).routerIds,
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
        assertThat(sync(zom.get(PojoBridge.class, bridge.id)).portIds,
                   contains(bPort1.id, bPort2.id));
        assertThat(sync(zom.get(PojoRouter.class, router.id)).portIds,
                   contains(rPort1.id, rPort2.id));
        assertEquals(rPort2.id,
                     sync(zom.get(PojoPort.class, bPort2.id)).peerId);

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
        assertEquals(bPort1.id,
                     sync(zom.get(PojoPort.class, rPort1.id)).peerId);

        // Add some rules to the chains.
        PojoRule c1Rule1 = new PojoRule("chain1-rule1", chain1.id,
                                bPort1.id, bPort2.id);
        PojoRule c1Rule2 = new PojoRule("chain1-rule2", chain1.id,
                                bPort2.id, rPort1.id);
        PojoRule c2Rule1 = new PojoRule("chain2-rule1", chain2.id,
                                rPort1.id, bPort1.id);
        createObjects(c1Rule1, c1Rule2, c2Rule1);

        assertThat(sync(zom.get(PojoChain.class, chain1.id)).ruleIds,
                   contains(c1Rule1.id, c1Rule2.id));
        assertThat(sync(zom.get(PojoChain.class, chain2.id)).ruleIds,
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
        assertThat(sync(zom.get(PojoPort.class, rPort2.id)).ruleIds, empty());

        // Should not be able to delete the bridge while it has ports.
        try {
            zom.delete(PojoBridge.class, bridge.id);
            fail("Delete should fail while bridge has ports.");
        } catch (ObjectReferencedException ex) { /* Expected */
        }

        // Delete a bridge port and verify that references to it are cleared.
        zom.delete(PojoPort.class, bPort1.id);
        assertFalse((Boolean)sync(zom.exists(PojoPort.class, bPort1.id)));
        assertThat(sync(zom.get(PojoBridge.class, bridge.id)).portIds,
                contains(bPort2.id));
        assertThat(sync(zom.get(PojoRule.class, c1Rule1.id)).portIds,
                contains(bPort2.id));
        assertThat(sync(zom.get(PojoRule.class, c2Rule1.id)).portIds,
                contains(rPort1.id));

        // Delete the other bridge port.
        zom.delete(PojoPort.class, bPort2.id);
        assertFalse((Boolean)sync(zom.exists(PojoPort.class, bPort2.id)));
        assertThat(sync(zom.get(PojoBridge.class, bridge.id)).portIds, empty());
        assertNull(sync(zom.get(PojoPort.class, rPort2.id)).peerId);
        assertThat(sync(zom.get(PojoRule.class, c1Rule1.id)).portIds, empty());
        assertThat(sync(zom.get(PojoRule.class, c1Rule2.id)).portIds,
                   contains(rPort1.id));

        // Delete the bridge and verify references to it are cleared.
        zom.delete(PojoBridge.class, bridge.id);
        assertThat(sync(zom.get(PojoChain.class, chain1.id)).bridgeIds,
                   empty());
        assertThat(sync(zom.get(PojoChain.class, chain2.id)).bridgeIds,
                   empty());

        // Delete a chain and verify that the delete cascades to rules.
        zom.delete(PojoChain.class, chain1.id);
        assertFalse((Boolean)sync(zom.exists(PojoChain.class, chain1.id)));
        assertFalse((Boolean)sync(zom.exists(PojoRule.class, c1Rule1.id)));
        assertFalse((Boolean)sync(zom.exists(PojoRule.class, c1Rule2.id)));

        // Additionally, the cascading delete of c1Rule2 should have cleared
        // rPort1's reference to it.
        assertPortsRuleIds(rPort1, c2Rule1.id);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRegisterClassWithNoIdField() throws Exception {
        ZookeeperObjectMapper zom2 = createZoom();
        zom2.registerClass(NoIdField.class);
    }

    @Test
    public void testRegisterDuplicateClass() throws Exception {
        ZookeeperObjectMapper zom2 = createZoom();
        zom2.registerClass(PojoBridge.class);
        try {
            zom2.registerClass(PojoBridge.class);
            fail("Duplicate class registration succeeded.");
        } catch (IllegalStateException ex) {
            // Expected.
        }
    }

    @Test(expected = AssertionError.class)
    public void testBindUnregisteredClass() throws Exception {
        ZookeeperObjectMapper zom2 = createZoom();
        zom2.registerClass(Router.class);
        zom2.declareBinding(
            Router.class, "inbound_filter_id", DeleteAction.CLEAR,
            Chain.class, "router_ids", DeleteAction.CLEAR);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testBindPojoToProtobufClass() throws Exception {
        ZookeeperObjectMapper zom2 = createZoom();
        zom2.registerClass(PojoBridge.class);
        zom2.registerClass(Chain.class);
        zom2.declareBinding(
            PojoBridge.class, "inChainId", DeleteAction.CLEAR,
            Chain.class, "bridge_ids", DeleteAction.CLEAR);
    }

    @Test(expected = ObjectReferencedException.class)
    public void testCascadeToDeleteError() throws Exception {
        ZookeeperObjectMapper zom2 = createZoom();
        zom2.registerClass(PojoBridge.class);
        zom2.registerClass(PojoChain.class);
        zom2.registerClass(PojoRule.class);
        zom2.declareBinding(PojoBridge.class, "inChainId", DeleteAction.CASCADE,
                           PojoChain.class, "bridgeIds", DeleteAction.CLEAR);
        zom2.declareBinding(PojoChain.class, "ruleIds", DeleteAction.ERROR,
                           PojoRule.class, "chainId", DeleteAction.CLEAR);
        zom2.build();

        PojoChain chain = new PojoChain("chain");
        PojoRule rule = new PojoRule("rule", chain.id);
        PojoBridge bridge = new PojoBridge("bridge", chain.id, null);
        createObjects(chain, rule, bridge);

        zom2.delete(PojoBridge.class, bridge.id);
    }

    @Test
    public void testGetNonExistingItem() throws Exception {
        UUID id = UUID.randomUUID();
        try {
            sync(zom.get(PojoBridge.class, id));
            fail("Should not be able to get non-existing object.");
        } catch (NotFoundException ex) {
            assertEquals(PojoBridge.class, ex.clazz());
            assertEquals(id, ex.id());
        }
    }

    @Test(expected = AssertionError.class)
    public void testCreateForUnregisteredClass() throws Exception {
        zom.create(Router.getDefaultInstance());
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
    public void testCreateProtoNetwork() throws Exception {
        Network network = this.createProtoNetwork(
                NETWORK_UUID, NETWORK_NAME, true, 10);
        zom.create(network);

        Network networkOut = sync(zom.get(Network.class, NETWORK_UUID));
        assertEquals("The retrieved proto object is equal to the original.",
                     network, networkOut);
    }

    @Test
    public void testCreateProtoNetworkWithExistingId() throws Exception {
        Network network = this.createProtoNetwork(
                NETWORK_UUID, NETWORK_NAME, true, 10);
        zom.create(network);
        try {
            zom.create(network);
            fail("Should not be able to create object with in-use ID.");
        } catch (ObjectExistsException ex) {
            assertEquals(Network.class, ex.clazz());
            assertEquals(NETWORK_UUID, ex.id());
        }
    }

    @Test
    public void testCreateProtoNetworkWithInChains()
            throws Exception {
        Chain inChain = this.createProtoChain(CHAIN_UUID, "in_chain");
        zom.create(inChain);

        // Add a network referencing an in-bound chain.
        Network network = this.createProtoNetwork(
                NETWORK_UUID, "bridge", CHAIN_UUID, null);
        zom.create(network);

        Network networkOut = sync(zom.get(Network.class, NETWORK_UUID));
        assertEquals("The retrieved proto object is equal to the original.",
                     network, networkOut);

        // Chains should have backrefs to the network.
        Chain in = sync(zom.get(Chain.class, CHAIN_UUID));
        assertThat(in.getNetworkIdsList(), contains(NETWORK_UUID));
    }

    @Test(expected = AssertionError.class)
    public void testUpdateForUnregisteredClass() throws Exception {
        zom.update(Router.getDefaultInstance());
    }

    @Test
    public void testUpdateProtoNetwork() throws Exception {
        Network network = this.createProtoNetwork(
                NETWORK_UUID, NETWORK_NAME, true, 10);
        zom.create(network);

        // Changes the tunnel key value.
        Network.Builder updateBldr = Network.newBuilder(network);
        updateBldr.setTunnelKey(20);
        Network updatedNetwork = updateBldr.build();

        // Update the network data in ZooKeeper.
        zom.update(updatedNetwork);

        Network retrieved = sync(zom.get(Network.class, NETWORK_UUID));
        assertEquals("The retrieved proto object is equal to the updated "
                     + "network.",
                     updatedNetwork, retrieved);
    }

    @Test
    public void testUpdateProtoNetworkWithInChain() throws Exception {
        Network network = this.createProtoNetwork(
                NETWORK_UUID, NETWORK_NAME, true, 10);
        zom.create(network);

        Chain inChain = this.createProtoChain(CHAIN_UUID, "in_chain");
        zom.create(inChain);

        // make sure they are created
        sync(zom.get(Chain.class, inChain.getId()));

        // Update the network with an in-bound chain.
        Network updatedNetwork =
                network.toBuilder().setInboundFilterId(CHAIN_UUID).build();
        zom.update(updatedNetwork);

        Network networkOut = sync(zom.get(Network.class, NETWORK_UUID));
        assertEquals("The retrieved network is updated with the chain.",
                     CHAIN_UUID, networkOut.getInboundFilterId());

        // Chains should have back refs to the network.
        Chain in = sync(zom.get(Chain.class, CHAIN_UUID));
        assertThat(in.getNetworkIdsList(), contains(NETWORK_UUID));
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
    public void testUpdateProtoNetworkWithNonExistingId() throws Exception {
        Network network = this.createProtoNetwork(
                NETWORK_UUID, NETWORK_NAME, true, 10);
        try {
            zom.update(network);
            fail("Should not be able to update nonexisting item.");
        } catch (NotFoundException ex) {
            assertEquals(Network.class, ex.clazz());
            assertEquals(NETWORK_UUID, ex.id());
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

    @Test(expected = IllegalStateException.class)
    public void testUpdateWithValidatorError() throws Exception {
        PojoRule rule = new PojoRule("rule", null);
        zom.create(rule);

        rule.name = "updated";
        zom.update(rule, new UpdateValidator<PojoRule>() {
            @Override
            public PojoRule validate(PojoRule oldObj, PojoRule newObj) {
                if (!oldObj.name.equals(newObj.name))
                    throw new IllegalStateException("Expected");
                return newObj;
            }
        });
    }

    @Test
    public void testUpdateWithValidatorModification() throws Exception {
        PojoRule rule = new PojoRule("rule", null);
        zom.create(rule);

        // ensure that the bridge is created
        sync(zom.get(PojoRule.class, rule.id));

        zom.update(rule, new UpdateValidator<PojoRule>() {
            @Override
            public PojoRule validate(PojoRule oldObj, PojoRule newObj) {
                newObj.name = "renamed";
                return null;
            }
        });

        PojoRule renamed = sync(zom.get(PojoRule.class, rule.id));
        assertEquals("renamed", renamed.name);
    }

    @Test
    public void testUpdateWithValidatorReturningModifiedObj() throws Exception {
        final PojoRule rule = new PojoRule("rule", null);
        zom.create(rule);

        // ensure that the bridge is created
        sync(zom.get(PojoRule.class, rule.id));

        zom.update(rule, new UpdateValidator<PojoRule>() {
            @Override
            public PojoRule validate(PojoRule oldObj, PojoRule newObj) {
                PojoRule replacement = new PojoRule("replacement", null);
                replacement.id = rule.id;
                return replacement;
            }
        });

        PojoRule replacement = sync(zom.get(PojoRule.class, rule.id));
        assertEquals("replacement", replacement.name);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testUpdateWithValidatorModifyingId() throws Exception {
        PojoRule rule = new PojoRule("rule", null);
        zom.create(rule);

        zom.update(rule, new UpdateValidator<PojoRule>() {
            @Override
            public PojoRule validate(PojoRule oldObj, PojoRule newObj) {
                return new PojoRule("rule", null);
            }
        });
    }

    @Test(expected = AssertionError.class)
    public void testDeleteForUnregisteredClass() throws Exception {
        zom.delete(Router.class, UUID.randomUUID());
    }

    @Test
    public void testDeleteProtoNetwork() throws Exception {
        Network network = this.createProtoNetwork(
                NETWORK_UUID, NETWORK_NAME, true, 10);
        // Persists the network in ZooKeeper
        zom.create(network);

        // Delete the network data in ZooKeeper.
        zom.delete(Network.class, NETWORK_UUID);

        // Get on the network should throw a NotFoundException.
        try {
            sync(zom.get(Network.class, NETWORK_UUID));
            fail("The deleted network is returned.");
        } catch (NotFoundException nfe) {
            // The network has been properly deleted.
        }
    }

    @Test
    public void testDeleteProtoNetworkWithInChain() throws Exception {
        Chain inChain = this.createProtoChain(CHAIN_UUID, "in_chain");
        zom.create(inChain);

        // Add a network referencing an in-bound chain.
        Network network = this.createProtoNetwork(
                NETWORK_UUID, NETWORK_NAME, CHAIN_UUID, null);
        zom.create(network);

        zom.delete(Network.class, NETWORK_UUID);
        // GET on the network should throw a NotFoundException.
        try {
            sync(zom.get(Network.class, NETWORK_UUID));
            fail("The deleted network is returned.");
        } catch (NotFoundException nfe) {
            // The network has been properly deleted.
        }

        // Chains should not have the backrefs to the network.
        Chain in = sync(zom.get(Chain.class, CHAIN_UUID));
        assertTrue(in.getNetworkIdsList().isEmpty());
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
        assertTrue(syncAll(zom.getAll(PojoChain.class)).isEmpty());
    }

    @Test
    public void testGetAllWithMultipleObjects() throws Exception {
        syncAll(zom.getAll(PojoChain.class));
        PojoChain chain1 = new PojoChain("chain1");
        PojoChain chain2 = new PojoChain("chain2");
        zom.create(chain1);
        syncAll(zom.getAll(PojoChain.class));
        zom.create(chain2);
        List<PojoChain> chains = syncAll(zom.getAll(PojoChain.class));
        assertEquals(2, chains.size());
    }

    @Test
    public void testSubscriberGetsInitialValue() throws Exception {
        PojoChain chain = new PojoChain("chain");
        zom.create(chain);

        ObjectSubscription<PojoChain> sub = subscribe(PojoChain.class,
                                                      chain.id, 1);
        sub.await(1, TimeUnit.SECONDS);
        assertEquals(1, sub.updates());
        assertNotNull(sub.event().get());
        assertEquals(chain.name, sub.event().get().name);
    }

    @Test
    public void testSubscriberGetsUpdates() throws Exception {
        PojoChain chain = new PojoChain("chain");
        zom.create(chain);
        ObjectSubscription<PojoChain> sub = subscribe(PojoChain.class,
                                                      chain.id, 1);
        sub.await(1, TimeUnit.SECONDS);
        sub.reset(1);
        chain.name = "renamedChain";
        zom.update(chain);
        sub.await(1, TimeUnit.SECONDS);
        assertEquals(2, sub.updates());
        assertEquals(chain.name, sub.event().get().name);
    }

    @Test
    public void testSubscriberGetsDelete() throws Exception {
        PojoChain chain = new PojoChain("chain");
        zom.create(chain);
        ObjectSubscription<PojoChain> sub = subscribe(PojoChain.class,
                                                      chain.id, 1);
        sub.await(1, TimeUnit.SECONDS);
        sub.reset(1);
        zom.delete(PojoChain.class, chain.id);
        sub.await(1, TimeUnit.SECONDS);
        assertTrue(sub.event().isEmpty());
    }

    @Test
    public void testSubscribeToNonexistentObject() throws Throwable {
        UUID id = UUID.randomUUID();
        ObjectSubscription<PojoChain> sub = subscribe(PojoChain.class, id, 1);
        sub.await(1, TimeUnit.SECONDS);
        assertThat(sub.ex(), instanceOf(NotFoundException.class));
        NotFoundException ex = (NotFoundException)sub.ex();
        assertEquals(ex.clazz(), PojoChain.class);
        assertEquals(None$.MODULE$, ex.id());
    }

    @Test
    public void testSecondSubscriberGetsLatestVersion() throws Exception {
        PojoChain chain = new PojoChain("chain");
        zom.create(chain);
        ObjectSubscription<PojoChain> sub1 = subscribe(PojoChain.class,
                                                       chain.id, 1);

        sub1.await(1, TimeUnit.SECONDS);
        sub1.reset(1);
        chain.name = "renamedChain";
        zom.update(chain);
        sub1.await(1, TimeUnit.SECONDS);

        ObjectSubscription<PojoChain> sub2 = subscribe(PojoChain.class,
                                                       chain.id, 1);
        sub2.await(1, TimeUnit.SECONDS);
        assertEquals(1, sub2.updates());
        assertNotNull(sub2.event().get());
        assertEquals("renamedChain", sub2.event().get().name);
    }

    @Test
    public void testClassSubscriberGetsCurrentList() throws Exception {
        PojoChain chain1 = new PojoChain("chain1");
        PojoChain chain2 = new PojoChain("chain2");
        createObjects(chain1, chain2);
        ClassSubscription<PojoChain> sub = subscribe(PojoChain.class, 2);
        sub.await(1, TimeUnit.SECONDS);
        assertEquals(2, sub.subs().size());
    }

    @Test
    public void testClassSubscriberGetsNewObject() throws Exception {
        PojoChain chain1 = new PojoChain("chain1");
        zom.create(chain1);
        ClassSubscription<PojoChain> sub = subscribe(PojoChain.class, 1);
        sub.await(1, TimeUnit.SECONDS);
        assertEquals(1, sub.subs().size());
        assertEquals("chain1", sub.subs().get(0).get().event().get().name);

        PojoChain chain2 = new PojoChain("chain2");
        sub.reset(1);
        zom.create(chain2);
        sub.await(1, TimeUnit.SECONDS);
        assertEquals(2, sub.subs().size());
    }

    @Test
    public void testSecondClassSubscriberGetsCurrentList() throws Exception {
        PojoChain chain1 = new PojoChain("chain1");
        zom.create(chain1);
        ClassSubscription<PojoChain> sub = subscribe(PojoChain.class, 1);
        sub.await(1, TimeUnit.SECONDS);
        assertEquals(1, sub.subs().size());

        PojoChain chain2 = new PojoChain("chain2");
        sub.reset(1);
        zom.create(chain2);
        sub.await(1, TimeUnit.SECONDS);

        ClassSubscription<PojoChain> sub2 = subscribe(PojoChain.class, 2);
        sub2.await(1, TimeUnit.SECONDS);
        assertEquals(2, sub2.subs().size());
    }

    @Test
    public void testClassObservableIgnoresDeletedInstances() throws Exception {
        ClassSubscription<PojoChain> sub1 = subscribe(PojoChain.class, 2);
        PojoChain chain1 = new PojoChain("chain1");
        PojoChain chain2 = new PojoChain("chain2");
        zom.create(chain1);
        zom.create(chain2);
        sub1.await(1, TimeUnit.SECONDS);
        assertEquals(2, sub1.subs().size());

        ObjectSubscription<PojoChain> chain1Sub =
            (sub1.subs().get(0).get().event().get().id.equals(chain1.id)) ?
            sub1.subs().get(0).get() : sub1.subs().get(1).get();
        chain1Sub.reset(1);
        zom.delete(PojoChain.class, chain1.id);
        chain1Sub.await(1, TimeUnit.SECONDS);
        assertTrue(chain1Sub.event().isEmpty());

        ClassSubscription<PojoChain> sub2 = subscribe(PojoChain.class, 1);
        sub2.await(1, TimeUnit.SECONDS);
        assertEquals(1, sub2.subs().size());
        assertEquals("chain2", sub2.subs().get(0).get().event().get().name);
    }

    private <T> ObjectSubscription<T> subscribe(
        Class<T> clazz, Object id, int counter) throws Exception{

        ObjectSubscription<T> sub = new ObjectSubscription<>(counter);
        zom.subscribe(clazz, id, sub);
        return sub;
    }

    private <T> ClassSubscription<T> subscribe(Class<T> clazz, int counter)
            throws Exception {
        ClassSubscription<T> sub = new ClassSubscription<>(counter);
        zom.subscribeAll(clazz, sub);
        return sub;
    }

    private void assertPortsRuleIds(PojoPort port, UUID... ruleIds)
            throws Exception {
        assertThat(sync(zom.get(PojoPort.class, port.id)).ruleIds,
                   contains(ruleIds));
    }

    private void createObjects(Object... objects) throws Exception {
        for (Object object : objects) {
            zom.create(object);
        }
    }
}

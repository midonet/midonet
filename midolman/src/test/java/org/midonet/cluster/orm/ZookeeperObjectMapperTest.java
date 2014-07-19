/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.orm;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import org.midonet.cluster.orm.FieldBinding.DeleteAction;
import org.midonet.midolman.SystemDataProvider;
import org.midonet.midolman.config.ZookeeperConfig;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.MockDirectory;
import org.midonet.midolman.state.ZkManager;
import org.midonet.midolman.version.VersionComparator;
import org.midonet.midolman.version.serialization.JsonVersionZkSerializer;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

public class ZookeeperObjectMapperTest {

    private static class Bridge {
        UUID id;
        String name;
        UUID inChainId;
        UUID outChainId;
        List<UUID> portIds;

        Bridge() {}

        Bridge(String name, UUID inChainId, UUID outChainId) {
            this.id = UUID.randomUUID();
            this.name = name;
            this.inChainId = inChainId;
            this.outChainId = outChainId;
        }
    }

    private static class Router {
        UUID id;
        String name;
        UUID inChainId;
        UUID outChainId;
        List<UUID> portIds;

        Router() {}

        Router(String name, UUID inChainId, UUID outChainId) {
            this.id = UUID.randomUUID();
            this.name = name;
            this.inChainId = inChainId;
            this.outChainId = outChainId;
        }
    }

    private static class Port {
        UUID id;
        String name;
        UUID peerId;
        UUID bridgeId;
        UUID routerId;
        UUID inChainId;
        UUID outChainId;
        List<UUID> ruleIds;

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
        UUID id;
        String name;
        List<UUID> ruleIds;
        List<UUID> bridgeIds;
        List<UUID> routerIds;
        List<UUID> portIds;

        Chain() {}

        public Chain(String name) {
            this.id = UUID.randomUUID();
            this.name = name;
        }
    }

    private static class Rule {
        UUID id;
        UUID chainId;
        String name;
        List<UUID> portIds;

        Rule() {}

        public Rule(String name, UUID chainId, UUID... portIds) {
            this.id = UUID.randomUUID();
            this.name = name;
            this.chainId = chainId;
            this.portIds = Arrays.asList(portIds);
        }
    }

    private ZookeeperObjectMapper orm;

    @Before
    public void setup() throws Exception {
        final String rootDir = "/zkormtest";
        Directory directory = new MockDirectory();
        ZkManager zkManager = new ZkManager(directory, rootDir);

        SystemDataProvider provider = Mockito.mock(SystemDataProvider.class);
        Mockito.when(provider.getWriteVersion()).thenReturn("2.0");
        Serializer serializer =
                new JsonVersionZkSerializer(provider, new VersionComparator());

        ZookeeperConfig zkConfig = Mockito.mock(ZookeeperConfig.class);
        Mockito.when(zkConfig.getMidolmanRootKey()).thenReturn(rootDir);

        orm = new ZookeeperObjectMapper(zkManager, serializer, zkConfig);

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

        directory.add(rootDir, null, CreateMode.PERSISTENT);
        for (String s : new String[]{
                "Bridge", "Router", "Port", "Chain", "Rule"})
            directory.add(rootDir + "/" + s, null, CreateMode.PERSISTENT);
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

        assertThat(orm.get(Port.class, bPort1.id).ruleIds,
                contains(c1Rule1.id, c2Rule1.id));
        assertThat(orm.get(Port.class, bPort2.id).ruleIds,
                contains(c1Rule1.id, c1Rule2.id));
        assertThat(orm.get(Port.class, rPort1.id).ruleIds,
                contains(c1Rule2.id, c2Rule1.id));

        // Should not be able to delete the bridge while it has ports.
        try {
            orm.delete(Bridge.class, bridge.id);
            fail("Delete should fail while bridge has ports.");
        } catch (RuntimeException ex) {}

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
        assertThat(orm.get(Port.class, rPort1.id).ruleIds,
                contains(c2Rule1.id));
    }

    @Test
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

        try {
            orm.delete(Bridge.class, bridge.id);
            fail("Should not be able to delete chain while rule exists.");
        } catch (RuntimeException ex) {
            assertTrue(orm.exists(Bridge.class, bridge.id));
            assertTrue(orm.exists(Chain.class, chain.id));
            assertTrue(orm.exists(Rule.class, rule.id));
        }
    }

    private void createObjects(Object... objects) throws Exception {
        for (Object object : objects) {
            orm.create(object);
        }
    }
}

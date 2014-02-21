/*
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.api.filter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.net.URI;

import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import static org.hamcrest.Matchers.arrayContainingInAnyOrder;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;

import org.midonet.api.rest_api.DtoWebResource;
import org.midonet.api.rest_api.FuncTest;
import org.midonet.api.rest_api.Topology;
import org.midonet.api.zookeeper.StaticMockDirectory;
import org.midonet.client.dto.DtoApplication;
import org.midonet.client.dto.DtoBridge;
import org.midonet.client.dto.DtoError;
import org.midonet.client.dto.DtoInteriorRouterPort;
import org.midonet.client.dto.DtoRouter;
import org.midonet.client.dto.DtoRule;
import org.midonet.client.dto.DtoRuleChain;

import static org.midonet.api.VendorMediaType.APPLICATION_BRIDGE_JSON;
import static org.midonet.api.VendorMediaType.APPLICATION_CHAIN_COLLECTION_JSON;
import static org.midonet.api.VendorMediaType.APPLICATION_CHAIN_JSON;
import static org.midonet.api.VendorMediaType.APPLICATION_RULE_COLLECTION_JSON;
import static org.midonet.api.VendorMediaType.APPLICATION_RULE_JSON;
import static org.midonet.api.VendorMediaType.APPLICATION_ROUTER_JSON;
import static org.midonet.api.VendorMediaType.APPLICATION_PORT_JSON;

@RunWith(Enclosed.class)
public class TestChain {

    @RunWith(Parameterized.class)
    public static class TestCreateChainBadRequest extends JerseyTest {

        private DtoWebResource dtoResource;
        private Topology topology;
        private final DtoRuleChain chain;
        private final String property;

        public TestCreateChainBadRequest(DtoRuleChain chain, String property) {
            super(FuncTest.appDesc);
            this.chain = chain;
            this.property = property;
        }

        @Before
        public void setUp() {

            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);

            // Create a chain - useful for checking duplicate name error
            DtoRuleChain c = new DtoRuleChain();
            c.setName("chain1-name");
            c.setTenantId("tenant1");

            topology = new Topology.Builder(dtoResource)
                    .create("chain1", c).build();
        }

        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        @Parameters
        public static Collection<Object[]> data() {

            List<Object[]> params = new ArrayList<Object[]>();

            // Null name
            DtoRuleChain nullName = new DtoRuleChain();
            nullName.setTenantId("tenant1");
            params.add(new Object[] { nullName, "name" });

            // Blank name
            DtoRuleChain blankName = new DtoRuleChain();
            blankName.setName("");
            blankName.setTenantId("tenant1");
            params.add(new Object[] { blankName, "name" });

            // Long name
            StringBuilder longNameStr = new StringBuilder(
                    Chain.MAX_CHAIN_NAME_LEN + 1);
            for (int i = 0; i < Chain.MAX_CHAIN_NAME_LEN + 1; i++) {
                longNameStr.append("a");
            }
            DtoRuleChain longName = new DtoRuleChain();
            longName.setName(longNameStr.toString());
            longName.setTenantId("tenant1");
            params.add(new Object[] { longName, "name" });

            // Chain name already exists
            DtoRuleChain dupNameChain = new DtoRuleChain();
            dupNameChain.setName("chain1-name");
            dupNameChain.setTenantId("tenant1");
            params.add(new Object[] { dupNameChain, "name" });

            // Chain with tenantID missing
            DtoRuleChain noTenant = new DtoRuleChain();
            noTenant.setName("noTenant-chain-name");
            params.add(new Object[] { noTenant, "tenantId" });

            return params;
        }

        @Test
        public void testBadInputCreate() {

            DtoApplication app = topology.getApplication();

            DtoError error = dtoResource.postAndVerifyBadRequest(
                    app.getChains(), APPLICATION_CHAIN_JSON, chain);
            List<Map<String, String>> violations = error.getViolations();
            assertEquals(1, violations.size());
            assertEquals(property, violations.get(0).get("property"));
        }
    }

    public static class TestChainCrud extends JerseyTest {

        private DtoWebResource dtoResource;
        private Topology topology;

        public TestChainCrud() {
            super(FuncTest.appDesc);
        }

        @Before
        public void before() {

            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);

            topology = new Topology.Builder(dtoResource).build();
        }

        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        @Test
        public void testCreateGetListDelete() {

            DtoApplication app = topology.getApplication();

            ClientResponse response;

            // Create a rule chain for Tenant1
            DtoRuleChain ruleChain1 = new DtoRuleChain();
            ruleChain1.setName("Chain1");
            ruleChain1.setTenantId("tenant1");
            response = resource().uri(app.getChains())
                    .type(APPLICATION_CHAIN_JSON)
                    .post(ClientResponse.class, ruleChain1);
            assertEquals("The chain was created.", 201, response.getStatus());
            ruleChain1 = resource().uri(response.getLocation())
                    .accept(APPLICATION_CHAIN_JSON).get(DtoRuleChain.class);
            assertEquals("Chain1", ruleChain1.getName());
            assertEquals("tenant1", ruleChain1.getTenantId());

            // Create another rule chain for Tenant1
            DtoRuleChain ruleChain2 = new DtoRuleChain();
            ruleChain2.setName("Chain2");
            ruleChain2.setTenantId("tenant1");
            response = resource().uri(app.getChains())
                    .type(APPLICATION_CHAIN_JSON)
                    .post(ClientResponse.class, ruleChain2);
            assertEquals("The chain was created.", 201, response.getStatus());
            ruleChain2 = resource().uri(response.getLocation())
                    .accept(APPLICATION_CHAIN_JSON).get(DtoRuleChain.class);
            assertEquals("Chain2", ruleChain2.getName());
            assertEquals("tenant1", ruleChain2.getTenantId());

            // Create a rule chain for Tenant2
            DtoRuleChain ruleChain3 = new DtoRuleChain();
            ruleChain3.setName("Chain3");
            ruleChain3.setTenantId("tenant2");
            response = resource().uri(app.getChains())
                    .type(APPLICATION_CHAIN_JSON)
                    .post(ClientResponse.class, ruleChain3);
            assertEquals("The chain was created.", 201, response.getStatus());
            ruleChain3 = resource().uri(response.getLocation())
                    .accept(APPLICATION_CHAIN_JSON).get(DtoRuleChain.class);
            assertEquals("Chain3", ruleChain3.getName());
            assertEquals("tenant2", ruleChain3.getTenantId());

            // List tenant1's chains
            response = resource().uri(app.getChains())
                    .queryParam("tenant_id", "tenant1")
                    .accept(APPLICATION_CHAIN_COLLECTION_JSON)
                    .get(ClientResponse.class);
            assertEquals(200, response.getStatus());
            DtoRuleChain[] chains = response.getEntity(DtoRuleChain[].class);
            assertThat("Tenant1 has 2 chains.", chains, arrayWithSize(2));
            assertThat(
                    "We expect the listed chains to match those we created.",
                    chains, arrayContainingInAnyOrder(ruleChain1, ruleChain2));

            // Create a JUMP rule in chain1 with target=chain2
            DtoRule jumpRule = new DtoRule();
            jumpRule.setPosition(1);
            jumpRule.setJumpChainName("Chain2");
            jumpRule.setType(DtoRule.Jump);
            response = resource().uri(ruleChain1.getRules())
                    .type(APPLICATION_RULE_JSON)
                    .post(ClientResponse.class, jumpRule);
            assertEquals("The jump rule was created.", 201,
                    response.getStatus());
            jumpRule = resource().uri(response.getLocation())
                    .accept(APPLICATION_RULE_JSON).get(DtoRule.class);
            assertEquals("Chain2", jumpRule.getJumpChainName());
            assertEquals(ruleChain1.getId(), jumpRule.getChainId());

            // Delete the first rule-chain
            response = resource().uri(ruleChain1.getUri()).delete(
                    ClientResponse.class);
            assertEquals(204, response.getStatus());
            // There should now be only the second chain.
            response = resource().uri(app.getChains())
                    .queryParam("tenant_id", "tenant1")
                    .accept(APPLICATION_CHAIN_COLLECTION_JSON)
                    .get(ClientResponse.class);
            assertEquals(200, response.getStatus());
            chains = response.getEntity(DtoRuleChain[].class);
            assertThat("We expect 1 listed chain after the delete", chains,
                    arrayWithSize(1));
            assertThat(
                    "The listed chain should be the one that wasn't deleted.",
                    chains, arrayContainingInAnyOrder(ruleChain2));

            // Test GET of a non-existing chain (the deleted first chain).
            response = resource().uri(ruleChain1.getUri())
                    .accept(APPLICATION_CHAIN_JSON).get(ClientResponse.class);
            assertEquals(404, response.getStatus());

            // TODO(pino): creata JUMP rule in chain1 with target=chain2.

            // TODO(pino): all these cases should fail:
            // TODO: 1) Set a JUMP target to the other tenant's chain.
            // TODO: 2) Set a JUMP target to a non-existent chain.
            // TODO: 3) Set a chain as a filter on the other tenant's bridge.
            // TODO: 4) Set a chain as a filter on the other tenant's router.
            // TODO: 5) Set a chain as a filter on the other tenant's port.
        }

        private DtoBridge getStockBridge(String name, UUID inbound, UUID outbound) {
            DtoBridge bridge = new DtoBridge();
            UUID bId = UUID.randomUUID();
            bridge.setId(bId);
            bridge.setName(name);
            bridge.setTenantId("tenant1");
            bridge.setInboundFilterId(inbound);
            bridge.setOutboundFilterId(outbound);
            return bridge;
        }

        private  DtoRouter getStockRouter(String name, UUID inbound, UUID outbound) {
            DtoRouter router = new DtoRouter();
            UUID rId = UUID.randomUUID();
            router.setId(rId);
            router.setName(name);
            router.setTenantId("tenant1");
            router.setInboundFilterId(inbound);
            router.setOutboundFilterId(outbound);
            return router;
        }

        private DtoInteriorRouterPort getStockRouterPort(UUID router, UUID inbound, UUID outbound) {
            DtoInteriorRouterPort port = new DtoInteriorRouterPort();
            port.setId(null);
            port.setInboundFilterId(inbound);
            port.setOutboundFilterId(outbound);
            port.setNetworkAddress("10.0.0.0");
            port.setNetworkLength(24);
            port.setPortAddress("10.0.0.2");
            port.setDeviceId(router);
            return port;
        }

        private DtoRuleChain getStockChain(String name) {
            DtoRuleChain ruleChain = new DtoRuleChain();
            UUID rc1Id = UUID.randomUUID();
            ruleChain.setId(rc1Id);
            ruleChain.setName(name);
            ruleChain.setTenantId("tenant1");
            return ruleChain;
        }

        private DtoRule getStockRule(UUID jumpChain, String jumpChainName) {
            DtoRule rule = new DtoRule();
            rule.setId(UUID.randomUUID());
            rule.setFlowAction("jump");
            rule.setJumpChainId(jumpChain);
            rule.setJumpChainName(jumpChainName);
            rule.setType("jump");
            return rule;
        }

        /*
         * This test function is long because a series of tests for each
         * of router, port, and bridge. It tests that backreferences in chains
         * go and cleanup the appropriate items in the objects that reference
         * them.
         */
        @Test
        public void testCleanup() {
            DtoApplication app = topology.getApplication();

            DtoRuleChain ruleChain1
                    = dtoResource.postAndVerifyCreated(app.getChains(),
                    APPLICATION_CHAIN_JSON, getStockChain("INBOUND"),
                    DtoRuleChain.class);

            DtoRuleChain ruleChain2
                    = dtoResource.postAndVerifyCreated(app.getChains(),
                    APPLICATION_CHAIN_JSON, getStockChain("OUTBOUND"),
                    DtoRuleChain.class);

            DtoRouter router
                    = dtoResource.postAndVerifyCreated(app.getRouters(),
                    APPLICATION_ROUTER_JSON,
                    getStockRouter("R", ruleChain1.getId(), ruleChain2.getId()),
                    DtoRouter.class);

            DtoBridge bridge
                    = dtoResource.postAndVerifyCreated(app.getBridges(),
                    APPLICATION_BRIDGE_JSON,
                    getStockBridge("B", ruleChain1.getId(), ruleChain2.getId()),
                    DtoBridge.class);

            DtoInteriorRouterPort port
                    = dtoResource.postAndVerifyCreated(router.getPorts(),
                    APPLICATION_PORT_JSON,
                    getStockRouterPort(router.getId(), ruleChain1.getId(),
                            ruleChain2.getId()),
                    DtoInteriorRouterPort.class);

            URI rulesUri = ruleChain2.getRules();
            DtoRule rule
                    = dtoResource.postAndVerifyCreated(rulesUri,
                    APPLICATION_RULE_JSON,
                    getStockRule(ruleChain1.getId(), ruleChain1.getName()),
                    DtoRule.class);

            DtoRule[] rules = dtoResource.getAndVerifyOk(rulesUri,
                    APPLICATION_RULE_COLLECTION_JSON, DtoRule[].class);
            assertEquals(1, rules.length);

            dtoResource.deleteAndVerifyNoContent(ruleChain1.getUri(),
                    APPLICATION_CHAIN_JSON);

            // The Rule should have been deleted.
            rules = dtoResource.getAndVerifyOk(rulesUri,
                    APPLICATION_RULE_COLLECTION_JSON, DtoRule[].class);
            assertEquals(0, rules.length);

            // Everyone's inbound filters should now be empty
            port = dtoResource.getAndVerifyOk(port.getUri(),
                    APPLICATION_PORT_JSON, DtoInteriorRouterPort.class);
            assertEquals(port.getInboundFilterId(), null);
            router = dtoResource.getAndVerifyOk(router.getUri(),
                    APPLICATION_ROUTER_JSON, DtoRouter.class);
            assertEquals(router.getInboundFilter(), null);
            bridge = dtoResource.getAndVerifyOk(bridge.getUri(),
                    APPLICATION_BRIDGE_JSON, DtoBridge.class);
            assertEquals(bridge.getInboundFilter(), null);

            // Set everyone's inbound filters to be the same as their
            // outbound filters.
            port.setInboundFilterId(ruleChain2.getId());
            port = dtoResource.putAndVerifyNoContent(port.getUri(),
                    APPLICATION_PORT_JSON, port, DtoInteriorRouterPort.class);

            router.setInboundFilterId(ruleChain2.getId());
            router = dtoResource.putAndVerifyNoContent(router.getUri(),
                    APPLICATION_ROUTER_JSON, router, DtoRouter.class);

            bridge.setInboundFilterId(ruleChain2.getId());
            bridge = dtoResource.putAndVerifyNoContent(bridge.getUri(),
                    APPLICATION_BRIDGE_JSON, bridge, DtoBridge.class);

            // delete the chain referenced by both inbound and outbound.
            dtoResource.deleteAndVerifyNoContent(ruleChain2.getUri(),
                    APPLICATION_CHAIN_JSON);

            // Everyone's filters should have been cleaned up.
            port = dtoResource.getAndVerifyOk(port.getUri(),
                    APPLICATION_PORT_JSON, DtoInteriorRouterPort.class);
            assertEquals(port.getInboundFilterId(), null);
            assertEquals(port.getOutboundFilterId(), null);
            router = dtoResource.getAndVerifyOk(router.getUri(),
                    APPLICATION_ROUTER_JSON, DtoRouter.class);
            assertEquals(router.getInboundFilter(), null);
            assertEquals(router.getOutboundFilter(), null);
            bridge = dtoResource.getAndVerifyOk(bridge.getUri(),
                    APPLICATION_BRIDGE_JSON, DtoBridge.class);
            assertEquals(bridge.getInboundFilter(), null);
            assertEquals(bridge.getOutboundFilter(), null);

            // Create the chains again for more testing.
            ruleChain1 = dtoResource.postAndVerifyCreated(app.getChains(),
                    APPLICATION_CHAIN_JSON, getStockChain("INBOUND"),
                    DtoRuleChain.class);

            ruleChain2 = dtoResource.postAndVerifyCreated(app.getChains(),
                    APPLICATION_CHAIN_JSON, getStockChain("OUTBOUND"),
                    DtoRuleChain.class);

            // Set the chains to different IDs.
            port.setInboundFilterId(ruleChain1.getId());
            port.setOutboundFilterId(ruleChain2.getId());
            port = dtoResource.putAndVerifyNoContent(port.getUri(),
                    APPLICATION_PORT_JSON, port, DtoInteriorRouterPort.class);

            router.setInboundFilterId(ruleChain1.getId());
            router.setOutboundFilterId(ruleChain2.getId());
            router = dtoResource.putAndVerifyNoContent(router.getUri(),
                    APPLICATION_ROUTER_JSON, router, DtoRouter.class);

            bridge.setInboundFilterId(ruleChain1.getId());
            bridge.setOutboundFilterId(ruleChain2.getId());
            bridge = dtoResource.putAndVerifyNoContent(bridge.getUri(),
                    APPLICATION_BRIDGE_JSON, bridge, DtoBridge.class);

            //Reset to the same Id
            port.setInboundFilterId(ruleChain2.getId());
            port = dtoResource.putAndVerifyNoContent(port.getUri(),
                    APPLICATION_PORT_JSON, port, DtoInteriorRouterPort.class);

            router.setInboundFilterId(ruleChain2.getId());
            router = dtoResource.putAndVerifyNoContent(router.getUri(),
                    APPLICATION_ROUTER_JSON, router, DtoRouter.class);

            bridge.setInboundFilterId(ruleChain2.getId());
            bridge = dtoResource.putAndVerifyNoContent(bridge.getUri(),
                    APPLICATION_BRIDGE_JSON, bridge, DtoBridge.class);

            // Delete the first chain. It should not affect the pointers.
            dtoResource.deleteAndVerifyNoContent(ruleChain1.getUri(),
                    APPLICATION_CHAIN_JSON);

            port = dtoResource.getAndVerifyOk(port.getUri(),
                    APPLICATION_PORT_JSON, DtoInteriorRouterPort.class);
            assertEquals(port.getInboundFilterId(), ruleChain2.getId());
            assertEquals(port.getOutboundFilterId(), ruleChain2.getId());
            router = dtoResource.getAndVerifyOk(router.getUri(),
                    APPLICATION_ROUTER_JSON, DtoRouter.class);
            assertEquals(router.getInboundFilterId(), ruleChain2.getId());
            assertEquals(router.getOutboundFilterId(), ruleChain2.getId());
            bridge = dtoResource.getAndVerifyOk(bridge.getUri(),
                    APPLICATION_BRIDGE_JSON, DtoBridge.class);
            assertEquals(bridge.getInboundFilterId(), ruleChain2.getId());
            assertEquals(bridge.getOutboundFilterId(), ruleChain2.getId());

            dtoResource.deleteAndVerifyNoContent(ruleChain2.getUri(),
                    APPLICATION_CHAIN_JSON);

            port = dtoResource.getAndVerifyOk(port.getUri(),
                    APPLICATION_PORT_JSON, DtoInteriorRouterPort.class);
            assertEquals(port.getInboundFilterId(), null);
            assertEquals(port.getOutboundFilterId(), null);
            router = dtoResource.getAndVerifyOk(router.getUri(),
                    APPLICATION_ROUTER_JSON, DtoRouter.class);
            assertEquals(router.getInboundFilterId(), null);
            assertEquals(router.getOutboundFilterId(), null);
            bridge = dtoResource.getAndVerifyOk(bridge.getUri(),
                    APPLICATION_BRIDGE_JSON, DtoBridge.class);
            assertEquals(bridge.getInboundFilterId(), null);
            assertEquals(bridge.getOutboundFilterId(), null);

            // Create these chains again for more testing.
            ruleChain1 = dtoResource.postAndVerifyCreated(app.getChains(),
                    APPLICATION_CHAIN_JSON, getStockChain("INBOUND"),
                    DtoRuleChain.class);

            ruleChain2 = dtoResource.postAndVerifyCreated(app.getChains(),
                    APPLICATION_CHAIN_JSON, getStockChain("OUTBOUND"),
                    DtoRuleChain.class);

            // Set the chains to different IDs.
            port.setInboundFilterId(ruleChain1.getId());
            port.setOutboundFilterId(ruleChain2.getId());
            port = dtoResource.putAndVerifyNoContent(port.getUri(),
                    APPLICATION_PORT_JSON, port, DtoInteriorRouterPort.class);

            router.setInboundFilterId(ruleChain1.getId());
            router.setOutboundFilterId(ruleChain2.getId());
            router = dtoResource.putAndVerifyNoContent(router.getUri(),
                    APPLICATION_ROUTER_JSON, router, DtoRouter.class);

            bridge.setInboundFilterId(ruleChain1.getId());
            bridge.setOutboundFilterId(ruleChain2.getId());
            bridge = dtoResource.putAndVerifyNoContent(bridge.getUri(),
                    APPLICATION_BRIDGE_JSON, bridge, DtoBridge.class);

            // Create these chains again for more testing.
            ruleChain1 = dtoResource.postAndVerifyCreated(app.getChains(),
                    APPLICATION_CHAIN_JSON, getStockChain("BOTH"),
                    DtoRuleChain.class);

            // Set the chains to one valid chain, and one null
            port.setInboundFilterId(ruleChain1.getId());
            port.setOutboundFilterId(null);
            port = dtoResource.putAndVerifyNoContent(port.getUri(),
                    APPLICATION_PORT_JSON, port, DtoInteriorRouterPort.class);

            router.setInboundFilterId(ruleChain1.getId());
            router.setOutboundFilterId(null);
            router = dtoResource.putAndVerifyNoContent(router.getUri(),
                    APPLICATION_ROUTER_JSON, router, DtoRouter.class);

            bridge.setInboundFilterId(ruleChain1.getId());
            bridge.setOutboundFilterId(null);
            bridge = dtoResource.putAndVerifyNoContent(bridge.getUri(),
                    APPLICATION_BRIDGE_JSON, bridge, DtoBridge.class);

            // Delete to trigger the back ref delete.
            dtoResource.deleteAndVerifyNoContent(ruleChain1.getUri(),
                    APPLICATION_CHAIN_JSON);

            // Create these chains again for more testing.
            ruleChain1 = dtoResource.postAndVerifyCreated(app.getChains(),
                    APPLICATION_CHAIN_JSON, getStockChain("BOTH"),
                    DtoRuleChain.class);

            // Set the filters to the same ruleChain
            port.setInboundFilterId(ruleChain1.getId());
            port.setOutboundFilterId(ruleChain1.getId());
            port = dtoResource.putAndVerifyNoContent(port.getUri(),
                    APPLICATION_PORT_JSON, port, DtoInteriorRouterPort.class);

            router.setInboundFilterId(ruleChain1.getId());
            router.setOutboundFilterId(ruleChain1.getId());
            router = dtoResource.putAndVerifyNoContent(router.getUri(),
                    APPLICATION_ROUTER_JSON, router, DtoRouter.class);

            bridge.setInboundFilterId(ruleChain1.getId());
            bridge.setOutboundFilterId(ruleChain1.getId());
            bridge = dtoResource.putAndVerifyNoContent(bridge.getUri(),
                    APPLICATION_BRIDGE_JSON, bridge, DtoBridge.class);

            port.setInboundFilterId(ruleChain2.getId());
            port.setOutboundFilterId(ruleChain2.getId());
            port = dtoResource.putAndVerifyNoContent(port.getUri(),
                    APPLICATION_PORT_JSON, port, DtoInteriorRouterPort.class);

            router.setInboundFilterId(ruleChain2.getId());
            router.setOutboundFilterId(ruleChain2.getId());
            router = dtoResource.putAndVerifyNoContent(router.getUri(),
                    APPLICATION_ROUTER_JSON, router, DtoRouter.class);

            bridge.setInboundFilterId(ruleChain2.getId());
            bridge.setOutboundFilterId(ruleChain2.getId());
            bridge = dtoResource.putAndVerifyNoContent(bridge.getUri(),
                    APPLICATION_BRIDGE_JSON, bridge, DtoBridge.class);

            port.setInboundFilterId(null);
            port.setOutboundFilterId(null);
            port = dtoResource.putAndVerifyNoContent(port.getUri(),
                    APPLICATION_PORT_JSON, port, DtoInteriorRouterPort.class);

            router.setInboundFilterId(null);
            router.setOutboundFilterId(null);
            router = dtoResource.putAndVerifyNoContent(router.getUri(),
                    APPLICATION_ROUTER_JSON, router, DtoRouter.class);

            bridge.setInboundFilterId(null);
            bridge.setOutboundFilterId(null);
            bridge = dtoResource.putAndVerifyNoContent(bridge.getUri(),
                    APPLICATION_BRIDGE_JSON, bridge, DtoBridge.class);

            // Delete the objects first. This way, if any of the back refs
            // weren't cleaned up, the delete of the chains would fail.
            dtoResource.deleteAndVerifyNoContent(router.getUri(),
                    APPLICATION_ROUTER_JSON);
            dtoResource.deleteAndVerifyNoContent(bridge.getUri(),
                    APPLICATION_BRIDGE_JSON);
            dtoResource.deleteAndVerifyNoContent(port.getUri(),
                    APPLICATION_PORT_JSON);

            dtoResource.deleteAndVerifyNoContent(ruleChain1.getUri(),
                    APPLICATION_CHAIN_JSON);
            dtoResource.deleteAndVerifyNoContent(ruleChain2.getUri(),
                    APPLICATION_CHAIN_JSON);
        }
    }
}

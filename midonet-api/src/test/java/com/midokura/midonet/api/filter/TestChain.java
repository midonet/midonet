/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midonet.api.filter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

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
import static org.junit.Assert.assertThat;

import com.midokura.midonet.api.rest_api.DtoWebResource;
import com.midokura.midonet.api.rest_api.FuncTest;
import com.midokura.midonet.api.rest_api.Topology;
import com.midokura.midonet.api.zookeeper.StaticMockDirectory;
import com.midokura.midonet.client.dto.DtoApplication;
import com.midokura.midonet.client.dto.DtoError;
import com.midokura.midonet.client.dto.DtoRule;
import com.midokura.midonet.client.dto.DtoRuleChain;

import static com.midokura.midonet.api.VendorMediaType.APPLICATION_CHAIN_COLLECTION_JSON;
import static com.midokura.midonet.api.VendorMediaType.APPLICATION_CHAIN_JSON;
import static com.midokura.midonet.api.VendorMediaType.APPLICATION_RULE_JSON;

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
    }
}

/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.mgmt.rest_api;

import static com.midokura.midolman.mgmt.http.VendorMediaType.APPLICATION_PORTGROUP_COLLECTION_JSON;
import static com.midokura.midolman.mgmt.http.VendorMediaType.APPLICATION_PORTGROUP_JSON;
import static com.midokura.midolman.mgmt.http.VendorMediaType.APPLICATION_PORT_JSON;
import static com.midokura.midolman.mgmt.http.VendorMediaType.APPLICATION_RULE_JSON;
import static org.hamcrest.Matchers.arrayContainingInAnyOrder;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import java.util.UUID;

import com.midokura.midolman.mgmt.data.zookeeper.StaticMockDirectory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;

import com.midokura.midolman.mgmt.data.dto.client.DtoBridge;
import com.midokura.midolman.mgmt.data.dto.client.DtoBridgePort;
import com.midokura.midolman.mgmt.data.dto.client.DtoPortGroup;
import com.midokura.midolman.mgmt.data.dto.client.DtoRule;
import com.midokura.midolman.mgmt.data.dto.client.DtoRuleChain;
import com.midokura.midolman.mgmt.data.dto.client.DtoTenant;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;

@RunWith(Enclosed.class)
public class TestPortGroup {

    public static class TestCrudSuccess extends JerseyTest {

        private DtoWebResource dtoResource;
        private Topology topology;

        public TestCrudSuccess() {
            super(FuncTest.appDesc);
        }

        @Before
        public void before() {

            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);

            // Create two tenants
            DtoTenant t1 = new DtoTenant();
            t1.setId("tenant1-id");

            DtoTenant t2 = new DtoTenant();
            t2.setId("tenant2-id");

            // Create one bridge for tenant1
            DtoBridge bridge = new DtoBridge();
            bridge.setName("Bridge1");

            // Create one chain for tenant1
            DtoRuleChain chain = new DtoRuleChain();
            chain.setName("Chain1");

            topology = new Topology.Builder(dtoResource).create("tenant1", t1)
                    .create("tenant2", t2).create("tenant1", "bridge1", bridge)
                    .create("tenant1", "chain1", chain).build();
        }

        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        @Test
        public void testCreateGetListDelete() {

            DtoTenant tenant1 = topology.getTenant("tenant1");
            DtoTenant tenant2 = topology.getTenant("tenant2");
            DtoBridge bridge = topology.getBridge("bridge1");
            DtoRuleChain chain = topology.getChain("chain1");

            // Create a port group for Tenant1
            DtoPortGroup group1 = new DtoPortGroup();
            group1.setName("Group1");
            group1 = dtoResource.postAndVerifyCreated(tenant1.getPortGroups(),
                    APPLICATION_PORTGROUP_JSON, group1, DtoPortGroup.class);
            assertEquals("Group1", group1.getName());
            assertEquals(tenant1.getId(), group1.getTenantId());

            // Create another port group for Tenant1
            DtoPortGroup group2 = new DtoPortGroup();
            group2.setName("Group2");
            group2 = dtoResource.postAndVerifyCreated(tenant1.getPortGroups(),
                    APPLICATION_PORTGROUP_JSON, group2, DtoPortGroup.class);
            assertEquals("Group2", group2.getName());
            assertEquals(tenant1.getId(), group2.getTenantId());

            // Create a port group for Tenant2
            DtoPortGroup group3 = new DtoPortGroup();
            group3.setName("Group3");
            group3 = dtoResource.postAndVerifyCreated(tenant2.getPortGroups(),
                    APPLICATION_PORTGROUP_JSON, group3, DtoPortGroup.class);
            assertEquals("Group3", group3.getName());
            assertEquals(tenant2.getId(), group3.getTenantId());

            // List tenant1's groups
            DtoPortGroup[] groups = dtoResource
                    .getAndVerifyOk(tenant1.getPortGroups(),
                            APPLICATION_PORTGROUP_COLLECTION_JSON,
                            DtoPortGroup[].class);
            assertThat("Tenant1 has 2 groups.", groups, arrayWithSize(2));
            assertThat(
                    "We expect the listed groups to match those we created.",
                    groups, arrayContainingInAnyOrder(group1, group2));

            // Create a port on Bridge1 that belongs to both of Tenant1's
            // PortGroups
            DtoBridgePort port = new DtoBridgePort();
            port.setPortGroupIDs(new UUID[] { group1.getId(), group2.getId() });
            port = dtoResource.postAndVerifyCreated(bridge.getPorts(),
                    APPLICATION_PORT_JSON, port, DtoBridgePort.class);
            assertEquals("Bridge1", bridge.getName());
            assertEquals(bridge.getId(), port.getDeviceId());
            assertThat("The port's groups should be group1 and group2",
                    port.getPortGroupIDs(),
                    arrayContainingInAnyOrder(group1.getId(), group2.getId()));

            // Now add the rule.
            DtoRule rule = new DtoRule();
            rule.setPosition(1);
            rule.setType(DtoRule.Accept);
            rule.setNwSrcAddress("10.11.12.13");
            rule.setNwSrcLength(32);
            UUID[] fakePortIDs = new UUID[] { UUID.randomUUID(),
                    UUID.randomUUID() };
            rule.setInPorts(fakePortIDs);
            rule.setPortGroup(group1.getId());
            rule = dtoResource.postAndVerifyCreated(chain.getRules(),
                    APPLICATION_RULE_JSON, rule, DtoRule.class);
            assertEquals(chain.getId(), rule.getChainId());
            assertEquals(DtoRule.Accept, rule.getType());
            assertEquals("10.11.12.13", rule.getNwSrcAddress());
            assertEquals(32, rule.getNwSrcLength());
            assertThat("The rule should match the fake ingress ports",
                    rule.getInPorts(),
                    arrayContainingInAnyOrder(fakePortIDs[0], fakePortIDs[1]));
            assertThat("The rule should match group1", rule.getPortGroup(),
                    equalTo(group1.getId()));

            // Delete the first group
            dtoResource.deleteAndVerifyNoContent(group1.getUri(),
                    APPLICATION_PORTGROUP_JSON);

            // There should now be only the second group.
            groups = dtoResource
                    .getAndVerifyOk(tenant1.getPortGroups(),
                            APPLICATION_PORTGROUP_COLLECTION_JSON,
                            DtoPortGroup[].class);
            assertThat("We expect 1 listed group after the delete", groups,
                    arrayWithSize(1));
            assertThat(
                    "The listed group should be the one that wasn't deleted.",
                    groups, arrayContainingInAnyOrder(group2));

            // Test GET of a non-existing group (the deleted first group).
            dtoResource.getAndVerifyNotFound(group1.getUri(),
                    APPLICATION_PORTGROUP_JSON);

            // TODO(pino): all these cases should fail:
            // TODO: 1) Set a Port's group to a GroupID owned by another Tenant.
        }
    }
}
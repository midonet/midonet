/*
 * Copyright 2012 Midokura Europe SARL
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.network;

import org.midonet.api.rest_api.DtoWebResource;
import org.midonet.api.zookeeper.StaticMockDirectory;
import org.midonet.api.rest_api.FuncTest;
import org.midonet.api.rest_api.Topology;
import org.midonet.client.dto.*;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import javax.ws.rs.core.UriBuilder;
import java.net.URI;
import java.util.*;

import static org.midonet.api.VendorMediaType.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

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

            // Create one bridge for tenant1
            DtoBridge bridge = new DtoBridge();
            bridge.setName("Bridge1");
            bridge.setTenantId("tenant1-id");

            // Create one chain for tenant1
            DtoRuleChain chain = new DtoRuleChain();
            chain.setName("Chain1");
            chain.setTenantId("tenant1-id");

            topology = new Topology.Builder(dtoResource)
                    .create("bridge1", bridge)
                    .create("chain1", chain).build();
        }

        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        @Test
        public void testCreateGetListDelete() {

            DtoApplication app = topology.getApplication();
            DtoBridge bridge = topology.getBridge("bridge1");
            DtoRuleChain chain = topology.getChain("chain1");

            // Create a port group for Tenant1
            DtoPortGroup group1 = new DtoPortGroup();
            group1.setName("Group1");
            group1.setTenantId("tenant1-id");
            group1 = dtoResource.postAndVerifyCreated(app.getPortGroups(),
                    APPLICATION_PORTGROUP_JSON, group1, DtoPortGroup.class);
            assertEquals("Group1", group1.getName());
            assertEquals("tenant1-id", group1.getTenantId());

            // Create another port group for Tenant1
            DtoPortGroup group2 = new DtoPortGroup();
            group2.setName("Group2");
            group2.setTenantId("tenant1-id");
            group2 = dtoResource.postAndVerifyCreated(app.getPortGroups(),
                    APPLICATION_PORTGROUP_JSON, group2, DtoPortGroup.class);
            assertEquals("Group2", group2.getName());
            assertEquals("tenant1-id", group2.getTenantId());

            // Create a port group for Tenant2
            DtoPortGroup group3 = new DtoPortGroup();
            group3.setName("Group3");
            group3.setTenantId("tenant2-id");
            group3 = dtoResource.postAndVerifyCreated(app.getPortGroups(),
                    APPLICATION_PORTGROUP_JSON, group3, DtoPortGroup.class);
            assertEquals("Group3", group3.getName());
            assertEquals("tenant2-id", group3.getTenantId());

            // List tenant1's groups
            URI tenantSearchUri = UriBuilder.fromUri(app.getPortGroups())
                    .queryParam("tenant_id", "tenant1-id").build();
            DtoPortGroup[] groups = dtoResource
                    .getAndVerifyOk(tenantSearchUri,
                            APPLICATION_PORTGROUP_COLLECTION_JSON,
                            DtoPortGroup[].class);
            assertThat("Tenant1 has 2 groups.", groups, arrayWithSize(2));
            assertThat(
                    "We expect the listed groups to match those we created.",
                    groups, arrayContainingInAnyOrder(group1, group2));

            // Create a port on Bridge1 that belongs to both of Tenant1's
            // PortGroups
            DtoBridgePort port = new DtoBridgePort();
            port = dtoResource.postAndVerifyCreated(bridge.getPorts(),
                    APPLICATION_PORT_V2_JSON, port, DtoBridgePort.class);
            assertEquals("Bridge1", bridge.
                getName());
            assertEquals(bridge.getId(), port.getDeviceId());

            // Add this port to a port group and verify that it exists
            DtoPortGroupPort portGroupPort = new DtoPortGroupPort();
            portGroupPort.setPortId(port.getId());
            portGroupPort = dtoResource.postAndVerifyCreated(group1.getPorts(),
                    APPLICATION_PORTGROUP_PORT_JSON, portGroupPort,
                    DtoPortGroupPort.class);
            DtoPortGroupPort[] portGroupPorts = dtoResource.getAndVerifyOk(
                    group1.getPorts(),
                    APPLICATION_PORTGROUP_PORT_COLLECTION_JSON,
                    DtoPortGroupPort[].class);
            assertThat("Port group has one port", portGroupPorts,
                    arrayWithSize(1));
            assertThat("Port is a member of port group",
                    portGroupPorts[0].getPortId(), equalTo(port.getId()));
            assertThat("Port group ID is correct",
                    portGroupPorts[0].getPortGroupId(),
                    equalTo(group1.getId()));

            // Retrieve port groups by port Id
            URI portSearchUri = UriBuilder.fromUri(
                    port.getPortGroups()).build();
            portGroupPorts = dtoResource
                    .getAndVerifyOk(portSearchUri,
                            APPLICATION_PORTGROUP_PORT_COLLECTION_JSON,
                            DtoPortGroupPort[].class);
            assertThat("Port has 1 groups.", portGroupPorts, arrayWithSize(1));
            assertThat("We expect the listed groups to match those we created.",
                    portGroupPorts, arrayContainingInAnyOrder(portGroupPort));

            // Retrieve port groups by port Id
            portSearchUri = UriBuilder.fromUri(app.getPortGroups())
                    .queryParam("port_id", port.getId()).build();
            groups = dtoResource
                    .getAndVerifyOk(portSearchUri,
                            APPLICATION_PORTGROUP_COLLECTION_JSON,
                            DtoPortGroup[].class);
            assertThat("Port has 1 groups.", groups, arrayWithSize(1));
            assertThat(
                    "We expect the listed groups to match those we created.",
                    groups, arrayContainingInAnyOrder(group1));

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
                    .getAndVerifyOk(tenantSearchUri,
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

    @RunWith(Parameterized.class)
    public static class TestCreatePortGroupBadRequest extends JerseyTest {

        private Topology topology;
        private DtoWebResource dtoResource;
        private final DtoPortGroup portGroup;
        private final String property;

        public TestCreatePortGroupBadRequest(DtoPortGroup portGroup,
                                             String property) {
            super(FuncTest.appDesc);
            this.portGroup = portGroup;
            this.property = property;
        }

        @Before
        public void setUp() {

            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);

            // Create a port group - useful for checking duplicate name error
            DtoPortGroup pg = new DtoPortGroup();
            pg.setName("pg1-name");
            pg.setTenantId("tenant1-id");

            topology = new Topology.Builder(dtoResource)
                    .create("pg1", pg).build();
        }

        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        @Parameterized.Parameters
        public static Collection<Object[]> data() {

            List<Object[]> params = new ArrayList<Object[]>();

            // Null name
            DtoPortGroup nullNamePortGroup = new DtoPortGroup();
            nullNamePortGroup.setTenantId("tenant1-id");
            params.add(new Object[] { nullNamePortGroup, "name" });

            // Blank name
            DtoPortGroup blankNamePortGroup = new DtoPortGroup();
            blankNamePortGroup.setName("");
            blankNamePortGroup.setTenantId("tenant1-id");
            params.add(new Object[] { blankNamePortGroup, "name" });

            // Long name
            StringBuilder longName = new StringBuilder(
                    PortGroup.MAX_PORT_GROUP_NAME_LEN + 1);
            for (int i = 0; i < PortGroup.MAX_PORT_GROUP_NAME_LEN + 1; i++) {
                longName.append("a");
            }
            DtoPortGroup longNamePortGroup = new DtoPortGroup();
            longNamePortGroup.setName(longName.toString());
            longNamePortGroup.setTenantId("tenant1-id");
            params.add(new Object[] { longNamePortGroup, "name" });

            // PortGroup name already exists
            DtoPortGroup dupNamePortGroup = new DtoPortGroup();
            dupNamePortGroup.setName("pg1-name");
            dupNamePortGroup.setTenantId("tenant1-id");
            params.add(new Object[]{dupNamePortGroup, "name"});

            // PortGroup with tenantID missing
            DtoPortGroup noTenant = new DtoPortGroup();
            noTenant.setName("noTenant-portGroup-name");
            params.add(new Object[] { noTenant, "tenantId" });

            return params;
        }

        @Test
        public void testBadInputCreate() {
            DtoApplication app = topology.getApplication();
            DtoError error = dtoResource.postAndVerifyBadRequest(
                    app.getPortGroups(), APPLICATION_PORTGROUP_JSON, portGroup);
            List<Map<String, String>> violations = error.getViolations();
            assertEquals(1, violations.size());
            assertEquals(property, violations.get(0).get("property"));
        }
    }
}

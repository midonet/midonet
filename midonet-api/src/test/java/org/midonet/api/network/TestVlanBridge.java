/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.network;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.midonet.api.rest_api.DtoWebResource;
import org.midonet.api.rest_api.FuncTest;
import org.midonet.api.rest_api.Topology;
import org.midonet.api.zookeeper.StaticMockDirectory;
import org.midonet.client.dto.DtoApplication;
import org.midonet.client.dto.DtoError;
import org.midonet.client.dto.DtoVlanBridge;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.midonet.api.VendorMediaType.APPLICATION_VLAN_BRIDGE_COLLECTION_JSON;
import static org.midonet.api.VendorMediaType.APPLICATION_VLAN_BRIDGE_JSON;

@RunWith(Enclosed.class)
public class TestVlanBridge {

    public static class TestVlanBridgeCrud extends JerseyTest {

        private DtoWebResource dtoResource;
        private Topology topology;

        public TestVlanBridgeCrud() {
            super(FuncTest.appDesc);
        }

        private Map<String, String> getTenantQueryParams(String tenantId) {
            Map<String, String> queryParams = new HashMap<String, String>();
            queryParams.put("tenant_id", tenantId);
            return queryParams;
        }

        @Before
        public void setUp() {

            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);
            topology = new Topology.Builder(dtoResource).build();
        }

        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        @Test
        public void testCrud() throws Exception {

            DtoApplication app = topology.getApplication();

            URI bridgesUri = app.getVlanBridges();
            assertNotNull(bridgesUri);
            DtoVlanBridge[] vlanBridges = dtoResource.getAndVerifyOk(bridgesUri,
                    getTenantQueryParams("tenant1"),
                    APPLICATION_VLAN_BRIDGE_COLLECTION_JSON, DtoVlanBridge[].class);
            assertEquals(0, vlanBridges.length);

            // Add a bridge
            DtoVlanBridge vlanBridge = new DtoVlanBridge();
            vlanBridge.setName("vlan-bridge1");
            vlanBridge.setTenantId("tenant1");

            DtoVlanBridge resVlanBridge = dtoResource.postAndVerifyCreated(bridgesUri,
                    APPLICATION_VLAN_BRIDGE_JSON, vlanBridge, DtoVlanBridge.class);
            assertNotNull(resVlanBridge.getId());
            assertNotNull(resVlanBridge.getUri());
            assertEquals(vlanBridge.getTenantId(), resVlanBridge.getTenantId());
            URI bridgeUri = resVlanBridge.getUri();

            // List the bridge
            vlanBridges = dtoResource.getAndVerifyOk(bridgesUri,
                    getTenantQueryParams("tenant1"),
                    APPLICATION_VLAN_BRIDGE_COLLECTION_JSON, DtoVlanBridge[].class);
            assertEquals(1, vlanBridges.length);
            assertEquals(resVlanBridge.getId(), vlanBridges[0].getId());

            //Update the bridge updatedBridge (name only)
            resVlanBridge.setName("vlan-bridge1-modified");
            DtoVlanBridge updatedBridge = dtoResource.putAndVerifyNoContent(
                    bridgeUri, APPLICATION_VLAN_BRIDGE_JSON, resVlanBridge,
                    DtoVlanBridge.class);
            assertNotNull(updatedBridge.getId());
            assertEquals(updatedBridge.getName(), "vlan-bridge1-modified");
            assertEquals(resVlanBridge.getTenantId(), updatedBridge.getTenantId());

            // Delete the bridge
            dtoResource.deleteAndVerifyNoContent(bridgeUri,
                                                 APPLICATION_VLAN_BRIDGE_JSON);

            // Verify that it's gone
            dtoResource.getAndVerifyNotFound(bridgeUri,
                    APPLICATION_VLAN_BRIDGE_JSON);

            // List should return an empty array
            vlanBridges = dtoResource.getAndVerifyOk(bridgesUri,
                    getTenantQueryParams("tenant1"),
                    APPLICATION_VLAN_BRIDGE_COLLECTION_JSON, DtoVlanBridge[].class);
            assertEquals(0, vlanBridges.length);
        }
    }

    @RunWith(Parameterized.class)
    public static class TestCreateBridgeBadRequest extends JerseyTest {

        private Topology topology;
        private DtoWebResource dtoResource;
        private final DtoVlanBridge vlanBridge;
        private final String property;

        public TestCreateBridgeBadRequest(DtoVlanBridge bridge, String property) {
            super(FuncTest.appDesc);
            this.vlanBridge = bridge;
            this.property = property;
        }

        @Before
        public void setUp() {

            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);

            // Create a bridge - useful for checking duplicate name error
            DtoVlanBridge b = new DtoVlanBridge();
            b.setName("vlan-bridge1-name");
            b.setTenantId("tenant1");

            topology = new Topology.Builder(dtoResource)
                    .create("vlan-bridge1", b).build();
        }

        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        @Parameters
        public static Collection<Object[]> data() {

            List<Object[]> params = new ArrayList<Object[]>();

            // Null name
            DtoVlanBridge nullNameBridge = new DtoVlanBridge();
            nullNameBridge.setTenantId("tenant1");
            params.add(new Object[] { nullNameBridge, "name" });

            // Blank name
            DtoVlanBridge blankNameBridge = new DtoVlanBridge();
            blankNameBridge.setName("");
            blankNameBridge.setTenantId("tenant1");
            params.add(new Object[] { blankNameBridge, "name" });

            // Long name
            StringBuilder longName = new StringBuilder(256);
            for (int i = 0; i < 256; i++) {
                longName.append("a");
            }
            DtoVlanBridge longNameBridge = new DtoVlanBridge();
            longNameBridge.setName(longName.toString());
            longNameBridge.setTenantId("tenant1");
            params.add(new Object[] { longNameBridge, "name" });

            // Bridge name already exists
            DtoVlanBridge dupNameBridge = new DtoVlanBridge();
            dupNameBridge.setName("vlan-bridge1-name");
            dupNameBridge.setTenantId("tenant1");
            params.add(new Object[] { dupNameBridge, "name" });

            // Bridge with tenantID missing
            DtoVlanBridge noTenant = new DtoVlanBridge();
            noTenant.setName("noTenant-bridge-name");
            params.add(new Object[] { noTenant, "tenantId" });

            return params;
        }

        @Test
        public void testBadInputCreate() {
            DtoApplication app = topology.getApplication();

            DtoError error = dtoResource.postAndVerifyBadRequest(
                    app.getVlanBridges(), APPLICATION_VLAN_BRIDGE_JSON,
                    vlanBridge);
            List<Map<String, String>> violations = error.getViolations();
            assertEquals(1, violations.size());
            assertEquals(property, violations.get(0).get("property"));
        }
    }

    @RunWith(Parameterized.class)
    public static class TestUpdateBridgeBadRequest extends JerseyTest {

        private final DtoVlanBridge testBridge;
        private final String property;
        private DtoWebResource dtoResource;
        private Topology topology;

        public TestUpdateBridgeBadRequest(DtoVlanBridge testBridge,
                                          String property) {
            super(FuncTest.appDesc);
            this.testBridge = testBridge;
            this.property = property;
        }

        @Before
        public void setUp() {

            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);

            // Create a bridge
            DtoVlanBridge b1 = new DtoVlanBridge();
            b1.setName("vlan-bridge1-name");
            b1.setTenantId("tenant1");

            // Create another bridge - useful for checking duplicate name error
            DtoVlanBridge b2 = new DtoVlanBridge();
            b2.setName("vlan-bridge2-name");
            b2.setTenantId("tenant1");

            topology = new Topology.Builder(dtoResource)
                    .create("vlan-bridge1", b1)
                    .create("vlan-bridge2", b2).build();
        }

        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        @Parameters
        public static Collection<Object[]> data() {

            List<Object[]> params = new ArrayList<Object[]>();

            // Null name
            DtoVlanBridge nullNameBridge = new DtoVlanBridge();
            nullNameBridge.setTenantId("tenant1");
            params.add(new Object[] { nullNameBridge, "name" });

            // Blank name
            DtoVlanBridge blankNameBridge = new DtoVlanBridge();
            blankNameBridge.setName("");
            blankNameBridge.setTenantId("tenant1");
            params.add(new Object[] { blankNameBridge, "name" });

            // Long name
            StringBuilder longName = new StringBuilder(256);
            for (int i = 0; i < 256; i++) {
                longName.append("a");
            }
            DtoVlanBridge longNameBridge = new DtoVlanBridge();
            longNameBridge.setName(longName.toString());
            longNameBridge.setTenantId("tenant1");
            params.add(new Object[] { longNameBridge, "name" });

            // Bridge name already exists
            DtoVlanBridge dupNameBridge = new DtoVlanBridge();
            dupNameBridge.setName("vlan-bridge2-name");
            dupNameBridge.setTenantId("tenant1");
            params.add(new Object[] { dupNameBridge, "name" });

            // Bridge with tenantID missing
            DtoVlanBridge noTenant = new DtoVlanBridge();
            noTenant.setName("noTenant-bridge-name");
            params.add(new Object[] { noTenant, "tenantId" });

            return params;
        }

        @Test
        public void testBadInput() {
            // Get the bridge
            DtoVlanBridge bridge = topology.getVlanBridge("vlan-bridge1");

            DtoError error = dtoResource.putAndVerifyBadRequest(
                    bridge.getUri(), APPLICATION_VLAN_BRIDGE_JSON, testBridge);
            List<Map<String, String>> violations = error.getViolations();
            assertEquals(1, violations.size());
            assertEquals(property, violations.get(0).get("property"));
        }
    }
}

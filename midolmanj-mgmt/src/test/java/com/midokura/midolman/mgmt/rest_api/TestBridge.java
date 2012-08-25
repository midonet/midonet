/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api;

import com.midokura.midolman.mgmt.data.dto.client.DtoApplication;
import com.midokura.midolman.mgmt.data.dto.client.DtoBridge;
import com.midokura.midolman.mgmt.data.dto.client.DtoError;
import com.midokura.midolman.mgmt.data.dto.client.DtoRuleChain;
import com.midokura.midolman.mgmt.data.zookeeper.StaticMockDirectory;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static com.midokura.midolman.mgmt.http.VendorMediaType.APPLICATION_BRIDGE_COLLECTION_JSON;
import static com.midokura.midolman.mgmt.http.VendorMediaType.APPLICATION_BRIDGE_JSON;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

@RunWith(Enclosed.class)
public class TestBridge {

    public static class TestBridgeCrud extends JerseyTest {

        private DtoWebResource dtoResource;
        private Topology topology;

        public TestBridgeCrud() {
            super(FuncTest.appDesc);
        }

        @Before
        public void setUp() {

            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);

            // Prepare chains that can be used to set to port
            DtoRuleChain chain1 = new DtoRuleChain();
            chain1.setName("chain1");
            chain1.setTenantId("tenant1");

            // Prepare another chain
            DtoRuleChain chain2 = new DtoRuleChain();
            chain2.setName("chain2");
            chain2.setName("tenant1");

            topology = new Topology.Builder(dtoResource)
                    .create("chain1", chain1)
                    .create("chain2", chain2).build();
        }

        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        @Test
        public void testCrud() throws Exception {

            DtoApplication app = topology.getApplication();
            DtoRuleChain chain1 = topology.getChain("chain1");
            DtoRuleChain chain2 = topology.getChain("chain2");

            URI bridgesUri = app.getBridges();
            assertNotNull(bridgesUri);
            DtoBridge[] bridges = dtoResource.getAndVerifyOk(bridgesUri,
                    APPLICATION_BRIDGE_COLLECTION_JSON, DtoBridge[].class);
            assertEquals(0, bridges.length);

            // Add a bridge
            DtoBridge bridge = new DtoBridge();
            bridge.setName("bridge1");
            bridge.setTenantId("tenant1");
            bridge.setInboundFilterId(chain1.getId());
            bridge.setOutboundFilterId(chain2.getId());

            DtoBridge resBridge = dtoResource.postAndVerifyCreated(bridgesUri,
                    APPLICATION_BRIDGE_JSON, bridge, DtoBridge.class);
            assertNotNull(resBridge.getId());
            assertNotNull(resBridge.getUri());
            // TODO: Implement 'equals' for DtoBridge
            assertEquals(bridge.getTenantId(), resBridge.getTenantId());
            assertEquals(bridge.getInboundFilterId(),
                    resBridge.getInboundFilterId());
            assertEquals(bridge.getOutboundFilterId(),
                    resBridge.getOutboundFilterId());
            URI bridgeUri = resBridge.getUri();

            // List the bridge
            bridges = dtoResource.getAndVerifyOk(bridgesUri,
                    APPLICATION_BRIDGE_COLLECTION_JSON, DtoBridge[].class);
            assertEquals(1, bridges.length);
            assertEquals(resBridge.getId(), bridges[0].getId());

            // Update the bridge
            resBridge.setName("bridge1-modified");
            resBridge.setInboundFilterId(chain2.getId());
            resBridge.setOutboundFilterId(chain1.getId());
            DtoBridge updatedBridge = dtoResource.putAndVerifyNoContent(
                    bridgeUri, APPLICATION_BRIDGE_JSON, resBridge,
                    DtoBridge.class);
            assertNotNull(updatedBridge.getId());
            assertEquals(updatedBridge.getName(), "bridge1-modified");
            assertEquals(resBridge.getTenantId(), updatedBridge.getTenantId());
            assertEquals(resBridge.getInboundFilterId(),
                    updatedBridge.getInboundFilterId());
            assertEquals(resBridge.getOutboundFilterId(),
                    updatedBridge.getOutboundFilterId());

            //Update the bridge updatedBridge (name only)
            updatedBridge.setName("bridge1-modified2");
            DtoBridge updatedBridge2 = dtoResource.putAndVerifyNoContent(
                    bridgeUri, APPLICATION_BRIDGE_JSON, updatedBridge,
                    DtoBridge.class);
            assertNotNull(updatedBridge2.getId());
            assertEquals(updatedBridge2.getName(), "bridge1-modified2");
            assertEquals(updatedBridge2.getTenantId(), updatedBridge.getTenantId());
            assertEquals(updatedBridge2.getInboundFilterId(),
                    updatedBridge.getInboundFilterId());
            assertEquals(updatedBridge2.getOutboundFilterId(),
                    updatedBridge.getOutboundFilterId());

            // Delete the bridge
            dtoResource.deleteAndVerifyNoContent(bridgeUri,
                    APPLICATION_BRIDGE_JSON);

            // Verify that it's gone
            dtoResource
                    .getAndVerifyNotFound(bridgeUri, APPLICATION_BRIDGE_JSON);

            // List should return an empty array
            bridges = dtoResource.getAndVerifyOk(bridgesUri,
                    APPLICATION_BRIDGE_COLLECTION_JSON, DtoBridge[].class);
            assertEquals(0, bridges.length);
        }
    }

    @RunWith(Parameterized.class)
    public static class TestCreateBridgeBadRequest extends JerseyTest {

        private Topology topology;
        private DtoWebResource dtoResource;
        private final DtoBridge bridge;
        private final String property;

        public TestCreateBridgeBadRequest(DtoBridge bridge, String property) {
            super(FuncTest.appDesc);
            this.bridge = bridge;
            this.property = property;
        }

        @Before
        public void setUp() {

            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);

            // Create a bridge - useful for checking duplicate name error
            DtoBridge b = new DtoBridge();
            b.setName("bridge1-name");
            b.setTenantId("tenant1");

            topology = new Topology.Builder(dtoResource)
                    .create("bridge1", b).build();
        }

        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        @Parameters
        public static Collection<Object[]> data() {

            List<Object[]> params = new ArrayList<Object[]>();

            // Null name
            DtoBridge nullNameBridge = new DtoBridge();
            params.add(new Object[] { nullNameBridge, "name" });

            // Blank name
            DtoBridge blankNameBridge = new DtoBridge();
            blankNameBridge.setName("");
            params.add(new Object[] { blankNameBridge, "name" });

            // Long name
            StringBuilder longName = new StringBuilder(256);
            for (int i = 0; i < 256; i++) {
                longName.append("a");
            }
            DtoBridge longNameBridge = new DtoBridge();
            blankNameBridge.setName(longName.toString());
            params.add(new Object[] { longNameBridge, "name" });

            // Bridge name already exists
            DtoBridge dupNameBridge = new DtoBridge();
            dupNameBridge.setName("bridge1-name");
            params.add(new Object[] { dupNameBridge, "name" });

            return params;
        }

        @Test
        public void testBadInputCreate() {
            DtoApplication app = topology.getApplication();

            DtoError error = dtoResource.postAndVerifyBadRequest(
                    app.getBridges(), APPLICATION_BRIDGE_JSON, bridge);
            List<Map<String, String>> violations = error.getViolations();
            assertEquals(1, violations.size());
            assertEquals(property, violations.get(0).get("property"));
        }
    }

    @RunWith(Parameterized.class)
    public static class TestUpdateBridgeBadRequest extends JerseyTest {

        private final DtoBridge testBridge;
        private final String property;
        private DtoWebResource dtoResource;
        private Topology topology;

        public TestUpdateBridgeBadRequest(DtoBridge testBridge, String property) {
            super(FuncTest.appDesc);
            this.testBridge = testBridge;
            this.property = property;
        }

        @Before
        public void setUp() {

            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);

            // Create a bridge
            DtoBridge b1 = new DtoBridge();
            b1.setName("bridge1-name");
            b1.setTenantId("tenant1");

            // Create another bridge - useful for checking duplicate name error
            DtoBridge b2 = new DtoBridge();
            b2.setName("bridge2-name");
            b2.setTenantId("tenant1");

            topology = new Topology.Builder(dtoResource)
                    .create("bridge1", b1)
                    .create("bridge2", b2).build();
        }

        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        @Parameters
        public static Collection<Object[]> data() {

            List<Object[]> params = new ArrayList<Object[]>();

            // Null name
            DtoBridge nullNameBridge = new DtoBridge();
            params.add(new Object[] { nullNameBridge, "name" });

            // Blank name
            DtoBridge blankNameBridge = new DtoBridge();
            blankNameBridge.setName("");
            params.add(new Object[] { blankNameBridge, "name" });

            // Long name
            StringBuilder longName = new StringBuilder(256);
            for (int i = 0; i < 256; i++) {
                longName.append("a");
            }
            DtoBridge longNameBridge = new DtoBridge();
            blankNameBridge.setName(longName.toString());
            params.add(new Object[] { longNameBridge, "name" });

            // Bridge name already exists
            DtoBridge dupNameBridge = new DtoBridge();
            dupNameBridge.setName("bridge2-name");
            params.add(new Object[] { dupNameBridge, "name" });

            return params;
        }

        @Test
        public void testBadInput() {
            // Get the bridge
            DtoBridge bridge = topology.getBridge("bridge1");

            DtoError error = dtoResource.putAndVerifyBadRequest(
                    bridge.getUri(), APPLICATION_BRIDGE_JSON, testBridge);
            List<Map<String, String>> violations = error.getViolations();
            assertEquals(1, violations.size());
            assertEquals(property, violations.get(0).get("property"));
        }
    }
}
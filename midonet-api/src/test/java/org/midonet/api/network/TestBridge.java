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
import java.util.UUID;

import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;
import org.codehaus.jackson.type.JavaType;
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
import org.midonet.client.VendorMediaType;
import org.midonet.client.dto.*;

import javax.ws.rs.core.UriBuilder;

import static org.hamcrest.Matchers.*;
import static org.hamcrest.Matchers.equalTo;
import static org.midonet.api.VendorMediaType.*;
import static org.junit.Assert.*;

@RunWith(Enclosed.class)
public class TestBridge {

    public static class TestBridgeList extends JerseyTest {

        private Topology topology;
        private DtoWebResource dtoResource;

        public TestBridgeList() {
            super(FuncTest.appDesc);
        }

        private void addActualBridges(Topology.Builder builder, String tenantId,
                                      int count) {
            for (int i = 0 ; i < count ; i++) {
                DtoBridge bridge = new DtoBridge();
                String tag = Integer.toString(i) + tenantId;
                bridge.setName(tag);
                bridge.setTenantId(tenantId);
                builder.create(tag, bridge);
            }
        }

        private DtoBridge getExpectedBridge(URI bridgesUri, String tag) {

            DtoBridge b = topology.getBridge(tag);
            String uri = bridgesUri.toString() + "/" + b.getId();

            // Make sure you set the non-ID fields to values you expect
            b.setName(tag);
            b.setUri(UriBuilder.fromUri(uri).build());
            b.setArpTable(UriBuilder.fromUri(uri + "/arp_table").build());
            b.setDhcpSubnet6s(UriBuilder.fromUri(uri + "/dhcpV6").build());
            b.setDhcpSubnets(UriBuilder.fromUri(uri + "/dhcp").build());
            b.setMacTable(UriBuilder.fromUri(uri + "/mac_table").build());
            b.setPorts(UriBuilder.fromUri(uri + "/ports").build());
            b.setPeerPorts(UriBuilder.fromUri(uri + "/peer_ports").build());

            return b;
        }

        private List<DtoBridge> getExpectedBridges(URI bridgesUri,
                                                   String tenantId,
                                                   int startTagNum,
                                                   int endTagNum) {
            List<DtoBridge> bridges = new ArrayList<DtoBridge>();

            for (int i = startTagNum; i <= endTagNum; i++) {
                String tag = Integer.toString(i) + tenantId;
                DtoBridge b = getExpectedBridge(bridgesUri, tag);
                bridges.add(b);
            }

            return bridges;
        }

        @Before
        public void setUp() {

            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);

            Topology.Builder builder = new Topology.Builder(dtoResource);

            // Create 5 bridges for tenant 0
            addActualBridges(builder, "tenant0", 5);

            // Create 5 bridges for tenant 1
            addActualBridges(builder, "tenant1", 5);

            topology = builder.build();
        }

        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        @Test
        public void testListAllBridges() throws Exception {

            // Get the expected list of DtoBridge objects
            DtoApplication app = topology.getApplication();
            List<DtoBridge> expected = getExpectedBridges(app.getBridges(),
                    "tenant0", 0, 4);
            expected.addAll(getExpectedBridges(
                    app.getBridges(),"tenant1", 0, 4));

            // Get the actual DtoBridge objects
            String actualRaw = dtoResource.getAndVerifyOk(app.getBridges(),
                    VendorMediaType.APPLICATION_BRIDGE_COLLECTION_JSON,
                    String.class);
            JavaType type = FuncTest.objectMapper.getTypeFactory()
                    .constructParametricType(List.class, DtoBridge.class);
            List<DtoBridge> actual = FuncTest.objectMapper.readValue(
                    actualRaw, type);

            // Compare the actual and expected
            assertThat(actual, hasSize(expected.size()));
            assertThat(actual, containsInAnyOrder(expected.toArray()));
        }

        @Test
        public void testListBridgesPerTenant() throws Exception {

            // Get the expected list of DtoBridge objects
            DtoApplication app = topology.getApplication();
            DtoTenant tenant = topology.getTenant("tenant0");
            List<DtoBridge> expected = getExpectedBridges(app.getBridges(),
                    tenant.getId(), 0, 4);

            // Get the actual DtoBridge objects
            String actualRaw = dtoResource.getAndVerifyOk(tenant.getBridges(),
                    VendorMediaType.APPLICATION_BRIDGE_COLLECTION_JSON,
                    String.class);
            JavaType type = FuncTest.objectMapper.getTypeFactory()
                    .constructParametricType(List.class, DtoBridge.class);
            List<DtoBridge> actual = FuncTest.objectMapper.readValue(
                    actualRaw, type);

            // Compare the actual and expected
            assertThat(actual, hasSize(expected.size()));
            assertThat(actual, containsInAnyOrder(expected.toArray()));
        }
    }

    public static class TestBridgeCrud extends JerseyTest {

        private DtoWebResource dtoResource;
        private Topology topology;

        public TestBridgeCrud() {
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

            // Prepare chains that can be used to set to port
            DtoRuleChain chain1 = new DtoRuleChain();
            chain1.setName("chain1");
            chain1.setTenantId("tenant1");

            // Prepare another chain
            DtoRuleChain chain2 = new DtoRuleChain();
            chain2.setName("chain2");
            chain2.setTenantId("tenant1");

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
                    getTenantQueryParams("tenant1"),
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
                    getTenantQueryParams("tenant1"),
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
            assertEquals(updatedBridge2.getTenantId(),
                    updatedBridge.getTenantId());
            assertEquals(updatedBridge2.getInboundFilterId(),
                    updatedBridge.getInboundFilterId());
            assertEquals(updatedBridge2.getOutboundFilterId(),
                    updatedBridge.getOutboundFilterId());

            // Delete the bridge
            dtoResource.deleteAndVerifyNoContent(bridgeUri,
                    APPLICATION_BRIDGE_JSON);

            // Verify that it's gone
            dtoResource.getAndVerifyNotFound(bridgeUri,
                    APPLICATION_BRIDGE_JSON);

            // List should return an empty array
            bridges = dtoResource.getAndVerifyOk(bridgesUri,
                    getTenantQueryParams("tenant1"),
                    APPLICATION_BRIDGE_COLLECTION_JSON, DtoBridge[].class);
            assertEquals(0, bridges.length);
        }

        @Test
        public void testMacTable() throws Exception {
            DtoApplication app = topology.getApplication();

            // Add a bridge
            DtoBridge bridge = new DtoBridge();
            bridge.setName("bridge1");
            bridge.setTenantId("tenant1");
            bridge = dtoResource.postAndVerifyCreated(app.getBridges(),
                APPLICATION_BRIDGE_JSON, bridge, DtoBridge.class);
            assertNotNull(bridge.getId());
            assertNotNull(bridge.getUri());

            // Create a port on the bridge and add a Mac-Port mapping
            DtoBridgePort p1 = new DtoBridgePort();
            p1 = dtoResource.postAndVerifyCreated(bridge.getPorts(),
                APPLICATION_PORT_JSON, p1, DtoBridgePort.class);
            assertNotNull(p1.getId());
            DtoMacPort mp1 = new DtoMacPort("02:11:22:33:44:55", p1.getId());
            mp1 = dtoResource.postAndVerifyCreated(bridge.getMacTable(),
                APPLICATION_MAC_PORT_JSON, mp1, DtoMacPort.class);

            // Create a second port and Mac-Port mapping
            DtoBridgePort p2 = new DtoBridgePort();
            p2 = dtoResource.postAndVerifyCreated(bridge.getPorts(),
                APPLICATION_PORT_JSON, p2, DtoBridgePort.class);
            assertNotNull(p2.getId());
            DtoMacPort mp2 = new DtoMacPort("02:11:22:33:44:66", p2.getId());
            mp2 = dtoResource.postAndVerifyCreated(bridge.getMacTable(),
                APPLICATION_MAC_PORT_JSON, mp2, DtoMacPort.class);

            // List the MacPort entries.
            DtoMacPort[] entries = dtoResource.getAndVerifyOk(
                bridge.getMacTable(), APPLICATION_MAC_PORT_COLLECTION_JSON,
                DtoMacPort[].class);
            assertThat("Expect 2 listed entries.",
                entries, arrayWithSize(2));
            assertThat("The listed entries to match those we created.",
                entries, arrayContainingInAnyOrder(mp1, mp2));

            // Delete the second entry.
            dtoResource.deleteAndVerifyNoContent(mp2.getUri(),
                APPLICATION_MAC_PORT_JSON);
            // List the MacPort entries.
            entries = dtoResource.getAndVerifyOk(
                bridge.getMacTable(), APPLICATION_MAC_PORT_COLLECTION_JSON,
                DtoMacPort[].class);
            assertThat("Expect 1 entry.",
                entries, arrayWithSize(1));
            assertThat("The listed entries to match those we created.",
                entries, arrayContainingInAnyOrder(mp1));

            // Try to create a MacPort entry with a bad MAC address
            mp2 = new DtoMacPort("02:11:NONSENSE", p2.getId());
            dtoResource.postAndVerifyBadRequest(bridge.getMacTable(),
                APPLICATION_MAC_PORT_JSON, mp2);
            // Try to create a MacPort entry with a null MAC address
            mp2.setMacAddr(null);
            dtoResource.postAndVerifyBadRequest(bridge.getMacTable(),
                APPLICATION_MAC_PORT_JSON, mp2);
            // Try to create a MacPort entry with a null Port ID.
            mp2 = new DtoMacPort("02:aa:bb:cc:dd:ee", null);
            dtoResource.postAndVerifyBadRequest(bridge.getMacTable(),
                APPLICATION_MAC_PORT_JSON, mp2);
            // Try to create a MacPort entry with a bogus port ID.
            mp2 = new DtoMacPort("02:aa:bb:cc:dd:ee", UUID.randomUUID());
            dtoResource.postAndVerifyBadRequest(bridge.getMacTable(),
                APPLICATION_MAC_PORT_JSON, mp2);
        }

        @Test
        public void testArpTable() throws Exception {
            DtoApplication app = topology.getApplication();

            // Add a bridge
            DtoBridge bridge = new DtoBridge();
            bridge.setName("bridge1");
            bridge.setTenantId("tenant1");
            bridge = dtoResource.postAndVerifyCreated(app.getBridges(),
                APPLICATION_BRIDGE_JSON, bridge, DtoBridge.class);
            assertNotNull(bridge.getId());
            assertNotNull(bridge.getUri());

            // Add an Arp Entry
            DtoIP4MacPair mp1 = new DtoIP4MacPair(
                "10.0.0.2", "02:11:22:33:44:55");
            mp1 = dtoResource.postAndVerifyCreated(bridge.getArpTable(),
                APPLICATION_IP4_MAC_JSON, mp1, DtoIP4MacPair.class);

            // Add a second Arp Entry
            DtoIP4MacPair mp2 = new DtoIP4MacPair(
                "10.0.0.3", "02:11:22:33:44:66");
            mp2 = dtoResource.postAndVerifyCreated(bridge.getArpTable(),
                APPLICATION_IP4_MAC_JSON, mp2, DtoIP4MacPair.class);

            // List the Arp entries.
            DtoIP4MacPair[] entries = dtoResource.getAndVerifyOk(
                bridge.getArpTable(), APPLICATION_IP4_MAC_COLLECTION_JSON,
                DtoIP4MacPair[].class);
            assertThat("Expect 2 listed entries.",
                entries, arrayWithSize(2));
            assertThat("The listed entries should match those we created.",
                entries, arrayContainingInAnyOrder(mp1, mp2));

            // Delete the second entry.
            dtoResource.deleteAndVerifyNoContent(mp2.getUri(),
                APPLICATION_IP4_MAC_JSON);
            // List the Arp entries.
            entries = dtoResource.getAndVerifyOk(
                bridge.getArpTable(), APPLICATION_IP4_MAC_COLLECTION_JSON,
                DtoIP4MacPair[].class);
            assertThat("Expect 1 entry.",
                entries, arrayWithSize(1));
            assertThat("The listed entries should match those we created.",
                entries, arrayContainingInAnyOrder(mp1));

            // Try to create an Arp entry with a bad MAC address
            mp2 = new DtoIP4MacPair("1.2.3.4", "02:11:NONSENSE");
            dtoResource.postAndVerifyBadRequest(bridge.getArpTable(),
                APPLICATION_IP4_MAC_JSON, mp2);
            // Try to create an Arp entry with a null MAC address
            mp2.setMac(null);
            dtoResource.postAndVerifyBadRequest(bridge.getArpTable(),
                APPLICATION_IP4_MAC_JSON, mp2);
            // Try to create an Arp entry with a null IP4.
            mp2 = new DtoIP4MacPair(null, "02:aa:bb:cc:dd:ee");
            dtoResource.postAndVerifyBadRequest(bridge.getArpTable(),
                APPLICATION_IP4_MAC_JSON, mp2);
            // Try to create Arp entries with bogus IP4 addresses.
            mp2 = new DtoIP4MacPair("1.2.3", "02:aa:bb:cc:dd:ee");
            dtoResource.postAndVerifyBadRequest(bridge.getArpTable(),
                APPLICATION_IP4_MAC_JSON, mp2);
            mp2 = new DtoIP4MacPair("1000.2.3.4", "02:aa:bb:cc:dd:ee");
            dtoResource.postAndVerifyBadRequest(bridge.getArpTable(),
                APPLICATION_IP4_MAC_JSON, mp2);
            mp2 = new DtoIP4MacPair("host", "02:aa:bb:cc:dd:ee");
            dtoResource.postAndVerifyBadRequest(bridge.getArpTable(),
                APPLICATION_IP4_MAC_JSON, mp2);
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
            nullNameBridge.setTenantId("tenant1");
            params.add(new Object[] { nullNameBridge, "name" });

            // Blank name
            DtoBridge blankNameBridge = new DtoBridge();
            blankNameBridge.setName("");
            blankNameBridge.setTenantId("tenant1");
            params.add(new Object[] { blankNameBridge, "name" });

            // Long name
            StringBuilder longName = new StringBuilder(256);
            for (int i = 0; i < 256; i++) {
                longName.append("a");
            }
            DtoBridge longNameBridge = new DtoBridge();
            longNameBridge.setName(longName.toString());
            longNameBridge.setTenantId("tenant1");
            params.add(new Object[] { longNameBridge, "name" });

            // Bridge name already exists
            DtoBridge dupNameBridge = new DtoBridge();
            dupNameBridge.setName("bridge1-name");
            dupNameBridge.setTenantId("tenant1");
            params.add(new Object[] { dupNameBridge, "name" });

            // Bridge with tenantID missing
            DtoBridge noTenant = new DtoBridge();
            noTenant.setName("noTenant-bridge-name");
            params.add(new Object[] { noTenant, "tenantId" });

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

        public TestUpdateBridgeBadRequest(DtoBridge testBridge,
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
            nullNameBridge.setTenantId("tenant1");
            params.add(new Object[] { nullNameBridge, "name" });

            // Blank name
            DtoBridge blankNameBridge = new DtoBridge();
            blankNameBridge.setName("");
            blankNameBridge.setTenantId("tenant1");
            params.add(new Object[] { blankNameBridge, "name" });

            // Long name
            StringBuilder longName = new StringBuilder(256);
            for (int i = 0; i < 256; i++) {
                longName.append("a");
            }
            DtoBridge longNameBridge = new DtoBridge();
            longNameBridge.setName(longName.toString());
            longNameBridge.setTenantId("tenant1");
            params.add(new Object[] { longNameBridge, "name" });

            // Bridge name already exists
            DtoBridge dupNameBridge = new DtoBridge();
            dupNameBridge.setName("bridge2-name");
            dupNameBridge.setTenantId("tenant1");
            params.add(new Object[] { dupNameBridge, "name" });

            // Bridge with tenantID missing
            DtoBridge noTenant = new DtoBridge();
            noTenant.setName("noTenant-bridge-name");
            params.add(new Object[] { noTenant, "tenantId" });

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

/*
 * Copyright 2015 Midokura SARL
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
package org.midonet.cluster.rest_api.network;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.ws.rs.core.UriBuilder;

import com.fasterxml.jackson.databind.JavaType;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;

import org.midonet.cluster.rest_api.ResourceUriBuilder;
import org.midonet.cluster.rest_api.rest_api.DtoWebResource;
import org.midonet.cluster.rest_api.rest_api.RestApiTestBase;
import org.midonet.cluster.rest_api.rest_api.Topology;
import org.midonet.client.dto.DtoApplication;
import org.midonet.client.dto.DtoBridge;
import org.midonet.client.dto.DtoBridgePort;
import org.midonet.client.dto.DtoError;
import org.midonet.client.dto.DtoIP4MacPair;
import org.midonet.client.dto.DtoMacPort;
import org.midonet.client.dto.DtoPort;
import org.midonet.client.dto.DtoRuleChain;
import org.midonet.cluster.auth.AuthService;
import org.midonet.cluster.auth.MockAuthService;
import org.midonet.cluster.rest_api.models.MacPort;
import org.midonet.cluster.rest_api.models.Tenant;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContainingInAnyOrder;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.midonet.cluster.rest_api.rest_api.FuncTest._injector;
import static org.midonet.cluster.rest_api.rest_api.FuncTest.appDesc;
import static org.midonet.cluster.rest_api.rest_api.FuncTest.objectMapper;
import static org.midonet.cluster.data.Bridge.UNTAGGED_VLAN_ID;
import static org.midonet.cluster.rest_api.validation.MessageProperty.BRIDGE_HAS_MAC_PORT;
import static org.midonet.cluster.rest_api.validation.MessageProperty.BRIDGE_HAS_VLAN;
import static org.midonet.cluster.rest_api.validation.MessageProperty.MAC_PORT_ON_BRIDGE;
import static org.midonet.cluster.rest_api.validation.MessageProperty.MAC_URI_FORMAT;
import static org.midonet.cluster.rest_api.validation.MessageProperty.VLAN_ID_MATCHES_PORT_VLAN_ID;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_BRIDGE_COLLECTION_JSON_V4;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_BRIDGE_JSON_V4;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_IP4_MAC_COLLECTION_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_IP4_MAC_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_JSON_V5;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_MAC_PORT_COLLECTION_JSON_V2;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_MAC_PORT_JSON_V2;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_PORT_V3_JSON;

@RunWith(Enclosed.class)
public class TestBridge {

    public static class TestBridgeList extends JerseyTest {

        private Topology topology;
        private DtoWebResource dtoWebResource;

        public TestBridgeList() {
            super(appDesc);
        }

        private void addActualBridges(Topology.Builder builder, String tenantId,
                                      int count) {
            for (int i = 0 ; i < count ; i++) {
                // In the new storage stack we don't store tenants in MidoNet
                // and instead fetch them directly from the AuthService, so
                // let's add them there.
                AuthService as = _injector.getInstance(AuthService.class);
                ((MockAuthService)as).addTenant(tenantId, tenantId);
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
            List<DtoBridge> bridges = new ArrayList<>();

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
            dtoWebResource = new DtoWebResource(resource);

            Topology.Builder builder = new Topology.Builder(dtoWebResource);

            // Create 5 bridges for tenant 0
            addActualBridges(builder, "tenant0", 5);

            // Create 5 bridges for tenant 1
            addActualBridges(builder, "tenant1", 5);

            topology = builder.build();
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
            String actualRaw = dtoWebResource.getAndVerifyOk(app.getBridges(),
                    APPLICATION_BRIDGE_COLLECTION_JSON_V4(), String.class);
            JavaType type = objectMapper.getTypeFactory()
                    .constructParametrizedType(List.class, List.class,
                                               DtoBridge.class);
            List<DtoBridge> actual = objectMapper.readValue(
                    actualRaw, type);

            // Compare the actual and expected
            assertThat(actual, hasSize(expected.size()));
            assertThat(actual, containsInAnyOrder(expected.toArray()));
        }

        @Test
        public void testListBridgesPerTenant() throws Exception {

            // Get the expected list of DtoBridge objects
            DtoApplication app = topology.getApplication();
            Tenant tenant = topology.getTenant("tenant0");
            List<DtoBridge> expected = getExpectedBridges(app.getBridges(),
                    tenant.id, 0, 4);

            // Get the actual DtoBridge objects
            String actualRaw = dtoWebResource.getAndVerifyOk(tenant.getBridges(),
                    APPLICATION_BRIDGE_COLLECTION_JSON_V4(), String.class);
            JavaType type = objectMapper.getTypeFactory()
                    .constructParametrizedType(List.class, List.class,
                                               DtoBridge.class);
            List<DtoBridge> actual = objectMapper.readValue(
                    actualRaw, type);

            // Compare the actual and expected
            assertThat(actual, hasSize(expected.size()));
            assertThat(actual, containsInAnyOrder(expected.toArray()));
        }
    }

    public static class TestBridgeCrud extends RestApiTestBase {

        private String mac0 = "01:23:45:67:89:00";
        private String mac1 = "01:23:45:67:89:01";
        private String mac2 = "01:23:45:67:89:02";
        private String mac3 = "01:23:45:67:89:03";

        public TestBridgeCrud() {
            super(appDesc);
        }

        @Override
        protected void extendTopology(Topology.Builder builder) {
            super.extendTopology(builder);

            // Prepare chains that can be used to set to port
            DtoRuleChain chain1 = new DtoRuleChain();
            chain1.setName("chain1");
            chain1.setTenantId("tenant1");
            builder.create("chain1", chain1);

            // Prepare another chain
            DtoRuleChain chain2 = new DtoRuleChain();
            chain2.setName("chain2");
            chain2.setTenantId("tenant1");
            builder.create("chain2", chain2);
        }

        private Map<String, String> getTenantQueryParams(String tenantId) {
            Map<String, String> queryParams = new HashMap<>();
            queryParams.put("tenant_id", tenantId);
            return queryParams;
        }

        private DtoBridgePort addTrunkPort(DtoBridge bridge) {
            DtoBridgePort port = new DtoBridgePort();
            port = dtoResource.postAndVerifyCreated(bridge.getPorts(),
                    APPLICATION_PORT_V3_JSON(), port, DtoBridgePort.class);
            assertNotNull(port.getId());
            return port;
        }

        private DtoBridgePort addInteriorPort(
                DtoBridge bridge, Short vlanId) {
            DtoBridgePort port = new DtoBridgePort();
            port.setVlanId(vlanId);
            port = dtoResource.postAndVerifyCreated(bridge.getPorts(),
                    APPLICATION_PORT_V3_JSON(), port, DtoBridgePort.class);
            assertNotNull(port.getId());
            return port;
        }

        private DtoMacPort addMacPortV1(
                DtoBridge bridge, String macAddr, UUID portId)
                throws URISyntaxException {
            DtoMacPort macPort = new DtoMacPort(macAddr, portId);
            return dtoResource.postAndVerifyCreated(
                ResourceUriBuilder.getMacTable(bridge.getUri(), null),
                APPLICATION_MAC_PORT_JSON_V2(), macPort, DtoMacPort.class);
        }

        private DtoMacPort addMacPort(DtoBridge bridge, Short vlanId,
                                      String macAddr, UUID portId)
                throws URISyntaxException {
            DtoMacPort macPort = new DtoMacPort(macAddr, portId, null);
            return dtoResource.postAndVerifyCreated(
                    ResourceUriBuilder.getMacTable(bridge.getUri(), vlanId),
                    APPLICATION_MAC_PORT_JSON_V2(), macPort, DtoMacPort.class);
        }

        private DtoError addInvalidMacPort(DtoBridge bridge, Short vlanId,
                                           String macAddr, UUID portId)
                throws URISyntaxException {
            DtoMacPort macPort = new DtoMacPort(macAddr, portId, vlanId);
            return dtoResource.postAndVerifyBadRequest(
                ResourceUriBuilder.getMacTable(bridge.getUri(), vlanId),
                APPLICATION_MAC_PORT_JSON_V2(), macPort);
        }

        private void deleteMacPort(DtoBridge bridge, Short vlanId,
                              String macAddr, UUID portId)
                throws URISyntaxException {
            MacPort mp = new MacPort(app.getUri(), bridge.getId(), macAddr,
                                     portId, vlanId);
            dtoResource.deleteAndVerifyNoContent(mp.getUri(),
                                                 APPLICATION_MAC_PORT_JSON_V2());
        }

        private DtoError deleteMacPortWithNotFoundError(
                UUID bridgeId, Short vlanId, String macAddr, UUID portId)
                throws URISyntaxException {
            MacPort mp = new MacPort(app.getUri(), bridgeId, macAddr,
                                     portId, vlanId);
            return dtoResource.deleteAndVerifyNotFound(mp.getUri(),
                                                       APPLICATION_JSON_V5());
        }

        private DtoMacPort getMacPort(UUID bridgeId, Short vlanId,
                                      String macAddr, UUID portId)
                throws URISyntaxException {
            MacPort mp = new MacPort(app.getUri(), bridgeId, macAddr,
                                     portId, vlanId);
            return dtoResource.getAndVerifyOk(mp.getUri(),
                                              APPLICATION_MAC_PORT_JSON_V2(),
                                              DtoMacPort.class);
        }

        private DtoError getMacPortWithNotFoundError(
                UUID bridgeId, Short vlanId, String macAddr, UUID portId)
                throws URISyntaxException {
            MacPort mp = new MacPort(app.getUri(), bridgeId, macAddr,
                                     portId, vlanId);
            return dtoResource.getAndVerifyNotFound(mp.getUri(),
                                                    APPLICATION_MAC_PORT_JSON_V2());
        }

        private DtoError getMacPortWithBadRequestError(
                UUID bridgeId, Short vlanId, String macAddr, UUID portId)
                throws URISyntaxException {
            MacPort mp = new MacPort(app.getUri(), bridgeId, macAddr,
                                     portId, vlanId);
            return dtoResource.getAndVerifyBadRequest(mp.getUri(),
                                                      APPLICATION_MAC_PORT_JSON_V2());
        }

        private DtoMacPort[] getMacTable(DtoBridge bridge) {
            return dtoResource.getAndVerifyOk(
                    bridge.getMacTable(),
                    APPLICATION_MAC_PORT_COLLECTION_JSON_V2(),
                    DtoMacPort[].class);
        }

        private DtoMacPort[] getMacTable(DtoBridge bridge, Short vlanId)
                throws URISyntaxException {
            URI macTableUri = (vlanId == null)
                              ? bridge.getMacTable()
                              : new URI(bridge.getVlanMacTableTemplate()
                .replace("{vlanId}", Short.toString(vlanId)));
            return dtoResource.getAndVerifyOk(
                    macTableUri,
                    APPLICATION_MAC_PORT_COLLECTION_JSON_V2(),
                    DtoMacPort[].class);
        }

        @Test
        public void testDuplicateName() throws Exception {

            DtoApplication app = topology.getApplication();
            URI bridgesUri = app.getBridges();

            DtoBridge bridge = new DtoBridge();
            bridge.setName("name");
            bridge.setTenantId("tenant1");

            DtoBridge b1 = dtoResource.postAndVerifyCreated(bridgesUri,
                    APPLICATION_BRIDGE_JSON_V4(), bridge, DtoBridge.class);

            // Duplicate name should be allowed
            bridge = new DtoBridge();
            bridge.setName("name");
            bridge.setTenantId("tenant1");
            DtoBridge b2 = dtoResource.postAndVerifyCreated(bridgesUri,
                    APPLICATION_BRIDGE_JSON_V4(), bridge, DtoBridge.class);

            // Deletion should work
            dtoResource.deleteAndVerifyNoContent(b1.getUri(),
                    APPLICATION_BRIDGE_JSON_V4());

            dtoResource.deleteAndVerifyNoContent(b2.getUri(),
                                                 APPLICATION_BRIDGE_JSON_V4());
        }

        @Test
        public void testBridgeCreateWithNoName() throws Exception {
            DtoBridge bridge = postBridge(null);
            assertNull(bridge.getName());
        }

        @Test
        public void testBridgeUpdateWithNoName() throws Exception {
            DtoBridge bridge = postBridge("foo");
            assertEquals("foo", bridge.getName());

            bridge.setName(null);
            bridge = dtoResource.putAndVerifyNoContent(
                bridge.getUri(), APPLICATION_BRIDGE_JSON_V4(), bridge,
                DtoBridge.class);
            assertNull(bridge.getName());
        }

        @Test
        public void testBridgeCreateWithNoTenant() throws Exception {
            DtoError error = dtoResource.postAndVerifyBadRequest(
                app.getBridges(), APPLICATION_BRIDGE_JSON_V4(), new DtoBridge());

            assertValidationProperties(error, "tenantId");
        }

        @Test
        public void testBridgeUpdateWithNoTenant() throws Exception {
            DtoBridge bridge = postBridge("foo");

            bridge.setTenantId(null);
            DtoError error = dtoResource.putAndVerifyBadRequest(
                bridge.getUri(), APPLICATION_BRIDGE_JSON_V4(), bridge);
            assertValidationProperties(error, "tenantId");
        }

        @Test
        public void testEmptyStringName() throws Exception {

            URI bridgesUri = app.getBridges();

            // Empty name is also allowed
            DtoBridge bridge = new DtoBridge();
            bridge.setName("");
            bridge.setTenantId("tenant1");
            DtoBridge b3 = dtoResource.postAndVerifyCreated(bridgesUri,
                                                            APPLICATION_BRIDGE_JSON_V4(),
                                                            bridge,
                                                            DtoBridge.class);

            dtoResource.deleteAndVerifyNoContent(b3.getUri(),
                                                 APPLICATION_BRIDGE_JSON_V4());
        }

        @Test
        public void testCrud() throws Exception {

            DtoApplication app = topology.getApplication();
            DtoRuleChain chain1 = topology.getChain("chain1");
            DtoRuleChain chain2 = topology.getChain("chain2");

            URI bridgesUri = app.getBridges();
            assertNotNull(bridgesUri);
            DtoBridge[] bridges = dtoResource.getAndVerifyOk(bridgesUri,
                    APPLICATION_BRIDGE_COLLECTION_JSON_V4(), DtoBridge[].class);
            assertEquals(0, bridges.length);

            // Add a bridge
            DtoBridge bridge = new DtoBridge();
            bridge.setName("bridge1");
            bridge.setTenantId("tenant1");
            bridge.setInboundFilterId(chain1.getId());
            bridge.setOutboundFilterId(chain2.getId());

            DtoBridge resBridge = dtoResource.postAndVerifyCreated(bridgesUri,
                    APPLICATION_BRIDGE_JSON_V4(), bridge, DtoBridge.class);
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
                    APPLICATION_BRIDGE_COLLECTION_JSON_V4(), DtoBridge[].class);
            assertEquals(1, bridges.length);
            assertEquals(resBridge.getId(), bridges[0].getId());

            // Update the bridge
            resBridge.setName("bridge1-modified");
            resBridge.setAdminStateUp(false);
            resBridge.setInboundFilterId(chain2.getId());
            resBridge.setOutboundFilterId(chain1.getId());
            DtoBridge updatedBridge = dtoResource.putAndVerifyNoContent(
                    bridgeUri, APPLICATION_BRIDGE_JSON_V4(), resBridge,
                    DtoBridge.class);
            assertNotNull(updatedBridge.getId());
            assertEquals(updatedBridge.getName(), "bridge1-modified");
            assertFalse(updatedBridge.isAdminStateUp());
            assertEquals(resBridge.getTenantId(), updatedBridge.getTenantId());
            assertEquals(resBridge.getInboundFilterId(),
                    updatedBridge.getInboundFilterId());
            assertEquals(resBridge.getOutboundFilterId(),
                    updatedBridge.getOutboundFilterId());

            //Update the bridge updatedBridge (name only)
            updatedBridge.setName("bridge1-modified2");
            DtoBridge updatedBridge2 = dtoResource.putAndVerifyNoContent(
                    bridgeUri, APPLICATION_BRIDGE_JSON_V4(), updatedBridge,
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
                                                 APPLICATION_BRIDGE_JSON_V4());

            // Verify that it's gone
            dtoResource.getAndVerifyNotFound(bridgeUri,
                                             APPLICATION_BRIDGE_JSON_V4());

            // List should return an empty array
            bridges = dtoResource.getAndVerifyOk(bridgesUri,
                     APPLICATION_BRIDGE_COLLECTION_JSON_V4(), DtoBridge[].class);
            assertEquals(0, bridges.length);
        }

        @Test
        public void testMacTable() throws Exception {
            DtoBridge bridge = postBridge("bridge1");

            // Create a port on the bridge and add a Mac-Port mapping
            DtoBridgePort p1 = addTrunkPort(bridge);
            DtoMacPort mp1 = addMacPortV1(
                    bridge, "02:11:22:33:44:55", p1.getId());


            // Create a second port and Mac-Port mapping
            DtoBridgePort p2 = addTrunkPort(bridge);
            DtoMacPort mp2 = addMacPortV1(
                    bridge, "02:11:22:33:44:66", p2.getId());


            // List the MacPort entries.
            DtoMacPort[] entries = getMacTable(bridge);
            assertThat("Expect 2 listed entries.",
                entries, arrayWithSize(2));
            assertThat("The listed entries to match those we created.",
                entries, arrayContainingInAnyOrder(mp1, mp2));

            // Delete the second entry.
            dtoResource.deleteAndVerifyNoContent(mp2.getUri(),
                APPLICATION_MAC_PORT_JSON_V2());
            // List the MacPort entries.
            entries = getMacTable(bridge);
            assertThat("Expect 1 entry.",
                entries, arrayWithSize(1));
            assertThat("The listed entries to match those we created.",
                entries, arrayContainingInAnyOrder(mp1));

            // Try to create a MacPort entry with a bad MAC address
            mp2 = new DtoMacPort("02:11:NONSENSE", p2.getId());
            dtoResource.postAndVerifyBadRequest(bridge.getMacTable(),
                APPLICATION_MAC_PORT_JSON_V2(), mp2);
            // Try to create a MacPort entry with a null MAC address
            mp2.setMacAddr(null);
            dtoResource.postAndVerifyBadRequest(bridge.getMacTable(),
                APPLICATION_MAC_PORT_JSON_V2(), mp2);
            // Try to create a MacPort entry with a null Port ID.
            mp2 = new DtoMacPort("02:aa:bb:cc:dd:ee", null);
            dtoResource.postAndVerifyBadRequest(bridge.getMacTable(),
                APPLICATION_MAC_PORT_JSON_V2(), mp2);
            // Try to create a MacPort entry with a bogus port ID.
            mp2 = new DtoMacPort("02:aa:bb:cc:dd:ee", UUID.randomUUID());
            dtoResource.postAndVerifyBadRequest(bridge.getMacTable(),
                APPLICATION_MAC_PORT_JSON_V2(), mp2);
        }

        @Test
        public void testVlanMacTables() throws Exception {
            // Create bridge and ports.
            DtoBridge bridge = postBridge("bridge1");
            DtoBridgePort ip0 = addInteriorPort(bridge, null);
            DtoBridgePort ip1 = addInteriorPort(bridge, (short)1);
            DtoBridgePort ip2 = addInteriorPort(bridge, (short)2);
            DtoBridgePort ip3 = addInteriorPort(bridge, (short)3);
            DtoBridgePort tp = addTrunkPort(bridge);

            // Create mac-port mappings.
            DtoMacPort[] macPorts = new DtoMacPort[] {
                    new DtoMacPort(mac0, ip0.getId(), (short)0),
                    new DtoMacPort(mac1, tp.getId(), (short)0),
                    new DtoMacPort(mac2, tp.getId(), (short)0),
                    new DtoMacPort(mac3, tp.getId(), (short)0),
                    new DtoMacPort(mac0, ip1.getId(), (short)1),
                    new DtoMacPort(mac1, ip1.getId(), (short)1),
                    new DtoMacPort(mac2, tp.getId(), (short)1),
                    new DtoMacPort(mac3, tp.getId(), (short)1),
                    new DtoMacPort(mac0, ip2.getId(), (short)2),
                    new DtoMacPort(mac1, ip2.getId(), (short)2),
                    new DtoMacPort(mac2, ip2.getId(), (short)2),
                    new DtoMacPort(mac3, ip2.getId(), (short)2),
                    new DtoMacPort(mac0, tp.getId(), (short)3),
                    new DtoMacPort(mac1, tp.getId(), (short)3),
                    new DtoMacPort(mac2, tp.getId(), (short)3),
                    new DtoMacPort(mac3, tp.getId(), (short)3),
            };

            for (DtoMacPort macPort : macPorts) {
                DtoMacPort newMacPort = addMacPort(
                        bridge, macPort.getVlanId(),
                        macPort.getMacAddr(), macPort.getPortId());
                // To simplify equality checks for asserts.
                macPort.setUri(newMacPort.getUri());
            }

            // V1 response will have vlanId set to null.
            DtoMacPort[] v1MacPorts = new DtoMacPort[4];
            for (int i = 0; i < 4; i++) {
                DtoMacPort v2MacPort = macPorts[i];
                v1MacPorts[i] = new DtoMacPort(v2MacPort.getMacAddr(),
                                               v2MacPort.getPortId(), null);
                v1MacPorts[i].setUri(v2MacPort.getUri());
            }

            DtoMacPort[] macTable = getMacTable(bridge, null);
            assertEquals("Should return sixteen entries.", macTable.length, 16);

            assertThat("Should return all entries.",
                       macTable, arrayContainingInAnyOrder(macPorts));

            macTable = getMacTable(bridge, UNTAGGED_VLAN_ID);
            assertEquals("Should return four entries.", macTable.length, 4);
            assertThat("Should return the untagged entries.",
                    macTable, arrayContainingInAnyOrder(
                        Arrays.copyOfRange(macPorts, 0, 4)));

            macTable = getMacTable(bridge, (short)1);
            assertEquals("Should return four entries.", macTable.length, 4);
            assertThat("Should return the untagged entries.",
                       macTable, arrayContainingInAnyOrder(
                    Arrays.copyOfRange(macPorts, 4, 8)));

            // Try a VLAN that doesn't exist.
            dtoResource.getAndVerifyNotFound(
                ResourceUriBuilder.getMacTable(bridge.getUri(), (short) 4),
                APPLICATION_MAC_PORT_COLLECTION_JSON_V2());
        }

        @Test
        public void testVlanMacPortCreation() throws Exception {
            DtoBridge bridge = postBridge("bridge1");
            DtoBridgePort ip0 = addInteriorPort(bridge, null);
            DtoBridgePort ip1 = addInteriorPort(bridge, (short)1);
            DtoBridgePort tp = addTrunkPort(bridge);

            // Create MAC-port mappings for untagged VLAN and VLAN1
            // on their associated interior ports.
            addMacPort(bridge, null, mac0, ip0.getId());
            addMacPort(bridge, null, mac1, ip0.getId());
            addMacPort(bridge, (short) 1, mac0, ip1.getId());
            addMacPort(bridge, (short) 1, mac1, ip1.getId());

            // Create a MAC-port mapping with the VLAN ID in the MacPort object
            // not consistent with the VLAN ID in the path. The ID in the path
            // should take precedence.
            DtoMacPort macPort = new DtoMacPort(mac2, ip1.getId(), (short)2);
            macPort = dtoResource.postAndVerifyCreated(
                    ResourceUriBuilder.getMacTable(bridge.getUri(), (short)1),
                    APPLICATION_MAC_PORT_JSON_V2(), macPort, DtoMacPort.class);
            assertEquals(1, macPort.getVlanId().intValue());

            // Try to create MAC-port mapping with bridge-port mismatch.
            DtoBridge bridge2 = postBridge("bridge2");
            DtoBridgePort bridge2Port = addInteriorPort(bridge2, null);
            DtoError error = addInvalidMacPort(bridge, null, mac0,
                                               bridge2Port.getId());
            assertErrorMatches(error, MAC_PORT_ON_BRIDGE);

            // Try to create MAC-port mappings with mismatched VLANs.
            error = addInvalidMacPort(bridge, null, mac2, ip1.getId());
            assertErrorMatches(error,
                    VLAN_ID_MATCHES_PORT_VLAN_ID,
                    ip1.getVlanId());

            // Should allow this one. We don't restrict mappings to untagged
            // ports based on VLAN.
            addMacPort(bridge, (short) 1, mac2, ip0.getId());

            // Also should not restrict mappings to trunk ports based on VLAN.
            addMacPort(bridge, null, mac3, tp.getId());
            addMacPort(bridge, (short) 1, mac3, tp.getId());

            // TODO: We should enforce VLAN+MAC uniqueness on MAC-port mappings.
            // Currently we do not.
        }

        @Test
        public void testGetMacPort() throws Exception {
            DtoBridge bridge1 = postBridge("bridge1");
            DtoPort bridge1ip0 = addInteriorPort(bridge1, null);
            DtoPort bridge1ip1 = addInteriorPort(bridge1, (short)1);
            addMacPort(bridge1, null, mac0, bridge1ip0.getId());
            addMacPort(bridge1, null, mac1, bridge1ip0.getId());
            addMacPort(bridge1, (short) 1, mac1, bridge1ip1.getId());
            addMacPort(bridge1, (short) 1, mac2, bridge1ip1.getId());

            DtoBridge bridge2 = postBridge("bridge2");
            DtoPort bridge2ip0 = addInteriorPort(bridge2, null);
            DtoPort bridge2ip2 = addInteriorPort(bridge2, (short)2);
            addMacPort(bridge2, null, mac1, bridge2ip0.getId());
            addMacPort(bridge2, null, mac2, bridge2ip0.getId());
            addMacPort(bridge2, (short) 2, mac2, bridge2ip2.getId());
            addMacPort(bridge2, (short) 2, mac3, bridge2ip2.getId());

            // Try to get a mapping from a non-existing bridge.
            UUID fakeBridgeId = new UUID(1234L, 5678L);
            DtoError error = getMacPortWithNotFoundError(
                    fakeBridgeId, null, mac0, bridge1ip0.getId());

            // Try to get a mapping from a VLAN that the bridge doesn't have.
            error = getMacPortWithNotFoundError(
                    bridge1.getId(), (short)2, mac0, bridge1ip0.getId());

            // Try to get a mapping with a malformed MAC address.
            error = getMacPortWithBadRequestError(bridge1.getId(),
                    null, "no-ta-ma-ca-dd-re", bridge1ip0.getId());
            assertErrorMatches(error, MAC_URI_FORMAT);

            // Try to get a mapping from the wrong bridge.
            error = getMacPortWithNotFoundError(
                    bridge1.getId(), null, mac2, bridge2ip0.getId());
            assertErrorMatches(error, BRIDGE_HAS_MAC_PORT);

            // Now get each mapping successfully.
            getMacPort(bridge1.getId(), null, mac0, bridge1ip0.getId());
            getMacPort(bridge1.getId(), null, mac1, bridge1ip0.getId());
            getMacPort(bridge1.getId(), (short)1, mac1, bridge1ip1.getId());
            getMacPort(bridge1.getId(), (short)1, mac2, bridge1ip1.getId());
            getMacPort(bridge2.getId(), null, mac1, bridge2ip0.getId());
            getMacPort(bridge2.getId(), null, mac2, bridge2ip0.getId());
            getMacPort(bridge2.getId(), (short)2, mac2, bridge2ip2.getId());
            getMacPort(bridge2.getId(), (short)2, mac3, bridge2ip2.getId());
        }

        @Test
        public void testDeleteMacPort() throws Exception {
            DtoBridge bridge1 = postBridge("bridge1");
            DtoPort bridge1ip0 = addInteriorPort(bridge1, null);
            DtoPort bridge1ip1 = addInteriorPort(bridge1, (short)1);
            addMacPort(bridge1, null, mac0, bridge1ip0.getId());
            addMacPort(bridge1, null, mac1, bridge1ip0.getId());
            addMacPort(bridge1, (short) 1, mac1, bridge1ip1.getId());
            addMacPort(bridge1, (short) 1, mac2, bridge1ip1.getId());

            DtoMacPort[] macTable = getMacTable(bridge1, null);
            assertEquals(4, macTable.length);

            DtoBridge bridge2 = postBridge("bridge2");
            DtoPort bridge2ip0 = addInteriorPort(bridge2, null);
            DtoPort bridge2ip2 = addInteriorPort(bridge2, (short)2);
            addMacPort(bridge2, null, mac1, bridge2ip0.getId());
            addMacPort(bridge2, null, mac2, bridge2ip0.getId());
            addMacPort(bridge2, (short) 2, mac2, bridge2ip2.getId());
            addMacPort(bridge2, (short) 2, mac3, bridge2ip2.getId());

            // Assert things are created as expected
            macTable = getMacTable(bridge2, null);
            assertEquals(4, macTable.length);

            // Attempt to delete a mapping with a non-existing bridge.
            UUID fakeBridgeId = new UUID(1234L, 5678L);
            DtoError error = deleteMacPortWithNotFoundError(
                    fakeBridgeId, null, mac0, bridge1ip1.getId());

            // Attempt to delete a mapping for a VLAN that doesn't exist
            // on this bridge.
            error = deleteMacPortWithNotFoundError(
                    bridge1.getId(), (short)2, mac1, bridge1ip1.getId());
            assertErrorMatches(error, BRIDGE_HAS_VLAN, 2);

            // Attempt to delete a mapping that only exists for the bridge's
            // other VLAN. This shouldn't error, because delete is idempotent,
            // but it also should not delete anything.
            deleteMacPort(bridge1, (short)1, mac0, bridge1ip0.getId());
            deleteMacPort(bridge1, null, mac1, bridge1ip1.getId());

            // Attempt to delete a mapping which doesn't exist on this bridge.
            // This should not error, because delete is idempotent.
            deleteMacPort(bridge2, null, mac0, bridge1ip0.getId());

            // Verify that the previous deletes didn't delete anything..
            macTable = getMacTable(bridge1, null);
            assertEquals(4, macTable.length);
            macTable = getMacTable(bridge2, null);
            assertEquals(4, macTable.length);

            // Delete some ports and verify that they were deleted.
            deleteMacPort(bridge1, null, mac0, bridge1ip0.getId());
            deleteMacPort(bridge1, null, mac1, bridge1ip0.getId());
            deleteMacPort(bridge1, (short)1, mac1, bridge1ip1.getId());

            macTable = getMacTable(bridge1, null);
            assertEquals(1, macTable.length);
            assertEquals(mac2, macTable[0].getMacAddr());
            assertEquals(1, macTable[0].getVlanId().intValue());
            assertEquals(bridge1ip1.getId(), macTable[0].getPortId());
        }

        @Test
        public void testArpTable() throws Exception {
            DtoBridge bridge = postBridge("bridge1");

            // Add an Arp Entry
            DtoIP4MacPair mp1 = new DtoIP4MacPair(
                "10.0.0.2", "02:11:22:33:44:55");
            mp1 = dtoResource.postAndVerifyCreated(bridge.getArpTable(),
                APPLICATION_IP4_MAC_JSON(), mp1, DtoIP4MacPair.class);

            // Add a second Arp Entry
            DtoIP4MacPair mp2 = new DtoIP4MacPair(
                "10.0.0.3", "02:11:22:33:44:66");
            mp2 = dtoResource.postAndVerifyCreated(bridge.getArpTable(),
                APPLICATION_IP4_MAC_JSON(), mp2, DtoIP4MacPair.class);

            // List the Arp entries.
            DtoIP4MacPair[] entries = dtoResource.getAndVerifyOk(
                bridge.getArpTable(), APPLICATION_IP4_MAC_COLLECTION_JSON(),
                DtoIP4MacPair[].class);
            assertThat("Expect 2 listed entries.",
                entries, arrayWithSize(2));
            assertThat("The listed entries should match those we created.",
                entries, arrayContainingInAnyOrder(mp1, mp2));

            // Delete the second entry.
            dtoResource.deleteAndVerifyNoContent(mp2.getUri(),
                APPLICATION_IP4_MAC_JSON());
            // List the Arp entries.
            entries = dtoResource.getAndVerifyOk(
                bridge.getArpTable(), APPLICATION_IP4_MAC_COLLECTION_JSON(),
                DtoIP4MacPair[].class);
            assertThat("Expect 1 entry.",
                entries, arrayWithSize(1));
            assertThat("The listed entries should match those we created.",
                entries, arrayContainingInAnyOrder(mp1));

            // Try to create an Arp entry with a bad MAC address
            mp2 = new DtoIP4MacPair("1.2.3.4", "02:11:NONSENSE");
            dtoResource.postAndVerifyBadRequest(bridge.getArpTable(),
                APPLICATION_IP4_MAC_JSON(), mp2);
            // Try to create an Arp entry with a null MAC address
            mp2.setMac(null);
            dtoResource.postAndVerifyBadRequest(bridge.getArpTable(),
                APPLICATION_IP4_MAC_JSON(), mp2);
            // Try to create an Arp entry with a null IP4.
            mp2 = new DtoIP4MacPair(null, "02:aa:bb:cc:dd:ee");
            dtoResource.postAndVerifyBadRequest(bridge.getArpTable(),
                APPLICATION_IP4_MAC_JSON(), mp2);
            // Try to create Arp entries with bogus IP4 addresses.
            mp2 = new DtoIP4MacPair("1.2.3", "02:aa:bb:cc:dd:ee");
            dtoResource.postAndVerifyBadRequest(bridge.getArpTable(),
                APPLICATION_IP4_MAC_JSON(), mp2);
            mp2 = new DtoIP4MacPair("1000.2.3.4", "02:aa:bb:cc:dd:ee");
            dtoResource.postAndVerifyBadRequest(bridge.getArpTable(),
                APPLICATION_IP4_MAC_JSON(), mp2);
            mp2 = new DtoIP4MacPair("host", "02:aa:bb:cc:dd:ee");
            dtoResource.postAndVerifyBadRequest(bridge.getArpTable(),
                APPLICATION_IP4_MAC_JSON(), mp2);
        }
    }
}

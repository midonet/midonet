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
package org.midonet.api.network;

import java.net.InetAddress;
import java.net.URI;
import java.util.Set;
import java.util.UUID;

import javax.ws.rs.core.Response.Status;

import com.google.common.collect.Sets;

import org.junit.Before;
import org.junit.Test;

import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.rest_api.FuncTest;
import org.midonet.api.rest_api.RestApiTestBase;
import org.midonet.api.rest_api.TopologyBackdoor;
import org.midonet.client.dto.DtoBridge;
import org.midonet.client.dto.DtoBridgePort;
import org.midonet.client.dto.DtoError;
import org.midonet.client.dto.DtoPort;
import org.midonet.client.dto.DtoTunnelZone;
import org.midonet.client.dto.DtoVtep;
import org.midonet.client.dto.DtoVtepBinding;
import org.midonet.client.dto.DtoVtepPort;
import org.midonet.client.dto.DtoVxLanPort;
import org.midonet.cluster.data.host.Host;
import org.midonet.cluster.rest_api.VendorMediaType;
import org.midonet.cluster.rest_api.validation.MessageProperty;
import org.midonet.midolman.state.VtepConnectionState;

import static org.hamcrest.Matchers.arrayContainingInAnyOrder;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeFalse;
import static org.midonet.api.network.TestPort.createBridgePort;
import static org.midonet.cluster.rest_api.VendorMediaType.APPLICATION_BRIDGE_COLLECTION_JSON;
import static org.midonet.cluster.rest_api.VendorMediaType.APPLICATION_BRIDGE_COLLECTION_JSON_V2;
import static org.midonet.cluster.rest_api.VendorMediaType.APPLICATION_PORT_JSON;
import static org.midonet.cluster.rest_api.VendorMediaType.APPLICATION_PORT_V2_COLLECTION_JSON;
import static org.midonet.cluster.rest_api.VendorMediaType.APPLICATION_PORT_V2_JSON;
import static org.midonet.cluster.rest_api.VendorMediaType.APPLICATION_VTEP_BINDING_COLLECTION_JSON;
import static org.midonet.cluster.rest_api.VendorMediaType.APPLICATION_VTEP_BINDING_JSON;
import static org.midonet.cluster.rest_api.VendorMediaType.APPLICATION_VTEP_COLLECTION_JSON;
import static org.midonet.cluster.rest_api.VendorMediaType.APPLICATION_VTEP_JSON;
import static org.midonet.cluster.rest_api.validation.MessageProperty.IP_ADDR_INVALID;
import static org.midonet.cluster.rest_api.validation.MessageProperty.IP_ADDR_INVALID_WITH_PARAM;
import static org.midonet.cluster.rest_api.validation.MessageProperty.MAX_VALUE;
import static org.midonet.cluster.rest_api.validation.MessageProperty.MIN_VALUE;
import static org.midonet.cluster.rest_api.validation.MessageProperty.NON_NULL;
import static org.midonet.cluster.rest_api.validation.MessageProperty.PORT_NOT_VXLAN_PORT;
import static org.midonet.cluster.rest_api.validation.MessageProperty.RESOURCE_NOT_FOUND;
import static org.midonet.cluster.rest_api.validation.MessageProperty.TUNNEL_ZONE_ID_IS_INVALID;
import static org.midonet.cluster.rest_api.validation.MessageProperty.VTEP_HAS_BINDINGS;
import static org.midonet.cluster.rest_api.validation.MessageProperty.VTEP_NOT_FOUND;
import static org.midonet.cluster.rest_api.validation.MessageProperty.VTEP_PORT_NOT_FOUND;
import static org.midonet.cluster.rest_api.validation.MessageProperty.VTEP_PORT_VLAN_PAIR_ALREADY_USED;
import static org.midonet.southbound.vtep.VtepConstants.bridgeIdToLogicalSwitchName;

public class TestVtep extends RestApiTestBase {

    public TestVtep() {
        super(FuncTest.appDesc);
    }

    private UUID goodTunnelZone = null;
    private UUID badTunnelZone = null;

    public abstract static class MockableVtep {
        abstract public String mgmtIp();
        abstract public int mgmtPort();
        abstract public String name();
        abstract public String desc();
        abstract public Set<String> tunnelIps();
        abstract public String[] portNames();
    }

    /** This VTEP is always mockable by default */
    public static final MockableVtep mockVtep1 = new MockableVtep() {
        public String mgmtIp() { return "250.132.36.225"; }
        public int mgmtPort() { return 12345; }
        public String name() { return "Mock Vtep"; }
        public String desc() { return "The mock vtep description"; }
        public Set<String> tunnelIps() {
            return Sets.newHashSet("32.213.81.62",
                                   "197.132.120.121",
                                   "149.150.232.204");
        }
        public String[] portNames() {
            return new String[]{"eth0", "eth1", "eth_2", "Te 0/2"};
        }
    };

    public static final MockableVtep mockVtep2 = new MockableVtep() {
        public String mgmtIp() { return "30.20.3.99"; }
        public int mgmtPort() { return 3388; }
        public String name() { return "TestVtep-mock-vtep-2"; }
        public String desc() { return "From TestVtep"; }
        public Set<String> tunnelIps() {
            return Sets.newHashSet("197.22.120.100");
        }
        public String[] portNames() {
            return new String[]{"eth0", "eth1", "eth_2", "Te 0/2"};
        }
    };

    @Before
    public void before() {
        URI tunnelZonesUri = app.getTunnelZones();
        DtoTunnelZone tz = new DtoTunnelZone();
        tz.setName("tz");
        tz = dtoResource.postAndVerifyCreated(tunnelZonesUri,
                  VendorMediaType.APPLICATION_TUNNEL_ZONE_JSON, tz,
                  DtoTunnelZone.class);
        assertNotNull(tz.getId());
        goodTunnelZone = tz.getId();
        assertEquals("tz", tz.getName());
        while (badTunnelZone == null || badTunnelZone.equals(
            this.goodTunnelZone)) {
            badTunnelZone = UUID.randomUUID();
        }
    }

    @Test
    public void testCreate() {
        assumeFalse(FuncTest.isCompatApiEnabled());
        DtoVtep vtep = postVtep();
        assertEquals(VtepConnectionState.CONNECTED.toString(),
                     vtep.getConnectionState());
        assertEquals(mockVtep1.desc(), vtep.getDescription());
        assertEquals(mockVtep1.mgmtIp(), vtep.getManagementIp());
        assertEquals(mockVtep1.mgmtPort(), vtep.getManagementPort());
        assertEquals(mockVtep1.name(), vtep.getName());
        assertThat(vtep.getTunnelIpAddrs(),
                   containsInAnyOrder(mockVtep1.tunnelIps().toArray()));
    }

    @Test
    public void testCreateWithNullIPAddr() {
        DtoError error = postVtepWithError(null, mockVtep1.mgmtPort(),
                                           Status.BAD_REQUEST);
        assertErrorMatchesPropMsg(error, "managementIp", NON_NULL);
    }

    @Test
    public void testCreateWithBadTunnelZone() {
        DtoVtep vtep = new DtoVtep();
        vtep.setManagementIp(mockVtep1.mgmtIp());
        vtep.setManagementPort(mockVtep1.mgmtPort());
        vtep.setTunnelZoneId(badTunnelZone);
        DtoError error = dtoResource.postAndVerifyError(app.getVteps(),
                                                        APPLICATION_VTEP_JSON,
                                                        vtep,
                                                        Status.BAD_REQUEST);
        assertErrorMatches(error, TUNNEL_ZONE_ID_IS_INVALID);
    }

    @Test
    public void testCreateWithNullTunnelZone() {
        assumeFalse(FuncTest.isCompatApiEnabled());
        DtoVtep vtep = new DtoVtep();
        vtep.setManagementIp(mockVtep1.mgmtIp());
        vtep.setManagementPort(mockVtep1.mgmtPort());
        vtep.setTunnelZoneId(null);
        DtoError error = dtoResource.postAndVerifyError(app.getVteps(),
                                                        APPLICATION_VTEP_JSON,
                                                        vtep,
                                                        Status.BAD_REQUEST);
        assertErrorMatches(error, TUNNEL_ZONE_ID_IS_INVALID);
    }

    @Test
    public void testCreateWithIllFormedIPAddr() {
        DtoError error = postVtepWithError("10.0.0.300", mockVtep1.mgmtPort(),
                                           Status.BAD_REQUEST);
        assertErrorMatchesPropMsg(error, "managementIp", IP_ADDR_INVALID);
    }

    @Test
    public void testCreateWithDuplicateIPAddr() {
        String ipAddr = mockVtep1.mgmtIp();
        postVtep();
        DtoError error = postVtepWithError(ipAddr, mockVtep1.mgmtPort() + 1,
                                           Status.CONFLICT);
        assertErrorMatches(error, MessageProperty.VTEP_EXISTS, ipAddr);
    }

    @Test
    public void testCreateWithInaccessibleIpAddr() {
        assumeFalse(FuncTest.isCompatApiEnabled());
        DtoVtep vtep = postVtep("10.0.0.1", 10001);
        assertEquals(VtepConnectionState.ERROR.toString(),
                     vtep.getConnectionState());
        assertNull(vtep.getDescription());
        assertEquals("10.0.0.1", vtep.getManagementIp());
        assertEquals(10001, vtep.getManagementPort());
        assertNull(vtep.getName());
        assertNull(vtep.getTunnelIpAddrs());
    }

    @Test
    public void testCreateWithHostConflict() throws Exception {
        assumeFalse(FuncTest.isCompatApiEnabled());
        String ip = "10.255.255.1";

        // Add the host to ZooKeeper.
        Host host = new Host();
        host.setId(UUID.randomUUID());
        host.setAddresses(new InetAddress[]{
            InetAddress.getByName(ip)});
        FuncTest._injector
            .getInstance(TopologyBackdoor.class)
            .createHost(host.getId(), host.getName(), host.getAddresses());

        // Try add the VTEP with the same IP address.
        postVtepWithError(ip, mockVtep1.mgmtPort(), Status.CONFLICT);
    }

    @Test
    public void testGet() {
        postVtep();
        DtoVtep vtep = getVtep(mockVtep1.mgmtIp());
        assertEquals(mockVtep1.mgmtIp(), vtep.getManagementIp());
        assertEquals(mockVtep1.mgmtPort(), vtep.getManagementPort());
    }

    @Test
    public void testGetWithInvalidIP() {
        DtoError error = getVtepWithError("10.0.0.300", Status.BAD_REQUEST);
        assertErrorMatches(error, MessageProperty.IP_ADDR_INVALID_WITH_PARAM,
                           "10.0.0.300");
    }

    @Test
    public void testGetWithUnrecognizedIP() {
        postVtep();
        DtoError error = getVtepWithError("10.0.0.1", Status.NOT_FOUND);
        assertErrorMatches(error, VTEP_NOT_FOUND, "10.0.0.1");
    }

    @Test
    public void testListVtepsWithNoVteps() {
        DtoVtep[] vteps = listVteps();
        assertEquals(0, vteps.length);
    }

    @Test
    public void testListVtepsWithTwoVteps() {
        assumeFalse(FuncTest.isCompatApiEnabled());
        // The mock client supports only one management IP/port, so
        // only one will successfully connect.
        DtoVtep[] expectedVteps = new DtoVtep[2];
        expectedVteps[0] = postVtep("10.0.0.1", 10001);
        assertEquals(VtepConnectionState.ERROR.toString(),
                     expectedVteps[0].getConnectionState());
        expectedVteps[1] = postVtep();
        assertEquals(VtepConnectionState.CONNECTED.toString(),
                     expectedVteps[1].getConnectionState());

        DtoVtep[] actualVteps = listVteps();
        assertEquals(2, actualVteps.length);
        assertThat(actualVteps, arrayContainingInAnyOrder(expectedVteps));
    }

    @Test
    public void testDeleteVtep() {
        assumeFalse(FuncTest.isCompatApiEnabled());
        DtoVtep vtep = postVtep();
        DtoBridge bridge = postBridge("bridge1");
        DtoVtepBinding binding =
                addAndVerifyBinding(vtep, bridge, mockVtep1.portNames()[0], 1);

        // Cannot delete the VTEP because it has bindings.
        DtoError error = dtoResource.deleteAndVerifyBadRequest(
                vtep.getUri(), APPLICATION_VTEP_JSON);
        assertErrorMatches(error, VTEP_HAS_BINDINGS, mockVtep1.mgmtIp());

        // Delete the binding.
        deleteBinding(binding.getUri());

        // Can delete the VTEP now.
        dtoResource.deleteAndVerifyNoContent(vtep.getUri(),
                                             APPLICATION_VTEP_JSON);
    }

    @Test
    public void testDeleteNonexistingVtep() {
        DtoError error = dtoResource.deleteAndVerifyNotFound(
            ResourceUriBuilder.getVtep(app.getUri(), "1.2.3.4"),
            APPLICATION_VTEP_JSON);
        assertErrorMatches(error, VTEP_NOT_FOUND, "1.2.3.4");
    }

    @Test
    public void testDeleteVtepWithInvalidIPAddress() {
        assumeFalse(FuncTest.isCompatApiEnabled());
        DtoError error = dtoResource.deleteAndVerifyBadRequest(
                ResourceUriBuilder.getVtep(app.getUri(), "300.1.2.3"),
                APPLICATION_VTEP_JSON);
        assertErrorMatches(error, IP_ADDR_INVALID_WITH_PARAM, "300.1.2.3");
    }

    @Test
    public void testAddBindingsSingleNetworkMultipleVteps() {
        assumeFalse(FuncTest.isCompatApiEnabled());
        DtoVtep vtep1 = postVtep();
        DtoVtep vtep2 = postVtep(mockVtep2.mgmtIp(), mockVtep2.mgmtPort());

        assertEquals(0, listBindings(vtep1).length);

        DtoBridge br = postBridge("network1");
        DtoVtepBinding b1 = addAndVerifyBinding(vtep1, br,
                                                mockVtep1.portNames()[0], 1);
        DtoVtepBinding b2 = addAndVerifyBinding(vtep1, br,
                                                mockVtep1.portNames()[1], 1);
        addAndVerifyBinding(vtep2, br, mockVtep2.portNames()[0], 2);
        addAndVerifyBinding(vtep2, br, mockVtep2.portNames()[1], 2);

        br = getBridge(br.getId());
        assertEquals("A vxlan port per vtep", 2, br.getVxLanPortIds().size());
        assertEquals(2, listBindings(vtep1).length);
        assertEquals(2, listBindings(vtep2).length);

        DtoVxLanPort vxPort1 = getVxLanPort(br.getVxLanPortIds().get(0));
        DtoVxLanPort vxPort2 = getVxLanPort(br.getVxLanPortIds().get(1));

        assertEquals(mockVtep1.mgmtIp(), vxPort1.getMgmtIpAddr());
        assertEquals(mockVtep2.mgmtIp(), vxPort2.getMgmtIpAddr());

        // Deleting a binding on a vtep has no other secondary effects
        deleteBinding(b1.getUri());

        br = getBridge(br.getId());
        assertEquals("A vxlan port per vtep", 2, br.getVxLanPortIds().size());
        assertEquals(1, listBindings(vtep1).length);
        assertEquals(2, listBindings(vtep2).length);

        vxPort1 = getVxLanPort(br.getVxLanPortIds().get(0));
        vxPort2 = getVxLanPort(br.getVxLanPortIds().get(1));

        assertEquals(mockVtep1.mgmtIp(), vxPort1.getMgmtIpAddr());
        assertEquals(mockVtep2.mgmtIp(), vxPort2.getMgmtIpAddr());

        // Deleting the last binding on a vtep kills the associated vxlan port
        deleteBinding(b2.getUri());

        br = getBridge(br.getId());
        assertEquals(1, br.getVxLanPortIds().size());
        assertEquals(0, listBindings(vtep1).length);
        assertEquals(2, listBindings(vtep2).length);

        // Only vxlan port 2 remains
        vxPort2 = getVxLanPort(br.getVxLanPortIds().get(0));
        assertEquals(mockVtep2.mgmtIp(), vxPort2.getMgmtIpAddr());
    }

    // Tests both add and remove, so no need to bother having a separate one for
    // just adding
    @Test
    public void testAddRemoveBindings() {
        assumeFalse(FuncTest.isCompatApiEnabled());
        DtoVtep vtep = postVtep();

        assertEquals(0, listBindings(vtep).length);

        DtoBridge br1 = postBridge("network1");
        DtoBridge br2 = postBridge("network2");
        DtoVtepBinding binding1 = addAndVerifyBinding(vtep, br1,
                                                      mockVtep1.portNames()[0],
                                                      1);
        DtoVtepBinding binding2 = addAndVerifyBinding(vtep, br2,
                                                      mockVtep1.portNames()[1],
                                                      2);

        assertEquals(br1.getId(), binding1.getNetworkId());
        assertEquals(br2.getId(), binding2.getNetworkId());

        br1 = getBridge(br1.getId());
        br2 = getBridge(br2.getId());

        DtoVxLanPort vxlanPort1 = getVxLanPort(br1.getVxLanPortId());
        DtoVxLanPort vxlanPort2 = getVxLanPort(br2.getVxLanPortId());

        DtoVtepBinding[] bindings = listBindings(vtep);
        assertEquals(2, bindings.length);
        assertThat(bindings, arrayContainingInAnyOrder(binding1, binding2));

        deleteBinding(binding1.getUri());

        bindings = listBindings(vtep);
        assertEquals(1, bindings.length);
        assertThat(bindings, arrayContainingInAnyOrder(binding2));
        dtoResource.getAndVerifyNotFound(vxlanPort1.getUri(),
                                         APPLICATION_PORT_JSON);
        dtoResource.getAndVerifyOk(vxlanPort2.getUri(),
                                   APPLICATION_PORT_JSON, DtoVxLanPort.class);
        br1 = getBridge(br1.getId());
        assertNull(br1.getVxLanPortId());

        br2 = getBridge(br2.getId());
        assertEquals(vxlanPort2.getId(), br2.getVxLanPortId());

        deleteBinding(binding2.getUri());
        dtoResource.getAndVerifyNotFound(vxlanPort2.getUri(),
                                         APPLICATION_PORT_JSON);

        br2 = getBridge(br2.getId());
        assertNull(br2.getVxLanPortId());

        bindings = listBindings(vtep);
        assertEquals(0, bindings.length);
    }

    @Test
    public void testAddRemoveMultipleBindingsOnSingleNetwork() {
        assumeFalse(FuncTest.isCompatApiEnabled());
        testAddRemoveBinding(mockVtep1.portNames()[0]);
    }

    @Test
    public void testAddRemoveBindingWithWeirdName() {
        assumeFalse(FuncTest.isCompatApiEnabled());
        testAddRemoveBinding(mockVtep1.portNames()[3]);
    }

    @Test
    public void testDeleteNonExistentBinding() {
        assumeFalse(FuncTest.isCompatApiEnabled());
        DtoVtep vtep = postVtep();
        DtoBridge br = postBridge("n");
        DtoVtepBinding binding =
                addAndVerifyBinding(vtep, br, mockVtep1.portNames()[0], 1);
        deleteBinding(binding.getUri());
        dtoResource.deleteAndVerifyNotFound(binding.getUri(),
                                            APPLICATION_VTEP_BINDING_JSON);
    }

    @Test
    public void testAddBindingWithIllFormedIP() {
        assumeFalse(FuncTest.isCompatApiEnabled());
        DtoVtep vtep = postVtep();
        URI bindingsUri = replaceInUri(
                vtep.getBindings(), mockVtep1.mgmtIp(), "300.0.0.1");
        DtoVtepBinding binding = makeBinding("eth0", 1, UUID.randomUUID());
        DtoError error = dtoResource.postAndVerifyError(bindingsUri,
                APPLICATION_VTEP_BINDING_JSON, binding, Status.BAD_REQUEST);
        assertErrorMatches(error, IP_ADDR_INVALID_WITH_PARAM, "300.0.0.1");
    }

    @Test
    public void testAddBindingWithUnrecognizedIP() {
        assumeFalse(FuncTest.isCompatApiEnabled());
        DtoBridge bridge = postBridge("network1");
        DtoVtep vtep = postVtep();
        URI bindingsUri = replaceInUri(
                vtep.getBindings(), mockVtep1.mgmtIp(), "10.10.10.10");
        DtoVtepBinding binding = makeBinding("eth0", 1, bridge.getId());
        DtoError error = dtoResource.postAndVerifyError(
            bindingsUri, APPLICATION_VTEP_BINDING_JSON,
            binding, Status.BAD_REQUEST);
        assertErrorMatches(error, VTEP_NOT_FOUND, "10.10.10.10");
    }

    @Test
    public void testAddBindingWithNegativeVlanId() {
        DtoVtep vtep = postVtep();
        DtoError error = postBindingWithError(vtep,
                makeBinding("eth0", -1, UUID.randomUUID()), Status.BAD_REQUEST);
        assertErrorMatchesPropMsg(error, "vlanId", MIN_VALUE, 0);
    }

    @Test
    public void testAddBindingWith4096VlanId() {
        DtoVtep vtep = postVtep();
        DtoError error = postBindingWithError(vtep,
                makeBinding("eth0", 4096, UUID.randomUUID()),
                Status.BAD_REQUEST);
        assertErrorMatchesPropMsg(error, "vlanId", MAX_VALUE, 4095);
    }

    @Test
    public void testAddBindingWithNullNetworkId() {
        DtoVtep vtep = postVtep();
        DtoError error = postBindingWithError(vtep,
                makeBinding("eth0", 1, null), Status.BAD_REQUEST);
        assertErrorMatchesPropMsg(error, "networkId", NON_NULL);
    }

    @Test
    public void testAddBindingWithUnrecognizedNetworkId() {
        DtoVtep vtep = postVtep();
        UUID networkId = UUID.randomUUID();
        DtoError error = postBindingWithError(vtep,
                makeBinding("eth0", 1, networkId), Status.BAD_REQUEST);
        assertErrorMatches(error, RESOURCE_NOT_FOUND, "bridge", networkId);
    }

    @Test
    public void testAddBindingWithNullPortName() {
        DtoVtep vtep = postVtep();
        DtoError error = postBindingWithError(vtep,
                makeBinding(null, 1, UUID.randomUUID()), Status.BAD_REQUEST);
        assertErrorMatchesPropMsg(error, "portName", NON_NULL);
    }

    @Test
    public void testAddBindingWithUnrecognizedPortName() {
        assumeFalse(FuncTest.isCompatApiEnabled());
        DtoBridge bridge = postBridge("network1");
        DtoVtep vtep = postVtep();
        DtoVtepBinding binding = makeBinding("blah", 1, bridge.getId());
        DtoError err = postBindingWithError(vtep, binding, Status.NOT_FOUND);
        assertErrorMatches(err, VTEP_PORT_NOT_FOUND, mockVtep1.mgmtIp(),
                           mockVtep1.mgmtPort(), "blah");
    }

    // The same port/vlan pair on the same vtep should not be used by two
    // midonet networks.
    @Test
    public void testAddConflictingBinding() {
        assumeFalse(FuncTest.isCompatApiEnabled());
        DtoBridge bridge = postBridge("network1");
        DtoBridge bridge2 = postBridge("network2");
        DtoVtep vtep = postVtep();
        postBinding(vtep, makeBinding(mockVtep1.portNames()[0],
                                      3, bridge.getId()));
        DtoError err = postBindingWithError(vtep,
                                            makeBinding(mockVtep1.portNames()[0],
                                                        3, bridge2.getId()),
                                            Status.CONFLICT);
        assertErrorMatches(err, VTEP_PORT_VLAN_PAIR_ALREADY_USED,
                           mockVtep1.mgmtIp(), mockVtep1.mgmtPort(),
                           mockVtep1.portNames()[0], 3, bridge.getId());

        // Ensure that the second bridge didn't keep a vxlan port
        bridge2 = getBridge(bridge2.getId());
        assertNull(bridge2.getVxLanPortId());
    }

    @Test
    public void testListBindings() {
        assumeFalse(FuncTest.isCompatApiEnabled());
        // First try getting the bindings when the VTEP is first
        // created. The mock VTEP client is preseeded with a non-
        // Midonet binding, so there should actually be one, but the
        // Midonet API should ignore it and return an empty list.
        DtoVtep vtep = postVtep();
        DtoVtepBinding[] actualBindings = listBindings(vtep);
        assertEquals(0, actualBindings.length);

        DtoBridge bridge = postBridge("network1");
        DtoBridge bridge2 = postBridge("network2");
        DtoVtepBinding[] expectedBindings = new DtoVtepBinding[2];
        expectedBindings[0] = postBinding(vtep, makeBinding(
                mockVtep1.portNames()[0], 1, bridge.getId()));
        expectedBindings[1] = postBinding(vtep, makeBinding(
                mockVtep1.portNames()[1], 5, bridge2.getId()));

        actualBindings = listBindings(vtep);
        assertThat(actualBindings, arrayContainingInAnyOrder(expectedBindings));
    }

    @Test
    public void testListBindingsWithUnrecognizedVtep() throws Exception {
        DtoError error = dtoResource.getAndVerifyNotFound(
                ResourceUriBuilder.getVtepBindings(app.getUri(), "10.10.10.10"),
                APPLICATION_VTEP_BINDING_COLLECTION_JSON);
        assertErrorMatches(error, VTEP_NOT_FOUND, "10.10.10.10");
    }

    @Test
    public void testGetBindingWithUnrecognizedVtep() {
        URI bindingUri = ResourceUriBuilder.getVtepBinding(
                app.getUri(), "1.2.3.4", "a_port", (short)1);
        DtoError error = dtoResource.getAndVerifyNotFound(
                bindingUri, APPLICATION_VTEP_BINDING_JSON);
        assertErrorMatches(error, VTEP_NOT_FOUND, "1.2.3.4");
    }

    @Test
    public void testListAndGetBindingsOnVxLanPort() {
        assumeFalse(FuncTest.isCompatApiEnabled());
        DtoVtep vtep = postVtep();
        DtoBridge bridge1 = postBridge("bridge1");
        DtoBridge bridge2 = postBridge("bridge2");

        // Post bindings, two on each bridge.
        DtoVtepBinding[] b1ExpectedBindings = new DtoVtepBinding[2];
        b1ExpectedBindings[0] = postBinding(vtep, makeBinding(
                mockVtep1.portNames()[0], 1, bridge1.getId()));
        b1ExpectedBindings[1] = postBinding(vtep, makeBinding(
                mockVtep1.portNames()[1], 2, bridge1.getId()));

        DtoVtepBinding[] b2ExpectedBindings = new DtoVtepBinding[2];
        b2ExpectedBindings[0] = postBinding(vtep, makeBinding(
                mockVtep1.portNames()[0], 3, bridge2.getId()));
        b2ExpectedBindings[1] = postBinding(vtep, makeBinding(
                mockVtep1.portNames()[1], 4, bridge2.getId()));

        // Get VXLAN ports.
        bridge1 = getBridge(bridge1.getId());
        DtoVxLanPort vxLanPort1 = getVxLanPort(bridge1.getVxLanPortId());
        bridge2 = getBridge(bridge2.getId());
        DtoVxLanPort vxLanPort2 = getVxLanPort(bridge2.getVxLanPortId());

        // Check bindings on each port.
        DtoVtepBinding[] b1ActualBindings = listBindings(vxLanPort1);
        assertThat(b1ActualBindings,
                   arrayContainingInAnyOrder(b1ExpectedBindings));
        DtoVtepBinding[] b2ActualBindings = listBindings(vxLanPort2);
        assertThat(b2ActualBindings,
                   arrayContainingInAnyOrder(b2ExpectedBindings));

        // Make sure we can get individual bindings via VxLanPort, as well.
        URI bindingUri = ResourceUriBuilder.getVxLanPortBinding(
                app.getUri(), vxLanPort1.getId(),
                mockVtep1.portNames()[0], (short)1);
        DtoVtepBinding b1ActualBinding1 = dtoResource.getAndVerifyOk(
                bindingUri, APPLICATION_VTEP_BINDING_JSON, DtoVtepBinding.class);
        assertEquals(b1ExpectedBindings[0], b1ActualBinding1);
    }

    @Test
    public void testListAndGetBindingsOnNonVxLanPort() throws Exception {
        assumeFalse(FuncTest.isCompatApiEnabled());
        DtoBridge bridge = postBridge("bridge1");
        DtoBridgePort port = postBridgePort(
                createBridgePort(null, bridge.getId(), null, null, null),
                bridge);

        URI bindingsUri = ResourceUriBuilder.getVxLanPortBindings(
            app.getUri(), port.getId());
        DtoError error = dtoResource.getAndVerifyBadRequest(
            bindingsUri, APPLICATION_VTEP_BINDING_COLLECTION_JSON);
        assertErrorMatches(error, PORT_NOT_VXLAN_PORT, port.getId());

        URI singleBindingUri = ResourceUriBuilder.getVxLanPortBinding(
            app.getUri(), port.getId(), "eth0", (short) 1);
        error = dtoResource.getAndVerifyBadRequest(
                singleBindingUri, APPLICATION_VTEP_BINDING_JSON);
        assertErrorMatches(error, PORT_NOT_VXLAN_PORT, port.getId());
    }

    @Test
    public void testListBindingsOnNonexistingPort() throws Exception {
        assumeFalse(FuncTest.isCompatApiEnabled());
        UUID portId = UUID.randomUUID();
        URI bindingsUri =
                ResourceUriBuilder.getVxLanPortBindings(app.getUri(), portId);
        DtoError error = dtoResource.getAndVerifyNotFound(
                bindingsUri, APPLICATION_VTEP_BINDING_COLLECTION_JSON);
        assertErrorMatches(error, RESOURCE_NOT_FOUND, "port", portId);
    }

    @Test
    public void testGetBindingOnNonexistingPort() throws Exception {
        assumeFalse(FuncTest.isCompatApiEnabled());
        UUID portId = UUID.randomUUID();
        URI bindingUri = ResourceUriBuilder.getVxLanPortBinding
                (app.getUri(), portId, "eth0", (short) 1);
        DtoError error = dtoResource.getAndVerifyNotFound(
                bindingUri, APPLICATION_VTEP_BINDING_JSON);
        assertErrorMatches(error, RESOURCE_NOT_FOUND, "port", portId);
    }

    @Test
    public void testListVtepPorts() {
        assumeFalse(FuncTest.isCompatApiEnabled());
        DtoVtep vtep = postVtep();
        DtoVtepPort[] ports = dtoResource.getAndVerifyOk(vtep.getPorts(),
                VendorMediaType.APPLICATION_VTEP_PORT_COLLECTION_JSON,
                DtoVtepPort[].class);

        DtoVtepPort[] expectedPorts =
                new DtoVtepPort[mockVtep1.portNames().length];
        for (int i = 0; i < mockVtep1.portNames().length; i++)
            expectedPorts[i] = new DtoVtepPort(mockVtep1.portNames()[i],
                                               mockVtep1.portNames()[i]
                                               + "-desc");

        assertThat(ports, arrayContainingInAnyOrder(expectedPorts));
    }

    @Test
    public void testDeleteVxLanPortDeletesBindings() {
        assumeFalse(FuncTest.isCompatApiEnabled());
        DtoBridge bridge1 = postBridge("bridge1");
        DtoBridge bridge2 = postBridge("bridge2");
        DtoVtep vtep = postVtep();

        DtoVtepBinding br1bi1 = postBinding(vtep, makeBinding(
                mockVtep1.portNames()[0], 1, bridge1.getId()));
        DtoVtepBinding br1bi2 = postBinding(vtep, makeBinding(
                mockVtep1.portNames()[1], 2, bridge1.getId()));
        DtoVtepBinding br2bi1 = postBinding(vtep, makeBinding(
                mockVtep1.portNames()[0], 3, bridge2.getId()));
        DtoVtepBinding br2bi2 = postBinding(vtep, makeBinding(
                mockVtep1.portNames()[1], 4, bridge2.getId()));

        DtoVtepBinding[] bindings = listBindings(vtep);
        assertThat(bindings, arrayContainingInAnyOrder(br1bi1, br1bi2,
                                                       br2bi1, br2bi2));

        bridge1 = getBridge(bridge1.getId());
        dtoResource.deleteAndVerifyNoContent(bridge1.getVxLanPort(),
                                             APPLICATION_PORT_V2_JSON);
        bindings = listBindings(vtep);
        assertThat(bindings, arrayContainingInAnyOrder(br2bi1, br2bi2));
    }

    @Test
    public void testListBridgePortsExcludesVxlanPort() {
        assumeFalse(FuncTest.isCompatApiEnabled());
        DtoBridge bridge = postBridge("bridge1");
        DtoBridgePort bridgePort = postBridgePort(
                createBridgePort(null, bridge.getId(), null, null, null),
                bridge);

        DtoVtep vtep = postVtep();
        postBinding(vtep, makeBinding(mockVtep1.portNames()[0],
                                      0, bridge.getId()));

        DtoBridgePort[] bridgePorts = dtoResource.getAndVerifyOk(
                bridge.getPorts(), APPLICATION_PORT_V2_COLLECTION_JSON,
                DtoBridgePort[].class);
        assertThat(bridgePorts, arrayContainingInAnyOrder(bridgePort));
    }

    @Test
    public void testBridgeV1LacksVxLanPortId() {
        assumeFalse(FuncTest.isCompatApiEnabled());
        DtoBridge b1 = postBridge("bridge1");
        DtoVtep vtep = postVtep();

        postBinding(vtep, makeBinding(mockVtep1.portNames()[0], 0, b1.getId()));

        // V3 get should have vxLanPortIds
        DtoBridge bridge = getBridge(b1.getId());
        assertNotNull(bridge.getVxLanPortIds());
        assertNotNull(bridge.getVxLanPorts());

        // V2 collection, too.
        DtoBridge[] bridges = dtoResource.getAndVerifyOk(app.getBridges(),
                APPLICATION_BRIDGE_COLLECTION_JSON_V2, DtoBridge[].class);
        assertNotNull(bridges[0].getVxLanPortId());
        assertNotNull(bridges[0].getVxLanPort());
        assertNull(bridges[0].getVxLanPortIds());
        assertNull(bridges[0].getVxLanPorts());

        // V1 should not.
        bridge = getBridgeV1(b1.getId());
        assertNull(bridge.getVxLanPortId());
        assertNull(bridge.getVxLanPort());

        bridges = dtoResource.getAndVerifyOk(app.getBridges(),
                APPLICATION_BRIDGE_COLLECTION_JSON, DtoBridge[].class);
        assertNull(bridges[0].getVxLanPortIds());
        assertNull(bridges[0].getVxLanPorts());
        assertNull(bridges[0].getVxLanPortIds());
        assertNull(bridges[0].getVxLanPorts());
    }

    @Test
    public void testDeleteAndRecreateBindings() {
        assumeFalse(FuncTest.isCompatApiEnabled());
        DtoBridge bridge = postBridge("bridge1");
        DtoVtep vtep = postVtep();
        DtoVtepBinding binding = postBinding(vtep,
                makeBinding(mockVtep1.portNames()[0], 0, bridge.getId()));
        deleteBinding(binding.getUri());
        postBinding(vtep,
                    makeBinding(mockVtep1.portNames()[0], 0, bridge.getId()));
    }

    private DtoVtep makeVtep(String mgmtIpAddr, int mgmtPort,
                             UUID tunnelZoneId) {
        DtoVtep vtep = new DtoVtep();
        vtep.setManagementIp(mgmtIpAddr);
        vtep.setManagementPort(mgmtPort);
        vtep.setTunnelZoneId(tunnelZoneId);
        return vtep;
    }

    private DtoVtep postVtep() {
        return postVtep(mockVtep1.mgmtIp(), mockVtep1.mgmtPort());
    }

    private DtoVtep postVtep(String mgmtIpAddr, int mgmtPort) {
        return postVtep(makeVtep(mgmtIpAddr, mgmtPort, goodTunnelZone));
    }

    private DtoVtep postVtep(DtoVtep vtep) {
        return dtoResource.postAndVerifyCreated(
                app.getVteps(), APPLICATION_VTEP_JSON, vtep, DtoVtep.class);
    }

    private DtoError postVtepWithError(
            String mgmtIpAddr, int mgmtPort, Status status) {
        DtoVtep vtep = makeVtep(mgmtIpAddr, mgmtPort, goodTunnelZone);
        return dtoResource.postAndVerifyError(
                app.getVteps(), APPLICATION_VTEP_JSON, vtep, status);
    }

    private DtoVtep getVtep(String mgmtIpAddr) {
        return dtoResource.getAndVerifyOk(
                app.getVtep(mgmtIpAddr), APPLICATION_VTEP_JSON, DtoVtep.class);
    }

    private DtoError getVtepWithError(String mgmtIpAddr, Status status) {
        return dtoResource.getAndVerifyError(
                app.getVtep(mgmtIpAddr), APPLICATION_VTEP_JSON, status);
    }

    private DtoVtep[] listVteps() {
        return dtoResource.getAndVerifyOk(app.getVteps(),
                                          APPLICATION_VTEP_COLLECTION_JSON,
                                          DtoVtep[].class);
    }

    private DtoVtepBinding makeBinding(
            String portName, int vlanId, UUID networkId) {
        DtoVtepBinding binding = new DtoVtepBinding();
        binding.setPortName(portName);
        binding.setVlanId((short)vlanId);
        binding.setNetworkId(networkId);
        return binding;
    }

    private DtoVtepBinding postBinding(DtoVtep vtep, DtoVtepBinding binding) {
        return dtoResource.postAndVerifyCreated(
                vtep.getBindings(), APPLICATION_VTEP_BINDING_JSON,
                binding, DtoVtepBinding.class);
    }

    private DtoError postBindingWithError(
            DtoVtep vtep, DtoVtepBinding binding, Status status) {
        return dtoResource.postAndVerifyError(vtep.getBindings(),
                                              APPLICATION_VTEP_BINDING_JSON,
                                              binding, status);
    }

    private DtoVtepBinding[] listBindings(DtoVtep vtep) {
        return dtoResource.getAndVerifyOk(vtep.getBindings(),
                APPLICATION_VTEP_BINDING_COLLECTION_JSON,
                DtoVtepBinding[].class);
    }

    private DtoVtepBinding[] listBindings(DtoVxLanPort vxLanPort) {
        return dtoResource.getAndVerifyOk(vxLanPort.getBindings(),
                APPLICATION_VTEP_BINDING_COLLECTION_JSON,
                DtoVtepBinding[].class);
    }

    private void deleteBinding(URI uri) {
        dtoResource.deleteAndVerifyNoContent(uri,
                                             APPLICATION_VTEP_BINDING_JSON);
    }

    private DtoVxLanPort getVxLanPort(UUID id) {
        DtoPort port = getPort(id);
        assertNotNull(port);
        return (DtoVxLanPort)port;
    }

    private void testAddRemoveBinding(String portName) {

        DtoVtep vtep = postVtep();

        assertEquals(0, listBindings(vtep).length);

        DtoBridge br = postBridge("network1");
        DtoVtepBinding binding1 = addAndVerifyFirstBinding(vtep, br,
                                                           portName, 1);
        DtoVtepBinding binding2 = addAndVerifyBinding(vtep, br, portName, 2);

        br = getBridge(binding1.getNetworkId());
        assertEquals(br.getId(), binding1.getNetworkId());

        DtoVxLanPort vxlanPort = getVxLanPort(br.getVxLanPortId());

        DtoVtepBinding[] bindings = listBindings(vtep);
        assertThat(bindings, arrayContainingInAnyOrder(binding1, binding2));

        deleteBinding(binding1.getUri());

        bindings = listBindings(vtep);
        assertThat(bindings, arrayContainingInAnyOrder(binding2));
        dtoResource.getAndVerifyOk(vxlanPort.getUri(),
                                   APPLICATION_PORT_JSON, DtoVxLanPort.class);

        vxlanPort = getVxLanPort(vxlanPort.getId()); // should exist

        deleteBinding(binding2.getUri());
        dtoResource.getAndVerifyNotFound(vxlanPort.getUri(),
                                         APPLICATION_PORT_JSON);

        bindings = listBindings(vtep);
        assertEquals(0, bindings.length);
    }

    /**
     * This is the first binding of this network to the VTEP. It's a special
     * case where we do just set the binding in Midonet, but not in the VTEP
     * and wait for the VxGW Service to consolidate. This method simulates
     * that the VxGW applies the config relevant for the new binding.
     */
    private DtoVtepBinding addAndVerifyFirstBinding(DtoVtep vtep,
                                                    DtoBridge network,
                                                    String portName, int vlan) {
        DtoVtepBinding b = addAndVerifyBinding(vtep, network, portName, vlan);
        String lsName = bridgeIdToLogicalSwitchName(network.getId());
        // This creates the logical switch by itself
        // bindVlan(lsName, portName, vlan, vni, new ArrayList<String>());
        return b;
    }

    // See addAndVerifyFirstBinding. This method will write both to the VTEP
    // and ZK, and assume that the VTEP already contains the logical switch.
    private DtoVtepBinding addAndVerifyBinding(DtoVtep vtep,
                                               DtoBridge network,
                                               String portName, int vlan) {
        DtoVtepBinding binding = postBinding(vtep,
                                             makeBinding(portName, vlan,
                                                         network.getId()));
        assertEquals(network.getId(), binding.getNetworkId());
        assertEquals(portName, binding.getPortName());
        assertEquals(vlan, binding.getVlanId());

        // Should create a VXLAN port on the specified bridge.
        DtoBridge bridge = getBridge(network.getId());
        DtoVxLanPort port = getVxLanPort(bridge.getVxLanPortId());
        assertEquals(mockVtep1.mgmtIp(), port.getMgmtIpAddr());
        assertEquals(mockVtep1.mgmtPort(), port.getMgmtPort());

        return binding;
    }

}

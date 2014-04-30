/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.network;

import java.net.URI;
import java.util.UUID;
import javax.ws.rs.core.Response.Status;

import org.junit.Before;
import org.junit.Test;
import org.midonet.api.VendorMediaType;
import org.midonet.api.rest_api.FuncTest;
import org.midonet.api.rest_api.RestApiTestBase;
import org.midonet.api.validation.MessageProperty;
import org.midonet.client.dto.DtoBridge;
import org.midonet.client.dto.DtoError;
import org.midonet.client.dto.DtoGreTunnelZone;
import org.midonet.client.dto.DtoPort;
import org.midonet.client.dto.DtoVtep;
import org.midonet.client.dto.DtoVtepBinding;
import org.midonet.client.dto.DtoVxLanPort;
import org.midonet.midolman.state.VtepConnectionState;
import static org.hamcrest.Matchers.arrayContainingInAnyOrder;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.midonet.api.VendorMediaType.APPLICATION_VTEP_BINDING_JSON;
import static org.midonet.api.VendorMediaType.APPLICATION_VTEP_COLLECTION_JSON;
import static org.midonet.api.VendorMediaType.APPLICATION_VTEP_JSON;
import static org.midonet.api.validation.MessageProperty.VTEP_NOT_FOUND;
import static org.midonet.api.validation.MessageProperty.getMessage;
import static org.midonet.api.vtep.VtepDataClientProvider.MOCK_VTEP_DESC;
import static org.midonet.api.vtep.VtepDataClientProvider.MOCK_VTEP_MGMT_IP;
import static org.midonet.api.vtep.VtepDataClientProvider.MOCK_VTEP_MGMT_PORT;
import static org.midonet.api.vtep.VtepDataClientProvider.MOCK_VTEP_NAME;
import static org.midonet.api.vtep.VtepDataClientProvider.MOCK_VTEP_PORT_NAMES;
import static org.midonet.api.vtep.VtepDataClientProvider.MOCK_VTEP_TUNNEL_IPS;

public class TestVtep extends RestApiTestBase {

    public TestVtep() {
        super(FuncTest.appDesc);
    }

    private UUID goodTunnelZone = null;
    private UUID badTunnelZone = null;

    @Before
    public void before() {
        goodTunnelZone = postTunnelZone("tz");
        assertNotNull(goodTunnelZone);
        while (badTunnelZone == null || badTunnelZone.equals(goodTunnelZone)) {
            badTunnelZone = UUID.randomUUID();
        }
    }

    @Test
    public void testCreate() {
        DtoVtep vtep = postVtep(MOCK_VTEP_MGMT_IP, MOCK_VTEP_MGMT_PORT);
        assertEquals(VtepConnectionState.CONNECTED.toString(),
                     vtep.getConnectionState());
        assertEquals(MOCK_VTEP_DESC, vtep.getDescription());
        assertEquals(MOCK_VTEP_MGMT_IP, vtep.getManagementIp());
        assertEquals(MOCK_VTEP_MGMT_PORT, vtep.getManagementPort());
        assertEquals(MOCK_VTEP_NAME, vtep.getName());
        assertThat(vtep.getTunnelIpAddrs(),
                   containsInAnyOrder(MOCK_VTEP_TUNNEL_IPS.toArray()));
    }

    @Test
    public void testCreateWithNullIPAddr() {
        DtoError error = postVtepWithError(null, MOCK_VTEP_MGMT_PORT,
                                           Status.BAD_REQUEST);
        assertErrorMatchesPropMsg(error, "managementIp", "may not be null");
    }

    @Test
    public void testCreateWithBadTunnelZone() {
        DtoVtep vtep = makeVtep(MOCK_VTEP_MGMT_IP, MOCK_VTEP_MGMT_PORT,
                                UUID.randomUUID());
        DtoError error = dtoResource.postAndVerifyError(app.getVteps(),
                                                        APPLICATION_VTEP_JSON,
                                                        vtep,
                                                        Status.BAD_REQUEST);
        assertErrorMatchesPropMsg(error, "tunnelZoneId",
                                  "Tunnel zone ID is not valid.");
    }

    @Test
    public void testCreateWithNullTunnelZone() {
        DtoVtep vtep = makeVtep(MOCK_VTEP_MGMT_IP, MOCK_VTEP_MGMT_PORT, null);
        DtoError error = dtoResource.postAndVerifyError(app.getVteps(),
                                                        APPLICATION_VTEP_JSON,
                                                        vtep,
                                                        Status.BAD_REQUEST);
        assertErrorMatchesPropMsg(error, "tunnelZoneId",
                                  "Tunnel zone ID is not valid.");
    }

    @Test
    public void testCreateWithIllFormedIPAddr() {
        DtoError error = postVtepWithError("10.0.0.300", MOCK_VTEP_MGMT_PORT,
                                           Status.BAD_REQUEST);
        assertErrorMatchesPropMsg(error, "managementIp",
                                  getMessage(MessageProperty.IP_ADDR_INVALID));
    }

    @Test
    public void testCreateWithDuplicateIPAddr() {
        String ipAddr = MOCK_VTEP_MGMT_IP;
        postVtep(ipAddr, MOCK_VTEP_MGMT_PORT);
        DtoError error = postVtepWithError(ipAddr, MOCK_VTEP_MGMT_PORT + 1,
                                           Status.CONFLICT);
        assertErrorMatches(error, MessageProperty.VTEP_EXISTS, ipAddr);
    }

    @Test
    public void testCreateWithInaccessibleIpAddr() {
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
    public void testGet() {
        postVtep(MOCK_VTEP_MGMT_IP, MOCK_VTEP_MGMT_PORT);
        DtoVtep vtep = getVtep(MOCK_VTEP_MGMT_IP);
        assertEquals(MOCK_VTEP_MGMT_IP, vtep.getManagementIp());
        assertEquals(MOCK_VTEP_MGMT_PORT, vtep.getManagementPort());
    }

    @Test
    public void testGetWithInvalidIP() {
        DtoError error = getVtepWithError("10.0.0.300", Status.BAD_REQUEST);
        assertErrorMatches(error,
                           MessageProperty.IP_ADDR_INVALID_WITH_PARAM,
                           "10.0.0.300");
    }

    @Test
    public void testGetWithUnrecognizedIP() {
        postVtep(MOCK_VTEP_MGMT_IP, MOCK_VTEP_MGMT_PORT);
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
        // The mock client supports only one management IP/port, so
        // only one will successfully connect.
        DtoVtep[] expectedVteps = new DtoVtep[2];
        expectedVteps[0] = postVtep("10.0.0.1", 10001);
        assertEquals(VtepConnectionState.ERROR.toString(),
                     expectedVteps[0].getConnectionState());
        expectedVteps[1] = postVtep(MOCK_VTEP_MGMT_IP, MOCK_VTEP_MGMT_PORT);
        assertEquals(VtepConnectionState.CONNECTED.toString(),
                     expectedVteps[1].getConnectionState());

        DtoVtep[] actualVteps = listVteps();
        assertEquals(2, actualVteps.length);
        assertThat(actualVteps, arrayContainingInAnyOrder(expectedVteps));
    }

    @Test
    public void testAddBinding() {
        DtoBridge bridge = postBridge("network1");
        DtoVtep vtep = postVtep(MOCK_VTEP_MGMT_IP, MOCK_VTEP_MGMT_PORT);
        DtoVtepBinding binding = postBinding(vtep,
                makeBinding(MOCK_VTEP_PORT_NAMES[0], (short)1, bridge.getId()));
        assertEquals(bridge.getId(), binding.getNetworkId());
        assertEquals(MOCK_VTEP_PORT_NAMES[0], binding.getPortName());
        assertEquals((short)1, binding.getVlanId());

        // Should create a VXLAN port on the specified bridge.
        bridge = getBridge(bridge.getId());
        assertNotNull(bridge.getVxLanPortId());

        DtoVxLanPort port = getVxLanPort(bridge.getVxLanPortId());
        assertEquals(MOCK_VTEP_MGMT_IP, port.getMgmtIpAddr());
        assertEquals(MOCK_VTEP_MGMT_PORT, port.getMgmtPort());

        // TODO: Check that the port has the binding, once that is implemented.
    }

    private DtoVtep makeVtep(String mgmtIpAddr, int mgmtPort,
                             UUID tunnelZoneId) {
        DtoVtep vtep = new DtoVtep();
        vtep.setManagementIp(mgmtIpAddr);
        vtep.setManagementPort(mgmtPort);
        vtep.setTunnelZoneId(tunnelZoneId);
        return vtep;
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
            String portName, short vlanId, UUID networkId) {
        DtoVtepBinding binding = new DtoVtepBinding();
        binding.setPortName(portName);
        binding.setVlanId(vlanId);
        binding.setNetworkId(networkId);
        return binding;
    }

    private DtoVtepBinding postBinding(DtoVtep vtep, DtoVtepBinding binding) {
        return dtoResource.postAndVerifyCreated(
                vtep.getBindings(), APPLICATION_VTEP_BINDING_JSON,
                binding, DtoVtepBinding.class);
    }

    private DtoVxLanPort getVxLanPort(UUID id) {
        DtoPort port = getPort(id);
        assertNotNull(port);
        assertTrue(port instanceof DtoVxLanPort);
        return (DtoVxLanPort)port;
    }

    private UUID postTunnelZone(String name) {
        URI tunnelZonesUri = app.getTunnelZones();
        DtoGreTunnelZone tunnelZone = new DtoGreTunnelZone();
        tunnelZone.setName(name);
        tunnelZone = dtoResource.postAndVerifyCreated(tunnelZonesUri,
                  VendorMediaType.APPLICATION_TUNNEL_ZONE_JSON, tunnelZone,
                  DtoGreTunnelZone.class);
        assertNotNull(tunnelZone.getId());
        assertEquals(name, tunnelZone.getName());
        return tunnelZone.getId();
    }

}
/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.network;

import org.junit.Test;
import org.midonet.api.rest_api.FuncTest;
import org.midonet.api.rest_api.RestApiTestBase;

public class TestVtep extends RestApiTestBase {

    protected static final String MGMT_IP = "119.15.112.22";
    protected static final int MGMT_PORT = 6633;
    protected static final String VTEP_PORT_1 = "eth0";

    public TestVtep() {
        super(FuncTest.appDesc);
    }

    @Test
    public void dummyTest() {
        // Need at least one test to prevent the suite from failing.
    }

    // These tests run against a live VTEP. Disabling them until I (bberg)
    // can replace it with a mock VTEP client.
    // (Galo) - I added the tunnel zone id to the method calls, but using a
    // random uuid so these tests will probably break because the tunnel zone
    // doesn't actually exist. Will need fixing when tests are reenabled
    /*
    @Test
    public void testCreate() {
        postVtep(MGMT_IP, MGMT_PORT, UUID.randomUUID());
    }

    @Test
    public void testCreateWithNullIPAddr() {
        DtoError error = postVtepWithError(null, MGMT_PORT, Status.BAD_REQUEST);
        assertErrorMatchesPropMsg(error, "managementIp", "may not be null");
    }

    @Test
    public void testCreateWithInvalidIPAddr() {
        DtoError error = postVtepWithError("10.0.0.300", MGMT_PORT, Status.BAD_REQUEST);
        assertErrorMatchesPropMsg(error, "managementIp",
                                  getMessage(MessageProperty.IP_ADDR_INVALID));
    }

    @Test
    public void testCreateWithDuplicateIPAddr() {
        String ipAddr = MGMT_IP;
        postVtep(ipAddr, MGMT_PORT, UUID.randomUUID());
        DtoError error =
                postVtepWithError(ipAddr, MGMT_PORT + 1, Status.CONFLICT);
        assertErrorMatches(error, MessageProperty.VTEP_EXISTS, ipAddr);
    }

    @Test
    public void testGet() {
        postVtep(MGMT_IP, MGMT_PORT, UUID.randomUUID());
        DtoVtep vtep = getVtep(MGMT_IP);
        assertEquals(MGMT_IP, vtep.getManagementIp());
        assertEquals(MGMT_PORT, vtep.getManagementPort());
    }

    @Test
    public void testGetWithInvalidIP() {
        DtoError error = getVtepWithError("10.0.0.300", Status.BAD_REQUEST);
        assertErrorMatches(error,
                MessageProperty.IP_ADDR_INVALID_WITH_PARAM, "10.0.0.300");
    }

    @Test
    public void testGetWithUnrecognizedIP() {
        postVtep(MGMT_IP, MGMT_PORT, UUID.randomUUID());
        DtoError error = getVtepWithError("10.0.0.1", Status.NOT_FOUND);
        assertErrorMatches(error, VTEP_NOT_FOUND, "10.0.0.1");
    }

    @Test
    public void testListVtepsWithNoVteps() {
        DtoVtep[] vteps = listVteps();
        assertEquals(0, vteps.length);
    }

    @Test
    public void testListVtepsWithThreeVteps() {
        DtoVtep[] expectedVteps = new DtoVtep[3];
        for (int i = 0; i < 3; i++)
            expectedVteps[i] = postVtep("10.0.0." + i, 10000 + i,
                                        UUID.randomUUID());

        DtoVtep[] actualVteps = listVteps();
        assertEquals(3, actualVteps.length);
        assertThat(actualVteps, arrayContainingInAnyOrder(expectedVteps));
    }

    @Test
    public void testAddBindingCreatesPort() {
        DtoBridge bridge = postBridge("network1");
        DtoVtep vtep = postVtep(MGMT_IP, MGMT_PORT, UUID.randomUUID());
        DtoVtepBinding binding = postBinding(vtep,
                makeBinding(VTEP_PORT_1, (short)1, bridge.getId()));

        bridge = getBridge(bridge.getId());
        assertNotNull(bridge.getVxLanPortId());

        DtoVxLanPort port = getVxLanPort(bridge.getVxLanPortId());
        assertEquals(MGMT_IP, port.getMgmtIpAddr());
        assertEquals(MGMT_PORT, port.getMgmtPort());
    }

    private DtoVtep makeVtep(String mgmtIpAddr, int mgmtPort,
                             UUID tunnelZoneId) {
        DtoVtep vtep = new DtoVtep();
        vtep.setManagementIp(mgmtIpAddr);
        vtep.setManagementPort(mgmtPort);
        vtep.setTunnelZone(tunnelZoneId);
        return vtep;
    }

    private DtoVtep postVtep(String mgmtIpAddr, int mgmtPort,
                             UUID tunnelZoneId) {
        return postVtep(makeVtep(mgmtIpAddr, mgmtPort, tunnelZoneId));
    }

    private DtoVtep postVtep(DtoVtep vtep) {
        return dtoResource.postAndVerifyCreated(
                app.getVteps(), APPLICATION_VTEP_JSON, vtep, DtoVtep.class);
    }

    private DtoError postVtepWithError(
            String mgmtIpAddr, int mgmtPort, Status status) {
        DtoVtep vtep = makeVtep(mgmtIpAddr, mgmtPort, UUID.randomUUID());
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
                APPLICATION_VTEP_COLLECTION_JSON, DtoVtep[].class);
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
        ClientResponse response = dtoResource.postAndVerifyStatus(
                vtep.getBindings(), APPLICATION_VTEP_BINDING_JSON,
                binding, Status.CREATED.getStatusCode());
        binding.setUri(response.getLocation());
        return binding;
    }

    private DtoVxLanPort getVxLanPort(UUID id) {
        DtoPort port = getPort(id);
        assertNotNull(port);
        assertTrue(port instanceof DtoVxLanPort);
        return (DtoVxLanPort)port;
    }
    */
}

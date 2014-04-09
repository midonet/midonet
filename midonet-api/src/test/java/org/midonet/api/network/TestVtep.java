/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.api.network;

import org.midonet.api.rest_api.DtoWebResource;
import org.midonet.api.rest_api.FuncTest;
import org.midonet.api.rest_api.RestApiTestBase;
import org.midonet.api.rest_api.Topology;
import org.midonet.api.validation.MessageProperty;
import org.midonet.api.zookeeper.StaticMockDirectory;
import org.midonet.client.dto.DtoApplication;
import org.midonet.client.dto.DtoError;
import org.midonet.client.dto.DtoVtep;

import javax.ws.rs.core.Response.Status;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.Matchers.arrayContainingInAnyOrder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.midonet.api.VendorMediaType.APPLICATION_VTEP_COLLECTION_JSON;
import static org.midonet.api.VendorMediaType.APPLICATION_VTEP_JSON;
import static org.midonet.api.validation.MessageProperty.VTEP_NOT_FOUND;
import static org.midonet.api.validation.MessageProperty.getMessage;

public class TestVtep extends RestApiTestBase {

    protected DtoWebResource dtoWebResource;
    protected Topology topology;
    protected DtoApplication app;

    public TestVtep() {
        super(FuncTest.appDesc);
    }

    @Before
    public void setUp() {
        dtoWebResource = new DtoWebResource(resource());
        topology = new Topology.Builder(dtoWebResource).build();
        app = topology.getApplication();
    }

    @After
    public void resetDirectory() {
        StaticMockDirectory.clearDirectoryInstance();
    }

    @Test
    public void testCreate() {
        postVtep("10.0.0.1", 10001);
    }

    @Test
    public void testCreateWithNullIPAddr() {
        DtoError error = postVtepWithError(null, 10001, Status.BAD_REQUEST);
        assertErrorMatchesPropMsg(error, "managementIp", "may not be null");
    }

    @Test
    public void testCreateWithInvalidIPAddr() {
        DtoError error = postVtepWithError("10.0.0.300", 10001, Status.BAD_REQUEST);
        assertErrorMatchesPropMsg(error, "managementIp",
                                  getMessage(MessageProperty.IP_ADDR_INVALID));
    }

    @Test
    public void testCreateWithDuplicateIPAddr() {
        String ipAddr = "10.0.0.1";
        postVtep(ipAddr, 10001);
        DtoError error = postVtepWithError(ipAddr, 10002, Status.CONFLICT);
        assertErrorMatches(error, MessageProperty.VTEP_EXISTS, ipAddr);
    }

    @Test
    public void testGet() {
        postVtep("10.0.0.1", 10001);
        DtoVtep vtep = getVtep("10.0.0.1");
        assertEquals("10.0.0.1", vtep.getManagementIp());
        assertEquals(10001, vtep.getManagementPort());
    }

    @Test
    public void testGetWithInvalidIP() {
        DtoError error = getVtepWithError("10.0.0.300", Status.BAD_REQUEST);
        assertErrorMatches(error,
                MessageProperty.IP_ADDR_INVALID_WITH_PARAM, "10.0.0.300");
    }

    @Test
    public void testGetWithUnrecognizedIP() {
        postVtep("10.0.0.1", 10001);
        DtoError error = getVtepWithError("10.0.0.2", Status.NOT_FOUND);
        assertErrorMatches(error, VTEP_NOT_FOUND, "10.0.0.2");
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
            expectedVteps[i] = postVtep("10.0.0." + i, 10000 + i);

        DtoVtep[] actualVteps = listVteps();
        assertEquals(3, actualVteps.length);
        assertThat(actualVteps, arrayContainingInAnyOrder(expectedVteps));
    }

    private DtoVtep makeVtep(String mgmtIpAddr, int mgmtPort) {
        DtoVtep vtep = new DtoVtep();
        vtep.setManagementIp(mgmtIpAddr);
        vtep.setManagementPort(mgmtPort);
        return vtep;
    }

    private DtoVtep postVtep(String mgmtIpAddr, int mgmtPort) {
        return postVtep(makeVtep(mgmtIpAddr, mgmtPort));
    }

    private DtoVtep postVtep(DtoVtep vtep) {
        return dtoWebResource.postAndVerifyCreated(
                app.getVteps(), APPLICATION_VTEP_JSON, vtep, DtoVtep.class);
    }

    private DtoError postVtepWithError(
            String mgmtIpAddr, int mgmtPort, Status status) {
        DtoVtep vtep = makeVtep(mgmtIpAddr, mgmtPort);
        return dtoWebResource.postAndVerifyError(
                app.getVteps(), APPLICATION_VTEP_JSON, vtep, status);
    }

    private DtoVtep getVtep(String mgmtIpAddr) {
        return dtoWebResource.getAndVerifyOk(
                app.getVtep(mgmtIpAddr), APPLICATION_VTEP_JSON, DtoVtep.class);
    }

    private DtoError getVtepWithError(String mgmtIpAddr, Status status) {
        return dtoWebResource.getAndVerifyError(
                app.getVtep(mgmtIpAddr), APPLICATION_VTEP_JSON, status);
    }

    private DtoVtep[] listVteps() {
        return dtoWebResource.getAndVerifyOk(app.getVteps(),
                APPLICATION_VTEP_COLLECTION_JSON, DtoVtep[].class);
    }
}

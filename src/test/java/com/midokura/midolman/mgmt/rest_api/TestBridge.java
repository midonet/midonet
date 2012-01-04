/*
 * @(#)TestBridge        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api;

import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_BRIDGE_JSON;
import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_TENANT_JSON;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.client.DtoBridge;
import com.midokura.midolman.mgmt.data.dto.client.DtoTenant;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;

public class TestBridge extends JerseyTest {

    private final static Logger log = LoggerFactory.getLogger(TestBridge.class);
    private final String testTenantName = "TEST-TENANT";
    private final String testBridgeName = "TEST-BRIDGE";

    private WebResource resource;
    private ClientResponse response;
    private URI testBridgeUri;
    DtoBridge bridge = new DtoBridge();

    public TestBridge() {
        super(FuncTest.appDesc);
    }

    @Before
    public void before() {

        DtoTenant tenant = new DtoTenant();
        tenant.setId(testTenantName);

        resource = resource().path("tenants");
        response = resource.type(APPLICATION_TENANT_JSON).accept(APPLICATION_TENANT_JSON).post(
                ClientResponse.class, tenant);
        log.debug("status: {}", response.getStatus());
        log.debug("location: {}", response.getLocation());
        assertEquals(201, response.getStatus());
        assertTrue(response.getLocation().toString().endsWith("tenants/" + testTenantName));

        bridge.setName(testBridgeName);
        resource = resource().path("tenants/" + testTenantName + "/bridges");
        response = resource.type(APPLICATION_BRIDGE_JSON).post(
                ClientResponse.class, bridge);

        log.debug("bridge location: {}", response.getLocation());
        testBridgeUri = response.getLocation();
    }

    @Test
    public void testCreateWithEmptyBody() {

        resource = resource().path("tenants/" + testTenantName + "/bridges");
        response = resource.type(APPLICATION_BRIDGE_JSON).post(
                ClientResponse.class, "{}");
        String body = response.getEntity(String.class);

        log.debug("status: {}", response.getStatus());
        log.debug("location: {}", response.getLocation());
        log.debug("body: {}", body);

        String idString = response.getLocation().toString();
        idString = idString.substring(idString.lastIndexOf("/") + 1, idString.length());
        log.debug("idString: {}", idString);
        try {
            UUID.fromString(idString);
        } catch (Exception e) {
            Assert.fail("failed: returned tenant id doesn't conform to UUID form. {}" +  e);
        }
    }

    @Test
    public void testCreateWithGivenIdAndNameAndGet() {
        UUID id = UUID.randomUUID();
        bridge.setName("TEST-CREATE-BRIDGE");
        bridge.setId(id);

        resource = resource().path("tenants/" + testTenantName + "/bridges");
        response = resource.type(APPLICATION_BRIDGE_JSON).post(
                ClientResponse.class, bridge);
        String body = response.getEntity(String.class);
        URI bridgeUri = response.getLocation();

        log.debug("status: {}", response.getStatus());
        log.debug("location: {}", bridgeUri);
        log.debug("body: {}", body);

        String idString = response.getLocation().toString();
        idString = idString.substring(idString.lastIndexOf("/") + 1, idString.length());
        log.debug("idString: {}", idString);
        try {
            UUID.fromString(idString);
        } catch (Exception e) {
            Assert.fail("failed: returned tenant id doesn't conform to UUID form. {}" +  e);
        }

        bridge = resource().uri(bridgeUri).type(APPLICATION_BRIDGE_JSON).get(DtoBridge.class);
        assertEquals("TEST-CREATE-BRIDGE", bridge.getName());
        assertEquals(id, bridge.getId());
        log.debug("bridge port: {}", bridge.getPorts());
        log.debug("bridge uri: {}", bridge.getUri());
    }

    @Test
    public void testGet() {
        bridge = resource().uri(testBridgeUri).type(APPLICATION_BRIDGE_JSON).get(DtoBridge.class);
        log.debug("testGet {}", bridge.getName());
        log.debug("testGet {}", bridge.getId());
        log.debug("testGet {}", bridge.getTenantId());
        log.debug("testGet {}", bridge.getPorts());
        log.debug("testGet {}", bridge.getUri());

        assertEquals(testBridgeName, bridge.getName());
        assertEquals(testBridgeUri, bridge.getUri());
        assertEquals(testTenantName, bridge.getTenantId());
    }

    @Test
    public void testUpdate() {
        final String newName = "NEW-BRIDGE-NAME";

        bridge = new DtoBridge();
        bridge.setName(newName);

        response = resource().uri(testBridgeUri).type(APPLICATION_BRIDGE_JSON).put(ClientResponse.class, bridge);
        log.debug("status: {}", response.getStatus());
        log.debug("body of Bridge Update: {}", response.getEntity(String.class));
        assertEquals(200, response.getStatus());


        response = resource().uri(testBridgeUri).type(APPLICATION_BRIDGE_JSON).get(ClientResponse.class);
        bridge = response.getEntity(DtoBridge.class);
        log.debug("name: {}", bridge.getName());
        assertEquals(200, response.getStatus());
        assertEquals(newName, bridge.getName());
    }

    @Test
    public void testDelete() {
        response = resource().uri(testBridgeUri).delete(ClientResponse.class);
        log.debug("status: {}", response.getStatus());
        assertEquals(204, response.getStatus());
    }

    @Test
    public void testList() {
        response = resource().uri(testBridgeUri).get(ClientResponse.class);
        String body = response.getEntity(String.class);
        log.debug("status: {}", response.getStatus());
        log.debug("body: {}", body);

        assertEquals(200, response.getStatus());
    }
}
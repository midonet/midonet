/*
 * @(#)testPort        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api;


import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_BRIDGE_JSON;
import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_PORT_COLLECTION_JSON;
import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_PORT_JSON;
import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_ROUTER_JSON;
import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_TENANT_JSON;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.URI;

import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dto.DtoBridge;
import com.midokura.midolman.mgmt.data.dto.DtoRouter;
import com.midokura.midolman.mgmt.data.dto.DtoTenant;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;

public class TestPort extends JerseyTest {

    private final static Logger log = LoggerFactory.getLogger(TestPort.class);
    private final String testTenantName = "TEST-TENANT";
    private final String testRouterName = "TEST-ROUTER";
    private final String testBridgeName = "TEST-BRIDGE";

    private WebResource resource;
    private ClientResponse response;
    private URI testRouterUri;
    private URI testBridgeUri;
    DtoRouter router = new DtoRouter();

    public TestPort() {
        super(FuncTest.appDesc);
    }

    @Before
    public void before() {

        DtoTenant tenant = new DtoTenant();
        tenant.setId(testTenantName);

        resource = resource().path("tenants");
        response = resource.type(APPLICATION_TENANT_JSON).post(
                ClientResponse.class, tenant);
        log.debug("status: {}", response.getStatus());
        log.debug("location: {}", response.getLocation());
        assertEquals(201, response.getStatus());
        assertTrue(response.getLocation().toString().endsWith("tenants/" + testTenantName));

        // Create a router.
        router.setName(testRouterName);
        resource = resource().path("tenants/" + testTenantName + "/routers");
        response = resource.type(APPLICATION_ROUTER_JSON).post(
                ClientResponse.class, router);

        log.debug("router location: {}", response.getLocation());
        testRouterUri = response.getLocation();

        // Create a bridge.
        DtoBridge bridge = new DtoBridge();
        bridge.setName(testBridgeName);
        resource = resource().path("tenants/" + testTenantName + "/bridges");
        response = resource.type(APPLICATION_BRIDGE_JSON).post(
                ClientResponse.class, bridge);

        log.debug("bridge location: {}", response.getLocation());
        testBridgeUri = response.getLocation();
    }

    @Test
    public void testCreateGetListDeleteBridgePort() {
        // Create a bridge port.
        URI bridgePortUri = URI.create(testBridgeUri.toString() + "/ports");
        log.debug("bridgePortUri: {}", bridgePortUri);
        response = resource().uri(bridgePortUri).type(APPLICATION_PORT_JSON).post(ClientResponse.class, "{}");
        assertEquals(201, response.getStatus());

        // Get the port
        URI portUri = response.getLocation();
        response = resource().uri(portUri).type(APPLICATION_PORT_JSON).get(ClientResponse.class);
        assertEquals(200, response.getStatus());
        log.debug("body: {}", response.getEntity(String.class));

        // List ports
        response = resource().uri(bridgePortUri).type(APPLICATION_PORT_COLLECTION_JSON).get(ClientResponse.class);
        assertEquals(200, response.getStatus());
        log.debug("body: {}", response.getEntity(String.class));

        // Delete the port.
        response = resource().uri(portUri).type(APPLICATION_PORT_JSON).delete(ClientResponse.class);
        assertEquals(204, response.getStatus());
    }

    @Test
    public void testCreateGetListDeleteRouterPort() {

        // Create a router port.
        URI routerPortUri = URI.create(testRouterUri.toString() + "/ports");
        log.debug("routerPortUri: {} ", routerPortUri);
        String requestBody = "{\"networkAddress\": \"10.0.0.0\", \"networkLength\": 24, " +
        		"\"portAddress\":\"10.0.0.1\", \"localNetworkAddress\": \"10.0.0.2\", " +
        		"\"localNetworkLength\": 32, \"vifId\":\"372b0040-12ae-11e1-be50-0800200c9a66\" }";
        response = resource().uri(routerPortUri).type(APPLICATION_PORT_JSON).post(ClientResponse.class, requestBody);
        log.debug("location: {}", response.getLocation());
        assertEquals(201, response.getStatus());

        // Get the port.
        URI portUri = response.getLocation();
        response = resource().uri(portUri).type(APPLICATION_PORT_JSON).get(ClientResponse.class);
        assertEquals(200, response.getStatus());
        log.debug("body: {}", response.getEntity(String.class));

        // List ports
        response = resource().uri(routerPortUri).type(APPLICATION_PORT_COLLECTION_JSON).get(ClientResponse.class);
        assertEquals(200, response.getStatus());
        log.debug("body: {}", response.getEntity(String.class));

        // Delete the port.
        response = resource().uri(portUri).type(APPLICATION_PORT_JSON).delete(ClientResponse.class);
        assertEquals(204, response.getStatus());
    }

    //@Test
    public void testGetDeleteLogicalPort() {
        // not implemented yet
    }
}
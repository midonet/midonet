/*
 * @(#)testVif        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api;

import java.net.URI;
import java.util.UUID;

import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dto.client.DtoMaterializedRouterPort;
import com.midokura.midolman.mgmt.data.dto.client.DtoRouter;
import com.midokura.midolman.mgmt.data.dto.client.DtoRuleChain;
import com.midokura.midolman.mgmt.data.dto.client.DtoTenant;


import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TestChain extends JerseyTest {

    private final static Logger log = LoggerFactory.getLogger(TestChain.class);
    private final String testTenantName = "TEST-TENANT";
    private final String testRouterName = "TEST-ROUTER";

    private WebResource resource;
    private ClientResponse response;
    private URI testRouterUri;
    private UUID testRouterPortId;

    DtoRouter router = new DtoRouter();

    public TestChain() {
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

        // Create a materialized router port.
        URI routerPortUri = URI.create(testRouterUri.toString() + "/ports");
        DtoMaterializedRouterPort port = new DtoMaterializedRouterPort();
        port.setNetworkAddress("10.0.0.0");
        port.setNetworkLength(24);
        port.setPortAddress("10.0.0.1");
        port.setLocalNetworkAddress("10.0.0.2");
        port.setLocalNetworkLength(32);
        port.setVifId(UUID.fromString("372b0040-12ae-11e1-be50-0800200c9a66"));

        response = resource().uri(routerPortUri).type(APPLICATION_PORT_JSON).post(ClientResponse.class, port);
        assertEquals(201, response.getStatus());
        log.debug("location: {}", response.getLocation());

        testRouterPortId = FuncTest.getUuidFromLocation(response.getLocation());
    }

    @Test
    public void testCreateGetListDelete() {
        DtoRuleChain ruleChain = new DtoRuleChain();

        ruleChain.setName("foo_chain");
        // Create a chain
        URI routerChainUri = URI.create(testRouterUri.toString() + "/chains");
        response = resource().uri(routerChainUri).type(APPLICATION_CHAIN_JSON).post(ClientResponse.class, ruleChain);

        URI ruleChainUri = response.getLocation();
        log.debug("status {}", response.getStatus());
        log.debug("location {}", response.getLocation());
        assertEquals(201, response.getStatus());

        // Get the chain
        response = resource().uri(ruleChainUri).accept(APPLICATION_CHAIN_JSON).get(ClientResponse.class);
        assertEquals(200, response.getStatus());
        log.debug("body: {}", response.getEntity(String.class));


        // List chains
        response = resource().uri(routerChainUri).accept(APPLICATION_CHAIN_COLLECTION_JSON).get(ClientResponse.class);
        log.debug("{}", response.getEntity(String.class));
        assertEquals(200, response.getStatus());

        //Delete the chain
        response = resource().uri(ruleChainUri).delete(ClientResponse.class);
        assertEquals(204, response.getStatus());
    }

}

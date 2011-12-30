/*
 * @(#)testVif        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api;

import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_PORT_JSON;
import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_ROUTER_JSON;
import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_ROUTE_COLLECTION_JSON;
import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_ROUTE_JSON;
import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_TENANT_JSON;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.DtoMaterializedRouterPort;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dto.DtoRoute;
import com.midokura.midolman.mgmt.data.dto.DtoRouter;
import com.midokura.midolman.mgmt.data.dto.DtoTenant;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;

public class TestRoute extends JerseyTest {

    private final static Logger log = LoggerFactory.getLogger(TestRoute.class);
    private final String testTenantName = "TEST-TENANT";
    private final String testRouterName = "TEST-ROUTER";
    private final String testBridgeName = "TEST-BRIDGE";

    private WebResource resource;
    private ClientResponse response;
    private URI testRouterUri;
    private URI testBridgeUri;

    private UUID testRouterPortId;
    private UUID testBridgePortId;

    DtoRouter router = new DtoRouter();

    public TestRoute() {
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
        log.debug("routerPortUri: {} ", routerPortUri);
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
        DtoRoute route = new DtoRoute();

        String srcNetworkAddr = "10.0.0.0";
        int srcNetworkLength = 24;
        String type = "Normal";
        String dstNetworkAddr = "192.168.0.0";
        int dstNetworkLength = 24;
        UUID nextHopPort = testRouterPortId;
        String nextHopGateway = "192.168.0.1";
        int weight = 100;

        // Create a route
        route.setSrcNetworkAddr(srcNetworkAddr);
        route.setSrcNetworkLength(srcNetworkLength);
        route.setType(type);
        route.setDstNetworkAddr(dstNetworkAddr);
        route.setDstNetworkLength(dstNetworkLength);
        route.setNextHopPort(nextHopPort);
        route.setNextHopGateway(nextHopGateway);
        route.setWeight(weight);

        URI routerRouteUri = URI.create(testRouterUri.toString() + "/routes");
        response = resource().uri(routerRouteUri).type(APPLICATION_ROUTE_JSON).post(ClientResponse.class, route);

        URI routeUri = response.getLocation();
        log.debug("status {}", response.getStatus());
        log.debug("location {}", response.getLocation());
        assertEquals(201, response.getStatus());

        // Get the route
        response = resource().uri(routeUri).accept(APPLICATION_ROUTE_JSON).get(ClientResponse.class);
        route = response.getEntity(DtoRoute.class);
        assertEquals(200, response.getStatus());

        assertEquals(srcNetworkAddr, route.getSrcNetworkAddr());
        assertEquals(srcNetworkLength, route.getSrcNetworkLength());
        assertEquals(type, route.getType());
        assertEquals(dstNetworkAddr, route.getDstNetworkAddr());
        assertEquals(dstNetworkLength, route.getDstNetworkLength());
        assertEquals(nextHopPort, route.getNextHopPort());
        assertEquals(nextHopGateway, route.getNextHopGateway());
        assertEquals(weight, route.getWeight());

        // List Routes
        response = resource().uri(routerRouteUri).accept(APPLICATION_ROUTE_COLLECTION_JSON).get(ClientResponse.class);
        log.debug("body: {}", response.getEntity(String.class));
        assertEquals(200, response.getStatus());

        // Delete the route
        response = resource().uri(routeUri).type(APPLICATION_ROUTE_JSON).delete(ClientResponse.class);
        assertEquals(204, response.getStatus());
    }
}

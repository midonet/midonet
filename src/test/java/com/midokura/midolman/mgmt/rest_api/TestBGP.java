/*
 * @(#)testVif        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api;

import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_BGP_COLLECTION_JSON;
import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_BGP_JSON;
import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_PORT_JSON;
import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.APPLICATION_ROUTER_JSON;
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

import com.midokura.midolman.mgmt.data.dto.DtoBgp;
import com.midokura.midolman.mgmt.data.dto.DtoRouter;
import com.midokura.midolman.mgmt.data.dto.DtoTenant;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;

public class TestBGP extends JerseyTest {

    private final static Logger log = LoggerFactory.getLogger(TestBGP.class);
    private final String testTenantName = "TEST-TENANT";
    private final String testRouterName = "TEST-ROUTER";

    private WebResource resource;
    private ClientResponse response;
    private URI testRouterUri;

    private UUID testRouterPortId;

    DtoRouter router = new DtoRouter();

    public TestBGP() {
        super(FuncTest.appDesc);
    }

    // This one also tests Create with given tenant ID string
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

        DtoBgp bgp = new DtoBgp();
        bgp.setLocalAS(55394);
        bgp.setPeerAS(65104);
        bgp.setPeerAddr("180.214.47.65");

        //Create bgp
        response = resource().path("/ports/" + testRouterPortId + "/bgps").type(APPLICATION_BGP_JSON).post(ClientResponse.class, bgp);
        assertEquals(201, response.getStatus());
        URI bgpUri = response.getLocation();

        //Get the bgp
        response = resource().uri(bgpUri).accept(APPLICATION_BGP_JSON).get(ClientResponse.class);
        bgp = response.getEntity(DtoBgp.class);
        assertEquals(200, response.getStatus());
        assertEquals(55394, bgp.getLocalAS());
        assertEquals(65104, bgp.getPeerAS());
        assertEquals("180.214.47.65", bgp.getPeerAddr());
        log.debug("adRoute {}", bgp.getAdRoutes());

        //List bgps

        response = resource().path("/ports/" + testRouterPortId + "/bgps").accept(APPLICATION_BGP_COLLECTION_JSON).get(ClientResponse.class);
        assertEquals(200, response.getStatus());
        log.debug("BODY: {}", response.getEntity(String.class));

        //Delete the chain
        response = resource().uri(bgpUri).delete(ClientResponse.class);
        assertEquals(204, response.getStatus());
    }
}
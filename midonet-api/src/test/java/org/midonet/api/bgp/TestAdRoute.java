/*
 * Copyright 2011 Midokura KK
 */
package org.midonet.api.bgp;

import org.midonet.api.VendorMediaType;
import org.midonet.api.zookeeper.StaticMockDirectory;
import org.midonet.api.rest_api.FuncTest;
import org.midonet.client.dto.DtoAdRoute;
import org.midonet.client.dto.DtoBgp;
import org.midonet.client.dto.DtoRouterPort;
import org.midonet.client.dto.DtoRouter;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.UUID;

import static org.junit.Assert.assertEquals;

public class TestAdRoute extends JerseyTest {

    private final static Logger log = LoggerFactory.getLogger(
            TestAdRoute.class);
    private final String testTenantId= "TEST-TENANT";
    private final String testRouterName = "TEST-ROUTER";

    private WebResource resource;
    private ClientResponse response;
    private URI testRouterUri;
    private URI adRoutesUri;
    private URI bgpUri;

    private UUID testRouterPortId;

    DtoRouter router = new DtoRouter();

    public TestAdRoute() {
        super(FuncTest.appDesc);
    }

    @Before
    public void before() {

        // Create a router.
        router.setName(testRouterName);
        router.setTenantId(testTenantId);
        resource = resource().path("/routers");
        response = resource.type(VendorMediaType.APPLICATION_ROUTER_JSON_V2).post(
                ClientResponse.class, router);

        log.debug("router location: {}", response.getLocation());
        testRouterUri = response.getLocation();

        // Create a Exterior router port.
        URI routerPortUri = URI.create(testRouterUri.toString() + "/ports");
        DtoRouterPort port = new DtoRouterPort();
        port.setNetworkAddress("10.0.0.0");
        port.setNetworkLength(24);
        port.setPortAddress("10.0.0.1");
        port.setVifId(UUID.fromString("372b0040-12ae-11e1-be50-0800200c9a66"));

        response = resource().uri(routerPortUri).type(VendorMediaType
            .APPLICATION_PORT_V2_JSON)
                .post(ClientResponse.class, port);
        assertEquals(201, response.getStatus());
        log.debug("location: {}", response.getLocation());

        testRouterPortId = FuncTest.getUuidFromLocation(response.getLocation());

        DtoBgp bgp = new DtoBgp();
        bgp.setLocalAS(55394);
        bgp.setPeerAS(65104);
        bgp.setPeerAddr("180.214.47.65");

        //Create bgp
        response = resource().path("/ports/" + testRouterPortId + "/bgps")
                .type(VendorMediaType.APPLICATION_BGP_JSON).post(ClientResponse.class, bgp);
        assertEquals(201, response.getStatus());
        bgpUri = response.getLocation();
        log.debug("BGPURI: {}", bgpUri);

        //Get the adRoute Uri
        response = resource().uri(bgpUri).accept(VendorMediaType.APPLICATION_BGP_JSON)
                .get(ClientResponse.class);
        bgp = response.getEntity(DtoBgp.class);

        adRoutesUri = bgp.getAdRoutes();
        log.debug("adRoute {}", adRoutesUri);
    }

    @After
    public void resetDirectory() throws Exception {
        StaticMockDirectory.clearDirectoryInstance();
    }

    @Test
    public void testCreateGetListDelete() {
        DtoAdRoute adRoute = new DtoAdRoute();
        adRoute.setNwPrefix("14.128.23.0");
        adRoute.setPrefixLength(27);

        //Create a adRoute
        response = resource().uri(adRoutesUri).type(VendorMediaType.APPLICATION_AD_ROUTE_JSON)
                .post(ClientResponse.class, adRoute);
        assertEquals(201, response.getStatus());
        URI adRouteUri = response.getLocation();

        //Get the adRoute
        response = resource().uri(adRouteUri).accept(VendorMediaType.APPLICATION_AD_ROUTE_JSON)
                .get(ClientResponse.class);
        adRoute = response.getEntity(DtoAdRoute.class);
        assertEquals(200, response.getStatus());
        assertEquals("14.128.23.0", adRoute.getNwPrefix());
        assertEquals(27, adRoute.getPrefixLength());

        //List adRoutes
        response = resource().uri(URI.create(bgpUri.toString() + "/ad_routes"))
                .accept(VendorMediaType.APPLICATION_AD_ROUTE_COLLECTION_JSON)
                .get(ClientResponse.class);
        assertEquals(200, response.getStatus());
        log.debug("BODY: {}", response.getEntity(String.class));

        //Delete the adRoute
        response = resource().uri(adRouteUri).delete(ClientResponse.class);
        assertEquals(204, response.getStatus());
    }
}

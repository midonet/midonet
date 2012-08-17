/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.rest_api;

import static com.midokura.midolman.mgmt.http.VendorMediaType.APPLICATION_PORT_JSON;
import static com.midokura.midolman.mgmt.http.VendorMediaType.APPLICATION_ROUTER_JSON;
import static com.midokura.midolman.mgmt.http.VendorMediaType.APPLICATION_ROUTE_COLLECTION_JSON;
import static com.midokura.midolman.mgmt.http.VendorMediaType.APPLICATION_ROUTE_JSON;
import static com.midokura.midolman.mgmt.http.VendorMediaType.APPLICATION_TENANT_JSON;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.zookeeper.StaticMockDirectory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dto.Route;
import com.midokura.midolman.mgmt.data.dto.client.DtoError;
import com.midokura.midolman.mgmt.data.dto.client.DtoMaterializedRouterPort;
import com.midokura.midolman.mgmt.data.dto.client.DtoRoute;
import com.midokura.midolman.mgmt.data.dto.client.DtoRouter;
import com.midokura.midolman.mgmt.data.dto.client.DtoTenant;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;

@RunWith(Enclosed.class)
public class TestRoute {

    private final static Logger log = LoggerFactory.getLogger(TestRoute.class);

    public static class TestRouteCrud extends JerseyTest {

        private final String testTenantName = "TEST-TENANT";
        private final String testRouterName = "TEST-ROUTER";

        private WebResource resource;
        private ClientResponse response;
        private URI testRouterUri;

        private UUID testRouterPortId;

        DtoRouter router = new DtoRouter();

        public TestRouteCrud() {
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
            assertTrue(response.getLocation().toString()
                    .endsWith("tenants/" + testTenantName));

            // Create a router.
            router.setName(testRouterName);
            resource = resource()
                    .path("tenants/" + testTenantName + "/routers");
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
            port.setVifId(UUID
                    .fromString("372b0040-12ae-11e1-be50-0800200c9a66"));

            response = resource().uri(routerPortUri)
                    .type(APPLICATION_PORT_JSON)
                    .post(ClientResponse.class, port);
            assertEquals(201, response.getStatus());
            log.debug("location: {}", response.getLocation());

            testRouterPortId = FuncTest.getUuidFromLocation(response
                    .getLocation());
        }

        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        @Test
        public void testNullNextHopGateway() {
            DtoRoute route = new DtoRoute();
            String srcNetworkAddr = "10.0.0.0";
            int srcNetworkLength = 24;
            String type = "Normal";
            String dstNetworkAddr = "192.168.0.0";
            int dstNetworkLength = 24;
            UUID nextHopPort = testRouterPortId;
            String nextHopGateway = null;
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

            URI routerRouteUri = URI.create(testRouterUri.toString()
                    + "/routes");
            response = resource().uri(routerRouteUri)
                    .type(APPLICATION_ROUTE_JSON)
                    .post(ClientResponse.class, route);

            URI routeUri = response.getLocation();
            log.debug("status {}", response.getStatus());
            log.debug("location {}", response.getLocation());
            assertEquals(201, response.getStatus());

            // Get the route
            response = resource().uri(routeUri).accept(APPLICATION_ROUTE_JSON)
                    .get(ClientResponse.class);
            route = response.getEntity(DtoRoute.class);
            assertEquals(200, response.getStatus());

            // Check the next hop gateway is null
            assertNull(nextHopGateway);

            // Delete the route
            response = resource().uri(routeUri).type(APPLICATION_ROUTE_JSON)
                    .delete(ClientResponse.class);
            assertEquals(204, response.getStatus());
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

            URI routerRouteUri = URI.create(testRouterUri.toString()
                    + "/routes");
            response = resource().uri(routerRouteUri)
                    .type(APPLICATION_ROUTE_JSON)
                    .post(ClientResponse.class, route);

            URI routeUri = response.getLocation();
            log.debug("status {}", response.getStatus());
            log.debug("location {}", response.getLocation());
            assertEquals(201, response.getStatus());

            // Get the route
            response = resource().uri(routeUri).accept(APPLICATION_ROUTE_JSON)
                    .get(ClientResponse.class);
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
            response = resource().uri(routerRouteUri)
                    .accept(APPLICATION_ROUTE_COLLECTION_JSON)
                    .get(ClientResponse.class);
            log.debug("body: {}", response.getEntity(String.class));
            assertEquals(200, response.getStatus());

            // Delete the route
            response = resource().uri(routeUri).type(APPLICATION_ROUTE_JSON)
                    .delete(ClientResponse.class);
            assertEquals(204, response.getStatus());
        }
    }

    @RunWith(Parameterized.class)
    public static class TestRouteCreateBadRequest extends JerseyTest {

        private final DtoRoute route;
        private final String property;
        private DtoWebResource dtoResource;
        private Topology topology;

        public TestRouteCreateBadRequest(DtoRoute route, String property) {
            super(FuncTest.appDesc);
            this.route = route;
            this.property = property;
        }

        @Before
        public void setUp() {
            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);

            // Create a tenant
            DtoTenant t = new DtoTenant();
            t.setId("tenant1-id");

            // Create a router
            DtoRouter r = new DtoRouter();
            r.setName("router1-name");

            topology = new Topology.Builder(dtoResource).create("tenant1", t)
                    .create("tenant1", "router1", r).build();
        }

        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        @Parameters
        public static Collection<Object[]> data() {

            List<Object[]> params = new ArrayList<Object[]>();

            // Null type
            DtoRoute nullType = new DtoRoute();
            nullType.setType(null);
            nullType.setDstNetworkAddr("10.0.0.1");
            nullType.setDstNetworkLength(24);
            nullType.setSrcNetworkAddr("192.168.1.1");
            nullType.setSrcNetworkLength(24);
            nullType.setWeight(100);
            params.add(new Object[] { nullType, "type" });

            // Invalid type
            DtoRoute invalidType = new DtoRoute();
            invalidType.setType("badType");
            invalidType.setDstNetworkAddr("10.0.0.1");
            invalidType.setDstNetworkLength(24);
            invalidType.setSrcNetworkAddr("192.168.1.1");
            invalidType.setSrcNetworkLength(24);
            invalidType.setWeight(100);
            params.add(new Object[] { invalidType, "type" });

            // Normal type but no next hop port
            DtoRoute noNextHop = new DtoRoute();
            noNextHop.setType(Route.Normal);
            noNextHop.setDstNetworkAddr("10.0.0.1");
            noNextHop.setDstNetworkLength(24);
            noNextHop.setSrcNetworkAddr("192.168.1.1");
            noNextHop.setSrcNetworkLength(24);
            noNextHop.setWeight(100);
            params.add(new Object[] { noNextHop, "nextHopPort" });

            return params;
        }

        @Test
        public void testBadInputCreate() {

            DtoRouter router1 = topology.getRouter("router1");
            route.setRouterId(router1.getId());

            DtoError error = dtoResource.postAndVerifyBadRequest(
                    router1.getRoutes(), APPLICATION_ROUTE_JSON, route);
            List<Map<String, String>> violations = error.getViolations();
            assertEquals(1, violations.size());
            assertEquals(property, violations.get(0).get("property"));
        }
    }
}

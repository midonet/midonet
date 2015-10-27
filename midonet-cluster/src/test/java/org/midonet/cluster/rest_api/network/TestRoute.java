/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.cluster.rest_api.network;

import java.net.URI;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import javax.ws.rs.core.Response;

import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.rest_api.rest_api.DtoWebResource;
import org.midonet.cluster.rest_api.rest_api.FuncTest;
import org.midonet.cluster.rest_api.rest_api.Topology;
import org.midonet.client.dto.DtoApplication;
import org.midonet.client.dto.DtoBridge;
import org.midonet.client.dto.DtoBridgePort;
import org.midonet.client.dto.DtoLink;
import org.midonet.client.dto.DtoRoute;
import org.midonet.client.dto.DtoRouter;
import org.midonet.client.dto.DtoRouterPort;
import org.midonet.client.dto.DtoRuleChain;
import org.midonet.cluster.rest_api.models.Route;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.*;

@RunWith(Enclosed.class)
public class TestRoute {

    private final static Logger log = LoggerFactory.getLogger(TestRoute.class);

    public static class TestRouteCrud extends JerseyTest {

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

            DtoApplication app = resource().path("")
                                           .accept(APPLICATION_JSON_V5())
                                           .get(DtoApplication.class);

            // Create a router.
            router.setName(testRouterName);
            router.setTenantId("TEST-TENANT");
            resource = resource().uri(app.getRouters());
            response = resource.type(APPLICATION_ROUTER_JSON_V3()).post(
                    ClientResponse.class, router);

            log.debug("router location: {}", response.getLocation());
            testRouterUri = response.getLocation();
            router = resource().uri(testRouterUri).accept(
                    APPLICATION_ROUTER_JSON_V3()).get(DtoRouter.class);

            // Create a Exterior router port.
            log.debug("routerPortUri: {} ", router.getPorts());
            DtoRouterPort port = new DtoRouterPort();
            port.setNetworkAddress("10.0.0.0");
            port.setNetworkLength(24);
            port.setPortAddress("10.0.0.1");
            port.setVifId(UUID
                    .fromString("372b0040-12ae-11e1-be50-0800200c9a66"));

            response = resource().uri(router.getPorts())
                    .type(APPLICATION_PORT_V3_JSON())
                    .post(ClientResponse.class, port);
            assertEquals(201, response.getStatus());
            log.debug("location: {}", response.getLocation());

            testRouterPortId = FuncTest.getUuidFromLocation(response
                    .getLocation());
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
            int weight = 100;

            // Create a route
            route.setSrcNetworkAddr(srcNetworkAddr);
            route.setSrcNetworkLength(srcNetworkLength);
            route.setType(type);
            route.setDstNetworkAddr(dstNetworkAddr);
            route.setDstNetworkLength(dstNetworkLength);
            route.setNextHopPort(nextHopPort);
            route.setNextHopGateway(null);
            route.setWeight(weight);

            URI routerRouteUri = URI.create(testRouterUri.toString()
                                            + "/routes");
            response = resource().uri(routerRouteUri)
                    .type(APPLICATION_ROUTE_JSON())
                    .post(ClientResponse.class, route);

            URI routeUri = response.getLocation();
            log.debug("status {}", response.getStatus());
            log.debug("location {}", response.getLocation());
            assertEquals(201, response.getStatus());

            // Get the route
            response = resource().uri(routeUri).accept(APPLICATION_ROUTE_JSON())
                    .get(ClientResponse.class);
            route = response.getEntity(DtoRoute.class);
            assertEquals(200, response.getStatus());

            // Check the next hop gateway is null
            assertNull(route.getNextHopGateway());

            // Delete the route
            response = resource().uri(routeUri).type(APPLICATION_ROUTE_JSON())
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
                    .type(APPLICATION_ROUTE_JSON())
                    .post(ClientResponse.class, route);

            URI routeUri = response.getLocation();
            log.debug("status {}", response.getStatus());
            log.debug("location {}", response.getLocation());
            assertEquals(201, response.getStatus());

            // Get the route
            response = resource().uri(routeUri).accept(APPLICATION_ROUTE_JSON())
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
                    .accept(APPLICATION_ROUTE_COLLECTION_JSON())
                    .get(ClientResponse.class);
            log.debug("body: {}", response.getEntity(String.class));
            assertEquals(200, response.getStatus());

            // Delete the route
            response = resource().uri(routeUri).type(APPLICATION_ROUTE_JSON())
                    .delete(ClientResponse.class);
            assertEquals(204, response.getStatus());
        }
    }

    @RunWith(Parameterized.class)
    public static class TestRouteCreateBadRequest extends JerseyTest {

        private final DtoRoute route;
        private DtoWebResource dtoResource;
        private Topology topology;

        public TestRouteCreateBadRequest(DtoRoute route) {
            super(FuncTest.appDesc);
            this.route = route;
        }

        @Before
        public void setUp() {
            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);

            // Create a router
            DtoRouter r = new DtoRouter();
            r.setName("router1-name");
            r.setTenantId("tenant1-id");

            topology = new Topology.Builder(dtoResource)
                    .create("router1", r).build();
        }

        @Parameters
        public static Collection<Object[]> data() {

            List<Object[]> params = new ArrayList<>();

            // Null type
            DtoRoute nullType = new DtoRoute();
            nullType.setType(null);
            nullType.setDstNetworkAddr("10.0.0.1");
            nullType.setDstNetworkLength(24);
            nullType.setSrcNetworkAddr("192.168.1.1");
            nullType.setSrcNetworkLength(24);
            nullType.setWeight(100);
            params.add(new Object[] { nullType });

            // Invalid type
            DtoRoute invalidType = new DtoRoute();
            invalidType.setType("badType");
            invalidType.setDstNetworkAddr("10.0.0.1");
            invalidType.setDstNetworkLength(24);
            invalidType.setSrcNetworkAddr("192.168.1.1");
            invalidType.setSrcNetworkLength(24);
            invalidType.setWeight(100);
            params.add(new Object[] { invalidType });

            // Normal type but no next hop port
            DtoRoute noNextHop = new DtoRoute();
            noNextHop.setType(Route.NextHop.Normal.toString());
            noNextHop.setDstNetworkAddr("10.0.0.1");
            noNextHop.setDstNetworkLength(24);
            noNextHop.setSrcNetworkAddr("192.168.1.1");
            noNextHop.setSrcNetworkLength(24);
            noNextHop.setWeight(100);
            params.add(new Object[] { noNextHop });

            // Normal type but next hop port is not a port on the router.
            DtoRoute badNextHop = new DtoRoute();
            badNextHop.setType(Route.NextHop.Normal.toString());
            badNextHop.setNextHopPort(UUID.randomUUID());
            badNextHop.setDstNetworkAddr("10.0.0.1");
            badNextHop.setDstNetworkLength(24);
            badNextHop.setSrcNetworkAddr("192.168.1.1");
            badNextHop.setSrcNetworkLength(24);
            badNextHop.setWeight(100);
            params.add(new Object[] { badNextHop });

            return params;
        }

        @Test
        public void testBadInputCreate() {

            DtoRouter router1 = topology.getRouter("router1");
            route.setRouterId(router1.getId());

            dtoResource.postAndVerifyBadRequest(
                router1.getRoutes(), APPLICATION_ROUTE_JSON(), route);
        }
    }

    public static class TestPortLinkAddRouteUnlinkSuccess extends JerseyTest {
        private DtoWebResource dtoResource;
        private Topology topology;

        public TestPortLinkAddRouteUnlinkSuccess() {
            super(FuncTest.appDesc);
        }

        @Before
        public void setUp() {
            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);

            // Create a router
            DtoRouter r1 = new DtoRouter();
            r1.setName("router1-name");
            r1.setTenantId("tenant1-id");

            // Create a bridge
            DtoBridge b1 = new DtoBridge();
            b1.setName("bridge1-name");
            b1.setTenantId("tenant1-id");

            // Create a chain
            DtoRuleChain c1 = new DtoRuleChain();
            c1.setId(UUID.randomUUID());
            c1.setName("chain1-name");
            c1.setTenantId("tenant1-id");

            // Create a router port
            DtoRouterPort r1Lp1 = TestPort.createRouterPort(null, null,
                "10.0.0.0", 24, "10.0.0.1");

            // Create a bridge port
            DtoBridgePort b1Lp1 = TestPort.createBridgePort(null, null,
                    c1.getId(), null, null);

            topology = new Topology.Builder(dtoResource)
                .create("chain1", c1)
                .create("router1", r1)
                .create("bridge1", b1)
                .create("router1", "router1Port1", r1Lp1)
                .create("bridge1", "bridge1Port1", b1Lp1)
                .build();
        }

        @Test
        public void testLinkAddRouteUnlink() {
            DtoRouter r1 = topology.getRouter("router1");
            topology.getBridge("bridge1");
            DtoRouterPort r1p1 = topology
                .getRouterPort("router1Port1");
            DtoBridgePort b1p1 = topology
                .getBridgePort("bridge1Port1");

            // Link the router and bridge
            DtoLink link = new DtoLink();
            link.setPeerId(b1p1.getId());
            dtoResource.postAndVerifyStatus(r1p1.getLink(),
                APPLICATION_PORT_LINK_JSON(), link, Response.Status.CREATED
                .getStatusCode());

            DtoRoute route = new DtoRoute();
            route.setRouterId(r1.getId());
            route.setType(DtoRoute.Normal);
            route.setSrcNetworkAddr("0.0.0.0");
            route.setSrcNetworkLength(0);
            route.setDstNetworkAddr("2.2.2.0");
            route.setDstNetworkLength(24);
            route.setWeight(100);
            route.setNextHopPort(r1p1.getId());

            dtoResource.postAndVerifyStatus(r1.getRoutes(),
                APPLICATION_ROUTE_JSON(), route,
                Response.Status.CREATED.getStatusCode());

            // Unlink
            dtoResource.deleteAndVerifyStatus(r1p1.getLink(),
                APPLICATION_PORT_LINK_JSON(),
                Response.Status.NO_CONTENT.getStatusCode());

        }
    }
}

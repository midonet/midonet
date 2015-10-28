/*
 * Copyright 2015 Midokura SARL
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.ws.rs.core.UriBuilder;

import com.fasterxml.jackson.databind.JavaType;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;

import org.apache.zookeeper.KeeperException;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;

import org.midonet.client.dto.DtoApplication;
import org.midonet.client.dto.DtoBridge;
import org.midonet.client.dto.DtoBridgePort;
import org.midonet.client.dto.DtoLoadBalancer;
import org.midonet.client.dto.DtoRouter;
import org.midonet.client.dto.DtoRouterPort;
import org.midonet.client.dto.DtoRuleChain;
import org.midonet.cluster.auth.AuthService;
import org.midonet.cluster.auth.MockAuthService;
import org.midonet.cluster.rest_api.models.Tenant;
import org.midonet.cluster.rest_api.rest_api.DtoWebResource;
import org.midonet.cluster.rest_api.rest_api.RestApiTestBase;
import org.midonet.cluster.rest_api.rest_api.Topology;
import org.midonet.cluster.rest_api.rest_api.TopologyBackdoor;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;

import static javax.ws.rs.core.Response.Status.CREATED;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.midonet.cluster.rest_api.rest_api.FuncTest._injector;
import static org.midonet.cluster.rest_api.rest_api.FuncTest.appDesc;
import static org.midonet.cluster.rest_api.rest_api.FuncTest.objectMapper;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_BRIDGE_JSON_V4;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_PORT_LINK_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_PORT_V3_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_ROUTER_COLLECTION_JSON_V3;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_ROUTER_JSON_V3;

@RunWith(Enclosed.class)
public class TestRouter {

    public static class TestRouterList extends JerseyTest {

        private Topology topology;
        private DtoWebResource dtoResource;

        public TestRouterList() {
            super(appDesc);
        }

        private void addActualRouters(Topology.Builder builder, String tenantId,
                                      int count) {
            for (int i = 0 ; i < count ; i++) {
                // In the new storage stack we don't store tenants in MidoNet
                // and instead fetch them directly from the AuthService, so
                // let's add them there.
                AuthService as = _injector.getInstance(AuthService.class);
                ((MockAuthService)as).addTenant(tenantId, tenantId);
                DtoRouter router = new DtoRouter();
                String tag = Integer.toString(i) + tenantId;
                router.setName(tag);
                router.setTenantId(tenantId);
                builder.create(tag, router);
            }
        }

        private DtoRouter getExpectedRouter(URI routersUri, String tag) {

            DtoRouter r = topology.getRouter(tag);
            String uri = routersUri.toString() + "/" + r.getId();

            // Make sure you set the non-ID fields to values you expect
            r.setName(tag);
            r.setUri(UriBuilder.fromUri(uri).build());
            r.setPorts(UriBuilder.fromUri(uri + "/ports").build());
            r.setPeerPorts(UriBuilder.fromUri(uri + "/peer_ports").build());
            r.setRoutes(UriBuilder.fromUri(uri + "/routes").build());

            return r;
        }

        private List<DtoRouter> getExpectedRouters(URI routersUri,
                                                   String tenantId,
                                                   int startTagNum,
                                                   int endTagNum) {
            List<DtoRouter> routers = new ArrayList<>();

            for (int i = startTagNum; i <= endTagNum; i++) {
                String tag = Integer.toString(i) + tenantId;
                DtoRouter r = getExpectedRouter(routersUri, tag);
                routers.add(r);
            }

            return routers;
        }

        @Before
        public void setUp() {

            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);

            Topology.Builder builder = new Topology.Builder(dtoResource);

            // Create 5 routers for tenant 0
            addActualRouters(builder, "tenant0", 5);

            // Create 5 routers for tenant 1
            addActualRouters(builder, "tenant1", 5);

            topology = builder.build();
        }

        @Test
        public void testListAllRouters() throws Exception {

            // Get the expected list of DtoRouter objects
            DtoApplication app = topology.getApplication();
            List<DtoRouter> expected = getExpectedRouters(app.getRouters(),
                    "tenant0", 0, 4);
            expected.addAll(getExpectedRouters(
                    app.getRouters(),"tenant1", 0, 4));

            // Get the actual DtoRouter objects
            String actualRaw = dtoResource.getAndVerifyOk(app.getRouters(),
                    APPLICATION_ROUTER_COLLECTION_JSON_V3(),
                    String.class);
            JavaType type = objectMapper.getTypeFactory()
                    .constructParametrizedType(List.class, List.class,
                                               DtoRouter.class);
            List<DtoRouter> actual = objectMapper.readValue(
                    actualRaw, type);

            // Compare the actual and expected
            assertThat(actual, hasSize(expected.size()));
            assertThat(actual, containsInAnyOrder(expected.toArray()));
        }

        @Test
        public void testListRoutersPerTenant() throws Exception {

            // Get the expected list of DtoBridge objects
            DtoApplication app = topology.getApplication();
            Tenant tenant = topology.getTenant("tenant0");

            List<DtoRouter> expected = getExpectedRouters(app.getRouters(),
                    tenant.id, 0, 4);

            // Get the actual DtoRouter objects
            String actualRaw = dtoResource.getAndVerifyOk(tenant.getRouters(),
                    APPLICATION_ROUTER_COLLECTION_JSON_V3(),
                    String.class);
            JavaType type = objectMapper.getTypeFactory()
                    .constructParametrizedType(List.class, List.class,
                                               DtoRouter.class);
            List<DtoRouter> actual = objectMapper.readValue(
                    actualRaw, type);

            // Compare the actual and expected
            assertThat(actual, hasSize(expected.size()));
            assertThat(actual, containsInAnyOrder(expected.toArray()));
        }
    }

    public static class TestRouterCrud extends RestApiTestBase {

        private DtoWebResource dtoResource;
        private Topology topology;

        public TestRouterCrud() {
            super(appDesc);
        }

        private Map<String, String> getTenantQueryParams(String tenantId) {
            Map<String, String> queryParams = new HashMap<>();
            queryParams.put("tenant_id", tenantId);
            return queryParams;
        }

        @Before
        public void setUp() throws KeeperException, InterruptedException {
            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);

            // Prepare chains that can be used to set to port
            DtoRuleChain chain1 = new DtoRuleChain();
            chain1.setName("chain1");
            chain1.setTenantId("tenant1-id");

            // Prepare another chain
            DtoRuleChain chain2 = new DtoRuleChain();
            chain2.setName("chain2");
            chain2.setTenantId("tenant1-id");

            // Prepare a loadBalancer
            DtoLoadBalancer loadBalancer1 = new DtoLoadBalancer();
            DtoLoadBalancer loadBalancer2 = new DtoLoadBalancer();

            topology = new Topology.Builder(dtoResource)
                    .create("chain1", chain1)
                    .create("chain2", chain2)
                    .create("loadBalancer1", loadBalancer1)
                    .create("loadBalancer2", loadBalancer2).build();
        }

        private DtoRouter createRouter(
                String name, String tenant, boolean withChains,
                boolean withLoadBalancer) {
            DtoApplication app = topology.getApplication();
            DtoRuleChain chain1 = topology.getChain("chain1");
            DtoRuleChain chain2 = topology.getChain("chain2");
            DtoLoadBalancer lb1 = topology.getLoadBalancer("loadBalancer1");

            DtoRouter router = new DtoRouter();
            router.setName(name);
            router.setTenantId(tenant);
            if (withChains) {
                router.setInboundFilterId(chain1.getId());
                router.setOutboundFilterId(chain2.getId());
            }

            if(withLoadBalancer) {
                router.setLoadBalancerId(lb1.getId());
            }

            DtoRouter resRouter = dtoResource.postAndVerifyCreated(
                app.getRouters(),
                APPLICATION_ROUTER_JSON_V3(), router,
                DtoRouter.class);
            assertNotNull(resRouter.getId());
            assertNotNull(resRouter.getUri());
            // TODO: Implement 'equals' for DtoRouter
            assertEquals(router.getTenantId(), resRouter.getTenantId());
            if (withChains) {
                assertEquals(router.getInboundFilterId(),
                    resRouter.getInboundFilterId());
                assertEquals(router.getOutboundFilterId(),
                    resRouter.getOutboundFilterId());
            }
            return resRouter;
        }

        @Test
        public void testDuplicateName() throws Exception {

            DtoApplication app = topology.getApplication();
            URI routersUrl = app.getRouters();

            DtoRouter router = new DtoRouter();
            router.setName("name");
            router.setTenantId("tenant1");

            DtoRouter r1 = dtoResource.postAndVerifyCreated(routersUrl,
                    APPLICATION_ROUTER_JSON_V3(), router, DtoRouter.class);

            // Duplicate name should be allowed
            router = new DtoRouter();
            router.setName("name");
            router.setTenantId("tenant1");
            DtoRouter r2 = dtoResource.postAndVerifyCreated(routersUrl,
                    APPLICATION_ROUTER_JSON_V3(), router, DtoRouter.class);

            dtoResource.deleteAndVerifyNoContent(r1.getUri(),
                    APPLICATION_ROUTER_JSON_V3());
            dtoResource.deleteAndVerifyNoContent(r2.getUri(),
                    APPLICATION_ROUTER_JSON_V3());
        }

        @Test
        public void testRouterCreateWithNoTenant() throws Exception {
            DtoApplication app = topology.getApplication();
            DtoRouter r = dtoResource.postAndVerifyCreated(app.getRouters(),
                APPLICATION_ROUTER_JSON_V3(), new DtoRouter(), DtoRouter.class);

            assertNull(r.getTenantId());
        }

        @Test
        public void testRouterUpdateWithNoTenant() throws Exception {
            DtoRouter router = createRouter("foo", "tenant1", false, false);

            assertEquals("tenant1", router.getTenantId());
            router.setTenantId(null);
            router = dtoResource.putAndVerifyNoContent(router.getUri(),
                APPLICATION_ROUTER_JSON_V3(), router, DtoRouter.class);

            assertNull(router.getTenantId());
        }

        @Test
        public void testRouterCreateWithNoName() throws Exception {
           DtoRouter router = createRouter(null, "tenant1", false, false);
            assertNull(router.getName());
        }

        @Test
        public void testRouterUpdateWithNoName() throws Exception {
            DtoRouter router = createRouter("foo", "tenant1", false, false);
            assertEquals("foo", router.getName());

            router.setName(null);
            router = dtoResource.putAndVerifyNoContent(
                router.getUri(), APPLICATION_ROUTER_JSON_V3(), router,
                DtoRouter.class);
            assertNull(router.getName());
        }

        @Test
        public void testEmptryStringName() throws Exception {

            DtoApplication app = topology.getApplication();
            URI routersUrl = app.getRouters();

            // Empty name is also allowed
            DtoRouter router = new DtoRouter();
            router.setName("");
            router.setTenantId("tenant1");
            router = dtoResource.postAndVerifyCreated(routersUrl,
                    APPLICATION_ROUTER_JSON_V3(), router, DtoRouter.class);

            dtoResource.deleteAndVerifyNoContent(router.getUri(),
                    APPLICATION_ROUTER_JSON_V3());
        }

        @Test
        public void testCrud() throws Exception {
            DtoApplication app = topology.getApplication();
            DtoRuleChain chain1 = topology.getChain("chain1");
            DtoRuleChain chain2 = topology.getChain("chain2");
            DtoLoadBalancer lb1 = topology.getLoadBalancer("loadBalancer1");
            DtoLoadBalancer lb2 = topology.getLoadBalancer("loadBalancer2");

            assertNotNull(app.getRouters());
            DtoRouter[] routers = dtoResource.getAndVerifyOk(app.getRouters(),
                    APPLICATION_ROUTER_COLLECTION_JSON_V3(), DtoRouter[].class);
            assertEquals(0, routers.length);

            // Add a router with a loadbalancer
            DtoRouter resRouter = createRouter("router1", "tenant1-id",
                                               true, true);

            // List the routers
            routers = dtoResource.getAndVerifyOk(app.getRouters(),
                    APPLICATION_ROUTER_COLLECTION_JSON_V3(), DtoRouter[].class);
            assertEquals(1, routers.length);
            assertEquals(resRouter.getId(), routers[0].getId());
            assertEquals(resRouter.getLoadBalancerId(),
                         routers[0].getLoadBalancerId());

            // Update the router: change name, admin state, and swap filters.
            resRouter.setName("router1-modified");
            resRouter.setAdminStateUp(false);
            resRouter.setInboundFilterId(chain2.getId());
            resRouter.setOutboundFilterId(chain1.getId());
            resRouter.setLoadBalancerId(lb2.getId());
            DtoRouter updatedRouter = dtoResource.putAndVerifyNoContent(
                    resRouter.getUri(), APPLICATION_ROUTER_JSON_V3(), resRouter,
                    DtoRouter.class);
            assertNotNull(updatedRouter.getId());
            assertEquals(resRouter.getTenantId(), updatedRouter.getTenantId());
            assertEquals(resRouter.getInboundFilterId(),
                         updatedRouter.getInboundFilterId());
            assertEquals(resRouter.getOutboundFilterId(),
                         updatedRouter.getOutboundFilterId());
            assertEquals(resRouter.getLoadBalancerId(),
                         updatedRouter.getLoadBalancerId());
            assertEquals(resRouter.getName(), updatedRouter.getName());
            assertEquals(resRouter.isAdminStateUp(),
                         updatedRouter.isAdminStateUp());

            // Delete the router
            dtoResource.deleteAndVerifyNoContent(resRouter.getUri(),
                    APPLICATION_ROUTER_JSON_V3());

            // Verify that it's gone
            dtoResource.getAndVerifyNotFound(resRouter.getUri(),
                    APPLICATION_ROUTER_JSON_V3());

            // List should return an empty array
            routers = dtoResource.getAndVerifyOk(app.getRouters(),
                    getTenantQueryParams("tenant1-id"),
                    APPLICATION_ROUTER_COLLECTION_JSON_V3(), DtoRouter[].class);
            assertEquals(0, routers.length);
        }

        @Test
        @Ignore("TODO FIXME - pending implementation in v2")
        public void testRouterDeleteWithArpEntries() throws Exception {
            // Add a router
            DtoRouter resRouter = createRouter("router1", "tenant1-id", false,
                                               false);
            // Add an ARP entry in this router's ARP cache.
            _injector
                .getInstance(TopologyBackdoor.class)
                .addArpTableEntryToRouter(resRouter.getId(),
                                          IPv4Addr.fromString("10.0.0.3"),
                                          MAC.fromString("02:00:dd:ee:ee:55"));

            dtoResource.deleteAndVerifyNoContent(
                resRouter.getUri(), APPLICATION_ROUTER_JSON_V3());
        }

        @Test
        public void testRouterDeleteWithLinkedInteriorPort() throws Exception {
            // Add a router
            DtoRouter resRouter = createRouter("router1", "tenant1-id",
                    false, false);
            // Add an interior router port.
            DtoRouterPort port = new DtoRouterPort();
            port.setNetworkAddress("10.0.0.0");
            port.setNetworkLength(24);
            port.setPortAddress("10.0.0.1");
            DtoRouterPort resPort =
                dtoResource.postAndVerifyCreated(resRouter.getPorts(),
                    APPLICATION_PORT_V3_JSON(), port, DtoRouterPort.class);
            assertNotNull(resPort.getId());
            // Create a bridge that we can link to the router.
            DtoBridge bridge = new DtoBridge();
            bridge.setName("bridge1");
            bridge.setTenantId("tenant1");
            DtoBridge resBridge = dtoResource.postAndVerifyCreated(
                topology.getApplication().getBridges(),
                APPLICATION_BRIDGE_JSON_V4(), bridge, DtoBridge.class);
            assertNotNull(resBridge.getId());
            assertNotNull(resBridge.getUri());
            // Add an interior bridge port.
            DtoBridgePort bPort = dtoResource.postAndVerifyCreated(
                resBridge.getPorts(), APPLICATION_PORT_V3_JSON(),
                new DtoBridgePort(), DtoBridgePort.class);
            assertNotNull(bPort.getId());
            assertNotNull(bPort.getUri());
            // Link the bridge and router ports.
            bPort.setPeerId(resPort.getId());
            dtoResource.postAndVerifyStatus(bPort.getLink(),
                APPLICATION_PORT_LINK_JSON(), bPort, CREATED.getStatusCode());
            dtoResource.deleteAndVerifyNoContent(
                resRouter.getUri(), APPLICATION_ROUTER_JSON_V3());
        }
    }
}

/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midonet.api.network;

import com.midokura.midonet.api.rest_api.DtoWebResource;
import com.midokura.midonet.api.rest_api.FuncTest;
import com.midokura.midonet.api.rest_api.Topology;
import com.midokura.midonet.api.zookeeper.StaticMockDirectory;
import com.midokura.midonet.client.dto.DtoApplication;
import com.midokura.midonet.client.dto.DtoError;
import com.midokura.midonet.client.dto.DtoRouter;
import com.midokura.midonet.client.dto.DtoRuleChain;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.net.URI;
import java.util.*;

import static com.midokura.midonet.api.VendorMediaType.APPLICATION_ROUTER_COLLECTION_JSON;
import static com.midokura.midonet.api.VendorMediaType.APPLICATION_ROUTER_JSON;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

@RunWith(Enclosed.class)
public class TestRouter {

    public static class TestRouterCrud extends JerseyTest {

        private DtoWebResource dtoResource;
        private Topology topology;

        public TestRouterCrud() {
            super(FuncTest.appDesc);
        }

        private Map<String, String> getTenantQueryParams(String tenantId) {
            Map<String, String> queryParams = new HashMap<String, String>();
            queryParams.put("tenant_id", tenantId);
            return queryParams;
        }

        @Before
        public void setUp() {

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

            topology = new Topology.Builder(dtoResource)
                    .create("chain1", chain1)
                    .create("chain2", chain2).build();
        }

        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        @Test
        public void testCrud() throws Exception {

            DtoApplication app = topology.getApplication();
            DtoRuleChain chain1 = topology.getChain("chain1");
            DtoRuleChain chain2 = topology.getChain("chain2");

            URI routersUri = app.getRouters();
            assertNotNull(routersUri);
            DtoRouter[] routers = dtoResource.getAndVerifyOk(routersUri,
                    getTenantQueryParams("tenant1-id"),
                    APPLICATION_ROUTER_COLLECTION_JSON, DtoRouter[].class);
            assertEquals(0, routers.length);

            // Add a router
            DtoRouter router = new DtoRouter();
            router.setName("router1");
            router.setTenantId("tenant1-id");
            router.setInboundFilterId(chain1.getId());
            router.setOutboundFilterId(chain2.getId());

            DtoRouter resRouter = dtoResource.postAndVerifyCreated(routersUri,
                    APPLICATION_ROUTER_JSON, router, DtoRouter.class);
            assertNotNull(resRouter.getId());
            assertNotNull(resRouter.getUri());
            // TODO: Implement 'equals' for DtoRouter
            assertEquals(router.getTenantId(), resRouter.getTenantId());
            assertEquals(router.getInboundFilterId(),
                    resRouter.getInboundFilterId());
            assertEquals(router.getOutboundFilterId(),
                    resRouter.getOutboundFilterId());
            URI routerUri = resRouter.getUri();

            // List the router
            routers = dtoResource.getAndVerifyOk(routersUri,
                    getTenantQueryParams("tenant1-id"),
                    APPLICATION_ROUTER_COLLECTION_JSON, DtoRouter[].class);
            assertEquals(1, routers.length);
            assertEquals(resRouter.getId(), routers[0].getId());

            // Update the router
            resRouter.setName("router1-modified");
            resRouter.setInboundFilterId(chain2.getId());
            resRouter.setOutboundFilterId(chain1.getId());
            DtoRouter updatedRouter = dtoResource.putAndVerifyNoContent(
                    routerUri, APPLICATION_ROUTER_JSON, resRouter,
                    DtoRouter.class);
            assertNotNull(updatedRouter.getId());
            assertEquals(resRouter.getTenantId(), updatedRouter.getTenantId());
            assertEquals(resRouter.getInboundFilterId(),
                    updatedRouter.getInboundFilterId());
            assertEquals(resRouter.getOutboundFilterId(),
                    updatedRouter.getOutboundFilterId());
            assertEquals(resRouter.getName(), updatedRouter.getName());

            // Delete the router
            dtoResource.deleteAndVerifyNoContent(routerUri,
                    APPLICATION_ROUTER_JSON);

            // Verify that it's gone
            dtoResource
                    .getAndVerifyNotFound(routerUri, APPLICATION_ROUTER_JSON);

            // List should return an empty array
            routers = dtoResource.getAndVerifyOk(routersUri,
                    getTenantQueryParams("tenant1-id"),
                    APPLICATION_ROUTER_COLLECTION_JSON, DtoRouter[].class);
            assertEquals(0, routers.length);
        }
    }

    @RunWith(Parameterized.class)
    public static class TestCreateRouterBadRequest extends JerseyTest {

        private Topology topology;
        private DtoWebResource dtoResource;
        private final DtoRouter router;
        private final String property;

        public TestCreateRouterBadRequest(DtoRouter router, String property) {
            super(FuncTest.appDesc);
            this.router = router;
            this.property = property;
        }

        @Before
        public void setUp() {

            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);

            // Create a router - useful for checking duplicate name error
            DtoRouter r = new DtoRouter();
            r.setName("router1-name");
            r.setTenantId("tenant1-id");

            topology = new Topology.Builder(dtoResource)
                    .create("router1", r).build();
        }

        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        @Parameters
        public static Collection<Object[]> data() {

            List<Object[]> params = new ArrayList<Object[]>();

            // Null name
            DtoRouter nullNameRouter = new DtoRouter();
            nullNameRouter.setTenantId("tenant1-id");
            params.add(new Object[] { nullNameRouter, "name" });

            // Blank name
            DtoRouter blankNameRouter = new DtoRouter();
            blankNameRouter.setName("");
            blankNameRouter.setTenantId("tenant1-id");
            params.add(new Object[] { blankNameRouter, "name" });

            // Long name
            StringBuilder longName = new StringBuilder(
                    Router.MAX_ROUTER_NAME_LEN + 1);
            for (int i = 0; i < Router.MAX_ROUTER_NAME_LEN + 1; i++) {
                longName.append("a");
            }
            DtoRouter longNameRouter = new DtoRouter();
            longNameRouter.setName(longName.toString());
            longNameRouter.setTenantId("tenant1-id");
            params.add(new Object[] { longNameRouter, "name" });

            // Router name already exists
            DtoRouter dupNameRouter = new DtoRouter();
            dupNameRouter.setName("router1-name");
            dupNameRouter.setTenantId("tenant1-id");
            params.add(new Object[]{dupNameRouter, "name"});

            // Router with tenantID missing
            DtoRouter noTenant = new DtoRouter();
            noTenant.setName("noTenant-router-name");
            params.add(new Object[] { noTenant, "tenantId" });

            return params;
        }

        @Test
        public void testBadInputCreate() {
            DtoApplication app = topology.getApplication();
            DtoError error = dtoResource.postAndVerifyBadRequest(
                    app.getRouters(), APPLICATION_ROUTER_JSON, router);
            List<Map<String, String>> violations = error.getViolations();
            assertEquals(1, violations.size());
            assertEquals(property, violations.get(0).get("property"));
        }
    }

    @RunWith(Parameterized.class)
    public static class TestUpdateRouterBadRequest extends JerseyTest {

        private final DtoRouter testRouter;
        private final String property;
        private DtoWebResource dtoResource;
        private Topology topology;

        public TestUpdateRouterBadRequest(DtoRouter testRouter, String property) {
            super(FuncTest.appDesc);
            this.testRouter = testRouter;
            this.property = property;
        }

        @Before
        public void setUp() {

            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);

            // Create a router
            DtoRouter r1 = new DtoRouter();
            r1.setName("router1-name");
            r1.setTenantId("tenant1-id");

            // Create another router - useful for checking duplicate name error
            DtoRouter r2 = new DtoRouter();
            r2.setName("router2-name");
            r2.setTenantId("tenant1-id");

            topology = new Topology.Builder(dtoResource)
                    .create("router1", r1)
                    .create("router2", r2).build();
        }

        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        @Parameters
        public static Collection<Object[]> data() {

            List<Object[]> params = new ArrayList<Object[]>();

            // Null name
            DtoRouter nullNameRouter = new DtoRouter();
            nullNameRouter.setTenantId("tenant1-id");
            params.add(new Object[] { nullNameRouter, "name" });

            // Blank name
            DtoRouter blankNameRouter = new DtoRouter();
            blankNameRouter.setName("");
            blankNameRouter.setTenantId("tenant1-id");
            params.add(new Object[] { blankNameRouter, "name" });

            // Long name
            StringBuilder longName = new StringBuilder(
                    Router.MAX_ROUTER_NAME_LEN + 1);
            for (int i = 0; i < Router.MAX_ROUTER_NAME_LEN + 1; i++) {
                longName.append("a");
            }
            DtoRouter longNameRouter = new DtoRouter();
            longNameRouter.setName(longName.toString());
            longNameRouter.setTenantId("tenant1-id");
            params.add(new Object[] { longNameRouter, "name" });

            // Router name already exists
            DtoRouter dupNameRouter = new DtoRouter();
            dupNameRouter.setName("router2-name");
            dupNameRouter.setTenantId("tenant1-id");
            params.add(new Object[] { dupNameRouter, "name" });

            // Router with tenantID missing
            DtoRouter noTenant = new DtoRouter();
            noTenant.setName("noTenant-router-name");
            params.add(new Object[] { noTenant, "tenantId" });

            return params;
        }

        @Test
        public void testBadInput() {
            // Get the router
            DtoRouter router = topology.getRouter("router1");

            DtoError error = dtoResource.putAndVerifyBadRequest(
                    router.getUri(), APPLICATION_ROUTER_JSON, testRouter);
            List<Map<String, String>> violations = error.getViolations();
            assertEquals(1, violations.size());
            assertEquals(property, violations.get(0).get("property"));
        }
    }
}

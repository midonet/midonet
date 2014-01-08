/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.api.l4lb;

import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.midonet.api.VendorMediaType;
import org.midonet.api.rest_api.DtoWebResource;
import org.midonet.api.rest_api.FuncTest;
import org.midonet.api.rest_api.Topology;
import org.midonet.api.zookeeper.StaticMockDirectory;
import org.midonet.client.dto.DtoApplication;
import org.midonet.client.dto.DtoLoadBalancer;
import org.midonet.client.dto.DtoRouter;

import java.net.URI;
import java.util.Random;
import java.util.UUID;

import static javax.ws.rs.core.Response.Status.*;
import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;

@RunWith(Enclosed.class)
public class TestLoadBalancer {

    public static class TestLoadBalancerCrud extends JerseyTest {

        private DtoWebResource dtoWebResource;
        private Topology topology;
        private URI topLevelLoadBalancersUri;
        private URI topLevelRoutersUri;

        public TestLoadBalancerCrud() {
            super(FuncTest.appDesc);
        }

        @Before
        public void setUp() {
            WebResource resource = resource();
            dtoWebResource = new DtoWebResource(resource);
            topology = new Topology.Builder(dtoWebResource).build();
            DtoApplication app = topology.getApplication();

            // URIs to use for operations
            topLevelLoadBalancersUri = app.getLoadBalancers();
            assertNotNull(topLevelLoadBalancersUri);
            topLevelRoutersUri = app.getRouters();
            assertNotNull(topLevelLoadBalancersUri);
        }

        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        private void verifyNumberOfLoadBalancers(int num) {
            DtoLoadBalancer[] loadBalancers = dtoWebResource.getAndVerifyOk(
                    topLevelLoadBalancersUri,
                    VendorMediaType.APPLICATION_LOAD_BALANCER_JSON,
                    DtoLoadBalancer[].class);
            assertEquals(num, loadBalancers.length);
        }

        private DtoLoadBalancer getStockLoadBalancer() {
            DtoLoadBalancer loadBalancer = new DtoLoadBalancer();
            // NOTE(tfukushima): Populating UUID of the load balancer because
            //   the API can create the resource with the specified UUID,
            //   which is very useful for the identical checks.
            loadBalancer.setId(UUID.randomUUID());
            loadBalancer.setAdminStateUp(true);
            return loadBalancer;
        }

        private DtoRouter getStockRouter() {
            DtoRouter router = new DtoRouter();
            router.setId(UUID.randomUUID());
            router.setName("lb_test_router" + new Random().nextInt());
            router.setTenantId("dummy_tenant");

            return router;
        }

        @Test
        synchronized public void testCrud() throws Exception {
            int counter = 0;
            // LoadBalancers should be empty
            verifyNumberOfLoadBalancers(counter);

            // POST
            DtoLoadBalancer loadBalancer = getStockLoadBalancer();
            ClientResponse response1 = dtoWebResource.postAndVerifyStatus(
                    topLevelLoadBalancersUri,
                    VendorMediaType.APPLICATION_LOAD_BALANCER_JSON,
                    loadBalancer,
                    CREATED.getStatusCode());
            URI loadBalancerUri = response1.getLocation();
            counter++;
            verifyNumberOfLoadBalancers(counter);

            // POST another one
            DtoLoadBalancer loadBalancer2 = getStockLoadBalancer();
            ClientResponse response2 = dtoWebResource.postAndVerifyStatus(
                    topLevelLoadBalancersUri,
                    VendorMediaType.APPLICATION_LOAD_BALANCER_JSON,
                    loadBalancer2,
                    CREATED.getStatusCode());
            URI loadBalancerUri2 = response2.getLocation();
            counter++;
            verifyNumberOfLoadBalancers(counter);

            // POST with the same ID as the existing resource and get 409
            // CONFLICT.
            dtoWebResource.postAndVerifyStatus(
                    topLevelLoadBalancersUri,
                    VendorMediaType.APPLICATION_LOAD_BALANCER_JSON,
                    loadBalancer2,
                    CONFLICT.getStatusCode());
            verifyNumberOfLoadBalancers(counter);

            // GET and check if it is the same as what we POSTed.
            DtoLoadBalancer newLoadBalancer = dtoWebResource.getAndVerifyOk(
                    loadBalancerUri,
                    VendorMediaType.APPLICATION_LOAD_BALANCER_JSON,
                    DtoLoadBalancer.class);
            // It checks the id as well but they're identical because POST
            // accepts popoulated id and create the load balancer with the id.
            assertEquals(loadBalancer, newLoadBalancer);
            DtoLoadBalancer newLoadBalancer2 = dtoWebResource.getAndVerifyOk(
                    loadBalancerUri2,
                    VendorMediaType.APPLICATION_LOAD_BALANCER_JSON,
                    DtoLoadBalancer.class);
            // It checks the id as well but they're identical because POST
            // accepts popoulated id and create the load balancer with the id.
            assertEquals(newLoadBalancer2, loadBalancer2);

            // PUT with the different parameters
            newLoadBalancer2.setAdminStateUp(
                    !newLoadBalancer2.isAdminStateUp());
            assertEquals(newLoadBalancer2.isAdminStateUp(),
                    !!newLoadBalancer2.isAdminStateUp());
            DtoLoadBalancer updatedLoadBalancer2 =
                    dtoWebResource.putAndVerifyNoContent(
                            loadBalancerUri2,
                            VendorMediaType.APPLICATION_LOAD_BALANCER_JSON,
                            newLoadBalancer2, DtoLoadBalancer.class);
            assertEquals(updatedLoadBalancer2, newLoadBalancer2);

            // Check if the loadbalancer can be assigned to the router
            DtoLoadBalancer assignedLoadBalancer = getStockLoadBalancer();
            DtoRouter router = dtoWebResource.postAndVerifyCreated(
                    topLevelRoutersUri,
                    VendorMediaType.APPLICATION_ROUTER_JSON,
                    getStockRouter(),
                    DtoRouter.class);
            assignedLoadBalancer = dtoWebResource.postAndVerifyCreated(
                    topLevelLoadBalancersUri,
                    VendorMediaType.APPLICATION_LOAD_BALANCER_JSON,
                    assignedLoadBalancer, DtoLoadBalancer.class);
            router.setLoadBalancerId(assignedLoadBalancer.getId());
            // We need to use v2 for the load balancer assignments.
            router = dtoWebResource.putAndVerifyNoContent(router.getUri(),
                    VendorMediaType.APPLICATION_ROUTER_JSON_V2,
                    router,
                    DtoRouter.class);
            assignedLoadBalancer = dtoWebResource.getAndVerifyOk(
                    assignedLoadBalancer.getUri(),
                    VendorMediaType.APPLICATION_LOAD_BALANCER_JSON,
                    DtoLoadBalancer.class);
            assertEquals(assignedLoadBalancer.getRouterId(), router.getId());
            assertEquals(router.getId(), assignedLoadBalancer.getRouterId());
            counter++;
            verifyNumberOfLoadBalancers(counter);

            // The load balancers can't assign themselves to the different
            // router
            DtoRouter anotherRouter = dtoWebResource.postAndVerifyCreated(
                    topLevelRoutersUri,
                    VendorMediaType.APPLICATION_ROUTER_JSON,
                    getStockRouter(),
                    DtoRouter.class);
            assignedLoadBalancer.setRouterId(anotherRouter.getId());
            dtoWebResource.putAndVerifyBadRequest(assignedLoadBalancer.getUri(),
                    VendorMediaType.APPLICATION_LOAD_BALANCER_JSON,
                    assignedLoadBalancer);

            // DELETE all created load balancers.
            // FIXME(tfukushima): Replace the following `deleteAndVerifyStatus`
            //   with `deleteAndVerifyNoContent` when the bug is fixed.
            dtoWebResource.deleteAndVerifyStatus(loadBalancerUri,
                    VendorMediaType.APPLICATION_LOAD_BALANCER_JSON,
                    NO_CONTENT.getStatusCode());
            counter--;
            verifyNumberOfLoadBalancers(counter);
            dtoWebResource.deleteAndVerifyStatus(loadBalancerUri2,
                    VendorMediaType.APPLICATION_LOAD_BALANCER_JSON,
                    NO_CONTENT.getStatusCode());
            counter--;
            verifyNumberOfLoadBalancers(counter);
            dtoWebResource.deleteAndVerifyStatus(assignedLoadBalancer.getUri(),
                    VendorMediaType.APPLICATION_LOAD_BALANCER_JSON,
                    NO_CONTENT.getStatusCode());
            counter--;
            verifyNumberOfLoadBalancers(counter);

            anotherRouter = dtoWebResource.getAndVerifyOk(
                    anotherRouter.getUri(),
                    VendorMediaType.APPLICATION_ROUTER_JSON_V2,
                    DtoRouter.class);
            assertNull(anotherRouter.getLoadBalancer());
        }
    }
}

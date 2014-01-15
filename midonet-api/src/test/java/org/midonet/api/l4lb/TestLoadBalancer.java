/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.api.l4lb;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.midonet.api.VendorMediaType;
import org.midonet.api.zookeeper.StaticMockDirectory;
import org.midonet.client.dto.DtoLoadBalancer;
import org.midonet.client.dto.DtoRouter;

import static javax.ws.rs.core.Response.Status.*;
import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNull;

@RunWith(Enclosed.class)
public class TestLoadBalancer {

    public static class TestLoadBalancerCrud extends L4LBTestBase {

        @Before
        public void setUp() {
            super.setUp();
        }

        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        private void verifyNumberOfLoadBalancers(int num) {
            DtoLoadBalancer[] loadBalancers = dtoWebResource.getAndVerifyOk(
                    topLevelLoadBalancersUri,
                    VendorMediaType.APPLICATION_LOAD_BALANCER_COLLECTION_JSON,
                    DtoLoadBalancer[].class);
            assertEquals(num, loadBalancers.length);
        }

        @Test
        synchronized public void testCrud() throws Exception {
            int counter = 0;
            // LoadBalancers should be empty
            verifyNumberOfLoadBalancers(counter);

            // POST
            DtoLoadBalancer loadBalancer = createStockLoadBalancer();
            verifyNumberOfLoadBalancers(++counter);

            // POST another one
            DtoLoadBalancer loadBalancer2 = createStockLoadBalancer();
            verifyNumberOfLoadBalancers(++counter);

            // POST with the same ID as the existing resource and get 409
            // CONFLICT.
            dtoWebResource.postAndVerifyStatus(
                    topLevelLoadBalancersUri,
                    VendorMediaType.APPLICATION_LOAD_BALANCER_JSON,
                    loadBalancer2,
                    CONFLICT.getStatusCode());
            verifyNumberOfLoadBalancers(counter);

            // GET and check if it is the same as what we POSTed.
            DtoLoadBalancer newLoadBalancer = getLoadBalancer(loadBalancer.getUri());
            // It checks the id as well but they're identical because POST
            // accepts popoulated id and create the load balancer with the id.
            assertEquals(loadBalancer, newLoadBalancer);

            DtoLoadBalancer newLoadBalancer2 = getLoadBalancer(loadBalancer2.getUri());
            // It checks the id as well but they're identical because POST
            // accepts popoulated id and create the load balancer with the id.
            assertEquals(newLoadBalancer2, loadBalancer2);

            // PUT with the different parameters
            newLoadBalancer2.setAdminStateUp(
                    !newLoadBalancer2.isAdminStateUp());
            DtoLoadBalancer updatedLoadBalancer2 = updateLoadBalancer(newLoadBalancer2);
            assertEquals(updatedLoadBalancer2, newLoadBalancer2);

            // Check if the loadbalancer can be assigned to the router
            DtoRouter router = createStockRouter();
            DtoLoadBalancer assignedLoadBalancer = createStockLoadBalancer();
            router.setLoadBalancerId(assignedLoadBalancer.getId());
            // We need to use v2 for the load balancer assignments.
            router = updateRouterV2(router);
            assignedLoadBalancer = getLoadBalancer(assignedLoadBalancer.getUri());
            assertEquals(router.getId(), assignedLoadBalancer.getRouterId());
            assertEquals(router.getUri(), assignedLoadBalancer.getRouter());
            verifyNumberOfLoadBalancers(++counter);

            // The load balancers can't assign themselves to the different
            // router
            DtoRouter anotherRouter = createStockRouter();
            assignedLoadBalancer.setRouterId(anotherRouter.getId());
            dtoWebResource.putAndVerifyBadRequest(assignedLoadBalancer.getUri(),
                    VendorMediaType.APPLICATION_LOAD_BALANCER_JSON,
                    assignedLoadBalancer);

            // DELETE all created load balancers.
            deleteLoadBalancer(loadBalancer.getUri());
            verifyNumberOfLoadBalancers(--counter);
            deleteLoadBalancer(loadBalancer2.getUri());
            verifyNumberOfLoadBalancers(--counter);
            deleteLoadBalancer(assignedLoadBalancer.getUri());
            verifyNumberOfLoadBalancers(--counter);

            anotherRouter = getRouterV2(anotherRouter.getUri());
            assertNull(anotherRouter.getLoadBalancer());
        }
    }
}

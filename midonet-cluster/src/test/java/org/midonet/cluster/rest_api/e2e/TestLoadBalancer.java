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
package org.midonet.cluster.rest_api.e2e;

import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;

import org.midonet.client.dto.DtoLoadBalancer;
import org.midonet.client.dto.DtoPool;
import org.midonet.client.dto.DtoRouter;
import org.midonet.client.dto.DtoVip;
import org.midonet.cluster.rest_api.Status;

import static javax.ws.rs.core.Response.Status.CONFLICT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_LOAD_BALANCER_COLLECTION_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_LOAD_BALANCER_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_POOL_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_VIP_JSON;

@RunWith(Enclosed.class)
public class TestLoadBalancer {

    public static class TestLoadBalancerCrud extends L4LBTestBase {

        private void verifyNumberOfLoadBalancers(int num) {
            DtoLoadBalancer[] loadBalancers = dtoResource.getAndVerifyOk(
                    topLevelLoadBalancersUri,
                    APPLICATION_LOAD_BALANCER_COLLECTION_JSON(),
                    DtoLoadBalancer[].class);
            assertEquals(num, loadBalancers.length);
        }

        @Test
        synchronized public void testCrud() throws Exception {
            int counter = 0;
            int poolCounter = 0;
            int vipCounter = 0;
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
            dtoResource.postAndVerifyStatus(
                    topLevelLoadBalancersUri,
                    APPLICATION_LOAD_BALANCER_JSON(),
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

            // Check if the load balancer object gets updated if the router
            // changes the load balancer it points to.
            DtoLoadBalancer otherLB = createStockLoadBalancer();
            router.setLoadBalancerId(otherLB.getId());
            router = updateRouterV2(router);
            otherLB = getLoadBalancer(otherLB.getUri());
            assignedLoadBalancer = getLoadBalancer(
                    assignedLoadBalancer.getUri());
            assertEquals(assignedLoadBalancer.getRouterId(), null);
            assertEquals(otherLB.getRouterId(), router.getId());
            verifyNumberOfLoadBalancers(++counter);

            // Clear the load balancer from the router and check that the
            // load balancer is updated
            router.setLoadBalancerId(null);
            updateRouterV2(router);
            otherLB = getLoadBalancer(otherLB.getUri());
            assertEquals(otherLB.getRouterId(), null);

            // The load balancers can't assign themselves to the different
            // router
            DtoRouter anotherRouter = createStockRouter();
            assignedLoadBalancer.setRouterId(anotherRouter.getId());
            dtoResource.putAndVerifyBadRequest(assignedLoadBalancer.getUri(),
                    APPLICATION_LOAD_BALANCER_JSON(),
                    assignedLoadBalancer);

            // POST a pool though the load balancer's `/pools` URI.
            DtoPool[] pools = getPools(loadBalancer.getPools());
            assertEquals(poolCounter, pools.length);
            DtoPool pool = getStockPool(loadBalancer.getId());
            pool = dtoResource.postAndVerifyCreated(loadBalancer.getPools(),
                    APPLICATION_POOL_JSON(), pool, DtoPool.class);
            pools = getPools(loadBalancer.getPools());
            poolCounter++;
            assertEquals(poolCounter, pools.length);

            // POST a VIP though the load balancer's `/vips` URI.
            DtoVip[] vips = getVips(loadBalancer.getVips());
            assertEquals(vipCounter, vips.length);
            // We can't add the VIP through the load balancer.
            DtoVip vip = getStockVip(pool.getId());
            dtoResource.postAndVerifyStatus(loadBalancer.getVips(),
                    APPLICATION_VIP_JSON(), vip,
                    Status.METHOD_NOT_ALLOWED.getStatusCode());
            vips = getVips(loadBalancer.getVips());
            assertEquals(vipCounter, vips.length);

            // Add a VIP, which would be deleted by the cascading.
            vip = postVip(getStockVip(pool.getId()));
            vipCounter++;
            vips = getVips(loadBalancer.getVips());
            assertEquals(vipCounter, vips.length);
            vip = getVip(vip.getUri());
            assertEquals(vip.getLoadBalancerId(), loadBalancer.getId());

            /* This ensures that the updatePool call below succeeds. */
            activatePoolHMMappingStatus(pool.getId());

            // Update the pool associating it with another load balancer and
            // see if it is moved appropriately.
            pool.setLoadBalancerId(loadBalancer2.getId());
            updatePool(pool);

            DtoPool[] newPools = getPools(loadBalancer2.getPools());
            // The number of the pools doesn't change.
            assertEquals(poolCounter, newPools.length);
            vip = getVip(vip.getUri());
            assertEquals(vip.getLoadBalancerId(), loadBalancer2.getId());
            pools = getPools(loadBalancer.getPools());
            assertEquals(0, pools.length);
            vips = getVips(loadBalancer2.getVips());
            // The number of the VIPs doesn't change.
            assertEquals(vipCounter, vips.length);
            DtoVip[] oldVips = getVips(loadBalancer.getVips());
            assertEquals(0, oldVips.length);

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

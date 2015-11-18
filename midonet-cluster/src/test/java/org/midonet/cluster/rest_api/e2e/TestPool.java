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

import java.net.URI;
import java.util.UUID;

import javax.ws.rs.core.MultivaluedMap;

import com.sun.jersey.api.client.ClientResponse;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;

import org.midonet.client.dto.DtoError;
import org.midonet.client.dto.DtoHealthMonitor;
import org.midonet.client.dto.DtoLoadBalancer;
import org.midonet.client.dto.DtoPool;
import org.midonet.client.dto.DtoVip;
import org.midonet.client.dto.l4lb.LBStatus;
import org.midonet.cluster.rest_api.ServiceUnavailableHttpException;
import org.midonet.cluster.services.rest_api.MidonetMediaTypes;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;

import static javax.ws.rs.core.Response.Status.CONFLICT;
import static javax.ws.rs.core.Response.Status.NOT_FOUND;
import static javax.ws.rs.core.Response.Status.SERVICE_UNAVAILABLE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.midonet.cluster.rest_api.validation.MessageProperty.RESOURCE_EXISTS;
import static org.midonet.cluster.rest_api.validation.MessageProperty.RESOURCE_NOT_FOUND;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_POOL_COLLECTION_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_POOL_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_POOL_MEMBER_COLLECTION_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_VIP_COLLECTION_JSON;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_VIP_JSON;

@RunWith(Enclosed.class)
public class TestPool {

    public static class TestPoolCrud extends L4LBTestBase {

        private DtoLoadBalancer loadBalancer;

        @Before
        public void setUp() throws Exception {
            super.setUp();
            loadBalancer = createStockLoadBalancer();
        }

        private void verifyNumberOfPools(int num) {
            DtoPool[] pools = dtoResource.getAndVerifyOk(
                    topLevelPoolsUri,
                    MidonetMediaTypes.APPLICATION_POOL_COLLECTION_JSON(),
                    DtoPool[].class);
            assertEquals(num, pools.length);
        }

        private void checkHealthMonitorBackref(
                URI healthMonitorUri, DtoPool expectedPool) {
            // Check health monitor backreference.
            DtoHealthMonitor hm = getHealthMonitor(healthMonitorUri);
            DtoPool[] hmPools = dtoResource.getAndVerifyOk(hm.getPools(),
                    APPLICATION_POOL_COLLECTION_JSON(),
                    DtoPool[].class);
            if (expectedPool == null) {
                assertEquals(0, hmPools.length);
            } else {
                assertEquals(1, hmPools.length);
                assertEquals(expectedPool, hmPools[0]);
            }
        }

        private void checkVipBackrefs(DtoPool expectedPool, URI... vipUris) {
            for (URI vipUri : vipUris) {
                DtoVip vip = getVip(vipUri);
                if (expectedPool == null) {
                    assertNull(vip.getPoolId());
                    assertNull(vip.getPool());
                } else {
                    assertEquals(expectedPool.getId(), vip.getPoolId());
                    assertEquals(expectedPool.getUri(), vip.getPool());
                }
            }
        }

        @Test
        public void testCrud() throws Exception {
            // Pools should be empty
            verifyNumberOfPools(0);

            // Post
            DtoPool pool = createStockPool(loadBalancer.getId());
            verifyNumberOfPools(1);

            // Post another
            DtoPool pool2 = createStockPool(loadBalancer.getId());
            verifyNumberOfPools(2);

            // Get and check
            DtoPool newPool = getPool(pool.getUri());
            Assert.assertEquals(pool, newPool);
            newPool = getPool(pool2.getUri());
            Assert.assertEquals(newPool, pool2);

            /* This ensures that the deletePool calls below succeed. */
            activatePoolHMMappingStatus(pool.getId());
            activatePoolHMMappingStatus(pool2.getId());

            // Delete
            deletePool(pool.getUri());
            verifyNumberOfPools(1);
            deletePool(pool2.getUri());
            verifyNumberOfPools(0);
        }

        @Test
        public void testCreateIntializesReferences() {
            DtoPool pool = getStockPool(loadBalancer.getId());
            DtoHealthMonitor healthMonitor = createStockHealthMonitor();
            pool.setHealthMonitorId(healthMonitor.getId());
            pool = postPool(pool);
            assertEquals(healthMonitor.getUri(), pool.getHealthMonitor());
            checkHealthMonitorBackref(healthMonitor.getUri(), pool);
        }

        @Test
        public void testUpdateReferences()
                throws SerializationException, StateAccessException {
            // Start with no health monitor.
            DtoPool pool = createStockPool(loadBalancer.getId());
            assertNull(pool.getHealthMonitor());

            /* This ensures that the updatePool call below succeeds. */
            activatePoolHMMappingStatus(pool.getId());

            // Clear reference.
            pool.setHealthMonitorId(null);
            pool = updatePool(pool);
            // Check if the reference is gone.
            assertNull(pool.getHealthMonitor());

            /* This ensures that the updatePool call below succeeds. */
            activatePoolHMMappingStatus(pool.getId());

            // Add a health monitor.
            DtoHealthMonitor healthMonitor1 = createStockHealthMonitor();
            pool.setHealthMonitorId(healthMonitor1.getId());
            pool = updatePool(pool);

            // Pool and healthMonitor1 should now reference each other.
            assertEquals(healthMonitor1.getUri(), pool.getHealthMonitor());
            checkHealthMonitorBackref(healthMonitor1.getUri(), pool);
        }

        @Test
        public void testDeleteClearsBackrefs() {
            DtoPool pool = createStockPool(loadBalancer.getId());

            // Add some VIPs.
            DtoVip vip = createStockVip(pool.getId());
            DtoVip vip2 = createStockVip(pool.getId());
            checkVipBackrefs(pool, vip.getUri(), vip2.getUri());

            /* This ensures that the deletePool call below succeeds. */
            activatePoolHMMappingStatus(pool.getId());

            deletePool(pool.getUri());
            // Strongly associated resources are deleted by cascading.
            dtoResource.getAndVerifyNotFound(vip.getUri(),
                    MidonetMediaTypes.APPLICATION_VIP_JSON());
            dtoResource.getAndVerifyNotFound(vip2.getUri(),
                    MidonetMediaTypes.APPLICATION_VIP_JSON());
        }

        @Test
        public void testCreateWithBadHealthMonitorId() {
            DtoPool pool = getStockPool(loadBalancer.getId());
            pool.setHealthMonitorId(UUID.randomUUID());
            dtoResource.postAndVerifyError(topLevelPoolsUri,
                                           APPLICATION_POOL_JSON(), pool,
                                           NOT_FOUND);
        }

        @Test
        public void testCreateWithDuplicatePoolId() {
            DtoPool pool1 = createStockPool(loadBalancer.getId());
            DtoPool pool2 = getStockPool(loadBalancer.getId());
            pool2.setId(pool1.getId());
            DtoError error = dtoResource.postAndVerifyError(
                    topLevelPoolsUri, APPLICATION_POOL_JSON(), pool2, CONFLICT);
            assertErrorMatches(error, RESOURCE_EXISTS, "Pool", pool2.getId());
        }

        @Test
        public void testCreateWithoutLbMethod() {
            DtoPool pool = getStockPool(loadBalancer.getId());
            pool.setLbMethod(null);
            // `lbMethod` property of Pool is mandatory.
            dtoResource.postAndVerifyBadRequest(
                    topLevelPoolsUri, APPLICATION_POOL_JSON(), pool);
        }

        @Test
        public void testGetWithRandomPoolId() throws Exception {
            UUID id = UUID.randomUUID();
            URI uri = addIdToUri(topLevelPoolsUri, id);
            DtoError error = dtoResource.getAndVerifyNotFound(
                    uri, APPLICATION_POOL_JSON());
            assertErrorMatches(error, RESOURCE_NOT_FOUND, "Pool", id);
        }

        @Test
        public void testDeleteWithRandomPoolId() throws Exception {
            // Succeeds because delete is idempotent.
            deletePool(addIdToUri(topLevelPoolsUri, UUID.randomUUID()));
        }

        @Test
        public void testUpdateWithBadPoolId() throws Exception {
            DtoPool pool = createStockPool(loadBalancer.getId());
            pool.setId(UUID.randomUUID());
            pool.setUri(addIdToUri(topLevelPoolsUri, pool.getId()));
            DtoError error = dtoResource.putAndVerifyNotFound(
                    pool.getUri(), APPLICATION_POOL_JSON(), pool);
            assertErrorMatches(error, RESOURCE_NOT_FOUND, "Pool", pool.getId());
        }

        @Test
        public void testUpdateWithBadHealthMonitorId() {
            DtoPool pool = createStockPool(loadBalancer.getId());
            pool.setHealthMonitorId(UUID.randomUUID());
            /* This ensures that the put call below succeeds. */
            activatePoolHMMappingStatus(pool.getId());

            ClientResponse res = dtoResource.putAndVerifyStatus(
                pool.getUri(), APPLICATION_POOL_JSON(), pool,
                NOT_FOUND.getStatusCode());
            assertErrorMatches(res.getEntity(DtoError.class),
                               RESOURCE_NOT_FOUND, "HealthMonitor",
                               pool.getHealthMonitorId());
        }

        @Test
        public void testListVips() {
            // Should start out empty.
            DtoPool pool = createStockPool(loadBalancer.getId());
            DtoVip[] vips = getVips(pool.getVips());
            assertEquals(0, vips.length);

            // Add one VIP.
            DtoVip vip1 = createStockVip(pool.getId());
            vips = getVips(pool.getVips());
            assertEquals(1, vips.length);
            assertEquals(vip1, vips[0]);

            // Try to add a second VIP without a reference to the pool and
            // fail with 400 Bad Request. `poolId` can't be null.
            DtoVip vip2 = getStockVip(null);
            dtoResource.postAndVerifyBadRequest(topLevelVipsUri,
                    APPLICATION_VIP_JSON(), vip2);
            vips = getVips(pool.getVips());
            assertEquals(1, vips.length);
            assertEquals(vip1, vips[0]);

            // Create a new VIP linking it to the pool.
            vip2 = createStockVip(pool.getId());
            vips = getVips(pool.getVips());
            assertEquals(2, vips.length);

            if (vip1.equals(vips[0])) {
                assertEquals(vip2, vips[1]);
            } else if (vip2.equals(vips[0])) {
                assertEquals(vip1, vips[1]);
            } else {
                Assert.fail("Neither VIP equal to vips[0]");
            }
        }

        @Test
        public void testListVipsWithRandomPoolId() throws Exception {
            UUID id = UUID.randomUUID();
            URI uri = new URI(topLevelPoolsUri + "/" + id + "/vips");
            DtoError error = dtoResource.getAndVerifyNotFound(
                    uri, APPLICATION_VIP_COLLECTION_JSON());
            assertErrorMatches(error, RESOURCE_NOT_FOUND, "Pool", id);
        }

        @Test
        public void testListMembersWithRandomPoolId() throws Exception {
            UUID id = UUID.randomUUID();
            URI uri = new URI(topLevelPoolsUri + "/" + id + "/pool_members");
            DtoError error = dtoResource.getAndVerifyNotFound(
                    uri, APPLICATION_POOL_MEMBER_COLLECTION_JSON());
            assertErrorMatches(error, RESOURCE_NOT_FOUND, "Pool", id);
        }

        @Test
        synchronized public void testPoolsOfLoadBalancer() {
            int poolsCounter = 0;
            // Create two Pools in advance.
            DtoPool pool1 = createStockPool(loadBalancer.getId());
            poolsCounter++;
            assertEquals(loadBalancer.getId(), pool1.getLoadBalancerId());

            DtoPool pool2 = createStockPool(loadBalancer.getId());
            poolsCounter++;
            assertEquals(loadBalancer.getId(), pool2.getLoadBalancerId());

            DtoPool[] pools = getPools(loadBalancer.getPools());
            assertEquals(poolsCounter, pools.length);

            // POST another one to the URI of the load balancer without the
            // explicit load balancer ID.:qa

            DtoPool pool3 = getStockPool(loadBalancer.getId());
            pool3  = dtoResource.postAndVerifyCreated(loadBalancer.getPools(),
                    APPLICATION_POOL_JSON(), pool3, DtoPool.class);
            poolsCounter++;
            assertEquals(loadBalancer.getId(), pool3.getLoadBalancerId());

            // Traverse the pools associated withe the load balancer
            pools = getPools(loadBalancer.getPools());
            assertEquals(poolsCounter, pools.length);
            for (DtoPool originalPool : pools) {
                DtoPool retrievedPool = dtoResource.getAndVerifyOk(
                        originalPool.getUri(),
                        APPLICATION_POOL_JSON(), DtoPool.class);
                assertEquals(originalPool, retrievedPool);

            }
        }

        @Test
        public void testCreatePoolAndStatusDefaultsToUp()
                throws Exception {
            DtoPool pool = createStockPool(loadBalancer.getId());
            assertEquals(LBStatus.ACTIVE, pool.getStatus());

            // Even if the users put values in the `status` property, it should
            // be ignored and `status` should default to UP.
            DtoPool pool2 = getStockPool(loadBalancer.getId());
            pool2.setStatus(null);
            pool2 = postPool(pool2);
            assertEquals(LBStatus.ACTIVE, pool2.getStatus());
        }

        @Test
        public void testPoolStatusCanNotBeChanged() throws Exception {
            DtoPool pool = createStockPool(loadBalancer.getId());
            assertEquals(LBStatus.ACTIVE, pool.getStatus());

            /* This ensures that the updatePool call below succeeds. */
            activatePoolHMMappingStatus(pool.getId());

            pool.setStatus(LBStatus.INACTIVE);
            pool = updatePool(pool);
            assertEquals(LBStatus.ACTIVE, pool.getStatus());
        }

        @Test
        public void testServiceUnavailableWithNullHealthMonitor()
                throws Exception {

            DtoPool pool = createStockPool(loadBalancer.getId());
            /* This ensures that the updatePool call below succeeds. */
            activatePoolHMMappingStatus(pool.getId());

            DtoHealthMonitor healthMonitor = createStockHealthMonitor();
            pool.setHealthMonitorId(healthMonitor.getId());
            pool = updatePool(pool);

            // PUT the pool during its mappingStatus is PENDING_*, which triggers
            // 503 Service Unavailable.
            pool.setHealthMonitorId(null);
            ClientResponse response = dtoResource.putAndVerifyStatus(
                    pool.getUri(), APPLICATION_POOL_JSON(), pool,
                    SERVICE_UNAVAILABLE.getStatusCode());
            MultivaluedMap<String, String> headers = response.getHeaders();
            String expectedRetryAfterHeaderValue = ServiceUnavailableHttpException
                    .RETRY_AFTER_HEADER_DEFAULT_VALUE.toString();
            String actualRetryAfterHeaderValue = headers.get(
                    ServiceUnavailableHttpException.RETRY_AFTER_HEADER_KEY).get(0);
            assertEquals(expectedRetryAfterHeaderValue,
                    actualRetryAfterHeaderValue);
        }
    }
}

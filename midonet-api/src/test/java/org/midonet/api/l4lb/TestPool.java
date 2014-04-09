/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.api.l4lb;

import com.sun.jersey.api.client.ClientResponse;
import junit.framework.Assert;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.midonet.api.VendorMediaType;
import org.midonet.api.rest_api.ServiceUnavailableHttpException;
import org.midonet.api.validation.MessageProperty;
import org.midonet.api.zookeeper.StaticMockDirectory;
import org.midonet.client.dto.DtoError;
import org.midonet.client.dto.DtoHealthMonitor;
import org.midonet.client.dto.DtoLoadBalancer;
import org.midonet.client.dto.DtoPool;
import org.midonet.client.dto.DtoVip;
import org.midonet.client.dto.l4lb.LBStatus;

import java.net.URI;
import java.util.UUID;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import static javax.ws.rs.core.Response.Status.BAD_REQUEST;
import static javax.ws.rs.core.Response.Status.CONFLICT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.midonet.api.VendorMediaType.APPLICATION_POOL_COLLECTION_JSON;
import static org.midonet.api.VendorMediaType.APPLICATION_POOL_JSON;
import static org.midonet.api.VendorMediaType.APPLICATION_POOL_MEMBER_COLLECTION_JSON;
import static org.midonet.api.VendorMediaType.APPLICATION_VIP_COLLECTION_JSON;
import static org.midonet.api.VendorMediaType.APPLICATION_VIP_JSON;
import static org.midonet.api.validation.MessageProperty.RESOURCE_EXISTS;
import static org.midonet.api.validation.MessageProperty.RESOURCE_NOT_FOUND;

@RunWith(Enclosed.class)
public class TestPool {


    public static class TestPoolCrud extends L4LBTestBase {

        private DtoLoadBalancer loadBalancer;

        @Before
        public void setUp() {
            super.setUp();

            loadBalancer = createStockLoadBalancer();
        }

        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        private void verifyNumberOfPools(int num) {
            DtoPool[] pools = dtoWebResource.getAndVerifyOk(
                    topLevelPoolsUri,
                    VendorMediaType.APPLICATION_POOL_COLLECTION_JSON,
                    DtoPool[].class);
            assertEquals(num, pools.length);
        }

        private void checkHealthMonitorBackref(
                URI healthMonitorUri, DtoPool expectedPool) {
            // Check health monitor backreference.
            DtoHealthMonitor hm = getHealthMonitor(healthMonitorUri);
            DtoPool[] hmPools = dtoWebResource.getAndVerifyOk(hm.getPools(),
                    APPLICATION_POOL_COLLECTION_JSON,
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
        public void testUpdateUpdatesReferences() {
            // Start with no health monitor.
            DtoPool pool = createStockPool(loadBalancer.getId());
            assertNull(pool.getHealthMonitor());

            // Add a health monitor.
            DtoHealthMonitor healthMonitor1 = createStockHealthMonitor();
            pool.setHealthMonitorId(healthMonitor1.getId());
            pool = updatePool(pool);

            // Pool and healthMonitor1 should now reference each other.
            assertEquals(healthMonitor1.getUri(), pool.getHealthMonitor());
            checkHealthMonitorBackref(healthMonitor1.getUri(), pool);

            // Clear references.
            pool.setHealthMonitorId(null);
            pool = updatePool(pool);

            // All references gone.
            assertNull(pool.getHealthMonitor());
        }

        @Test
        public void testDeleteClearsBackrefs() {
            DtoPool pool = createStockPool(loadBalancer.getId());

            // Add some VIPs.
            DtoVip vip = createStockVip(pool.getId());
            DtoVip vip2 = createStockVip(pool.getId());
            checkVipBackrefs(pool, vip.getUri(), vip2.getUri());

            deletePool(pool.getUri());
            // Strongly associated resources are deleted by cascading.
            dtoWebResource.getAndVerifyNotFound(vip.getUri(),
                    VendorMediaType.APPLICATION_VIP_JSON);
            dtoWebResource.getAndVerifyNotFound(vip2.getUri(),
                    VendorMediaType.APPLICATION_VIP_JSON);
        }

        @Test
        public void testCreateWithBadHealthMonitorId() {
            DtoPool pool = getStockPool(loadBalancer.getId());
            pool.setHealthMonitorId(UUID.randomUUID());
            DtoError error = dtoWebResource.postAndVerifyError(
                    topLevelPoolsUri, APPLICATION_POOL_JSON, pool, BAD_REQUEST);
            assertErrorMatches(error, RESOURCE_NOT_FOUND,
                    "health monitor", pool.getHealthMonitorId());
        }

        @Test
        public void testCreateWithDuplicatePoolId() {
            DtoPool pool1 = createStockPool(loadBalancer.getId());
            DtoPool pool2 = getStockPool(loadBalancer.getId());
            pool2.setId(pool1.getId());
            DtoError error = dtoWebResource.postAndVerifyError(
                    topLevelPoolsUri, APPLICATION_POOL_JSON, pool2, CONFLICT);
            assertErrorMatches(error, RESOURCE_EXISTS, "pool", pool2.getId());
        }

        @Test
        public void testCreateWithoutLbMethod() {
            DtoPool pool = getStockPool(loadBalancer.getId());
            pool.setLbMethod(null);
            // `lbMethod` property of Pool is mandatory.
            dtoWebResource.postAndVerifyBadRequest(
                    topLevelPoolsUri, APPLICATION_POOL_JSON, pool);
        }

        @Test
        public void testGetWithRandomPoolId() throws Exception {
            UUID id = UUID.randomUUID();
            URI uri = addIdToUri(topLevelPoolsUri, id);
            DtoError error = dtoWebResource.getAndVerifyNotFound(
                    uri, APPLICATION_POOL_JSON);
            assertErrorMatches(error, RESOURCE_NOT_FOUND, "pool", id);
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
            DtoError error = dtoWebResource.putAndVerifyNotFound(
                    pool.getUri(), APPLICATION_POOL_JSON, pool);
            assertErrorMatches(error, RESOURCE_NOT_FOUND, "pool", pool.getId());
        }

        @Test
        public void testUpdateWithBadHealthMonitorId() {
            DtoPool pool = createStockPool(loadBalancer.getId());
            pool.setHealthMonitorId(UUID.randomUUID());
            DtoError error = dtoWebResource.putAndVerifyBadRequest(
                    pool.getUri(), APPLICATION_POOL_JSON, pool);
            assertErrorMatches(error, RESOURCE_NOT_FOUND,
                    "health monitor", pool.getHealthMonitorId());
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
            dtoWebResource.postAndVerifyBadRequest(topLevelVipsUri,
                    APPLICATION_VIP_JSON,
                    vip2);
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
            DtoError error = dtoWebResource.getAndVerifyNotFound(
                    uri, APPLICATION_VIP_COLLECTION_JSON);
            assertErrorMatches(error, RESOURCE_NOT_FOUND, "pool", id);
        }

        @Test
        public void testListMembersWithRandomPoolId() throws Exception {
            UUID id = UUID.randomUUID();
            URI uri = new URI(topLevelPoolsUri + "/" + id + "/pool_members");
            DtoError error = dtoWebResource.getAndVerifyNotFound(
                    uri, APPLICATION_POOL_MEMBER_COLLECTION_JSON);
            assertErrorMatches(error, RESOURCE_NOT_FOUND, "pool", id);
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
            // explicit load balancer ID.
            DtoPool pool3 = getStockPool(loadBalancer.getId());
            pool3  = dtoWebResource.postAndVerifyCreated(loadBalancer.getPools(),
                    APPLICATION_POOL_JSON, pool3, DtoPool.class);
            poolsCounter++;
            assertEquals(loadBalancer.getId(), pool3.getLoadBalancerId());

            // Traverse the pools associated withe the load balancer
            pools = getPools(loadBalancer.getPools());
            assertEquals(poolsCounter, pools.length);
            for (DtoPool originalPool : pools) {
                DtoPool retrievedPool = dtoWebResource.getAndVerifyOk(
                        originalPool.getUri(),
                        APPLICATION_POOL_JSON, DtoPool.class);
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
        public void testPoolStatusCanNotBeChanged()
                throws Exception {
            DtoPool pool = createStockPool(loadBalancer.getId());
            assertEquals(LBStatus.ACTIVE, pool.getStatus());

            pool.setStatus(LBStatus.INACTIVE);
            pool = updatePool(pool);
            assertEquals(LBStatus.ACTIVE, pool.getStatus());
        }

        @Test
        public void testServiceUnavailable()
                throws Exception {
            DtoPool pool = createStockPool(loadBalancer.getId());
            DtoHealthMonitor healthMonitor = createStockHealthMonitor();
            pool.setHealthMonitorId(healthMonitor.getId());
            pool = updatePool(pool);

            // Update with another health monitor, which triggers the 503.
            DtoHealthMonitor healthMonitor2 = createStockHealthMonitor();
            pool.setHealthMonitorId(healthMonitor2.getId());
            ClientResponse response = dtoWebResource.putAndVerifyStatus(pool.getUri(),
                    APPLICATION_POOL_JSON, pool,
                    Response.Status.SERVICE_UNAVAILABLE.getStatusCode());
            MultivaluedMap<String, String> headers = response.getHeaders();
            assertEquals("3", headers.get(
                    ServiceUnavailableHttpException.RETRY_AFTER_HEADER_KEY).get(0));
        }
    }
}

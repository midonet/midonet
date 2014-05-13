/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.api.l4lb.e2e;

import org.apache.zookeeper.KeeperException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.midonet.api.l4lb.VIP;
import org.midonet.api.zookeeper.StaticMockDirectory;
import org.midonet.client.dto.DtoError;
import org.midonet.client.dto.DtoLoadBalancer;
import org.midonet.client.dto.DtoPool;
import org.midonet.client.dto.DtoVip;
import org.midonet.client.dto.l4lb.VipSessionPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static javax.ws.rs.core.Response.Status.BAD_REQUEST;
import static javax.ws.rs.core.Response.Status.CONFLICT;
import static javax.ws.rs.core.Response.Status.NOT_FOUND;
import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotSame;
import static org.midonet.api.VendorMediaType.APPLICATION_VIP_COLLECTION_JSON;
import static org.midonet.api.VendorMediaType.APPLICATION_VIP_JSON;
import static org.midonet.api.validation.MessageProperty.RESOURCE_EXISTS;
import static org.midonet.api.validation.MessageProperty.RESOURCE_NOT_FOUND;

@RunWith(Enclosed.class)
public class TestVip {
    public static class TestVipCrud extends L4LBTestBase {
        private final static Logger log = LoggerFactory
                .getLogger(VIP.class);

        private DtoLoadBalancer loadBalancer;
        private DtoPool pool;
        private DtoVip vip;
        private int vipCounter;

        @Before
        public void setUp() throws Exception {
            super.setUp();

            vipCounter = 0;
            // VIPs should be empty
            verifyNumberOfVips(vipCounter);

            // Create a VIP with loadBalancer1 and pool1.
            loadBalancer = createStockLoadBalancer();
            pool = createStockPool(loadBalancer.getId());
            vip = createStockVip(pool.getId());
            vipCounter++;

            assertParentChildRelationship(loadBalancer, pool, vip);
        }

        private void verifyNumberOfVips(int num) {
            DtoVip[] vips = getVips(topLevelVipsUri);
            assertEquals(num, vips.length);
        }

        private void postVipAndVerifyBadRequestError(DtoVip vip, Object... args) {
            DtoError error = dtoResource.postAndVerifyBadRequest(
                    topLevelVipsUri, APPLICATION_VIP_JSON, vip);
            assertErrorMatches(error, RESOURCE_NOT_FOUND, args);
        }

        private void putVipAndVerifyNotFoundError(DtoVip vip, Object... args) {
            DtoError error = dtoResource.putAndVerifyError(vip.getUri(),
                    APPLICATION_VIP_JSON, vip, NOT_FOUND);
            assertErrorMatches(error, RESOURCE_NOT_FOUND, args);
        }

        private void checkBackrefs(
                URI loadBalancerUri, URI poolUri, DtoVip expectedVip) {
            // Check load balancer backreference.
            if (loadBalancerUri != null) {
                DtoLoadBalancer loadBalancer = getLoadBalancer(loadBalancerUri);
                DtoVip[] lbVips = dtoResource.getAndVerifyOk(
                        loadBalancer.getVips(),
                        APPLICATION_VIP_COLLECTION_JSON,
                        DtoVip[].class);
                if (expectedVip == null) {
                    assertEquals(0, lbVips.length);
                } else {
                    assertEquals(1, lbVips.length);
                    assertEquals(expectedVip, lbVips[0]);
                }
            }

            // Check pool backreference.
            if (poolUri != null) {
                DtoPool pool = getPool(poolUri);
                DtoVip[] poolVips = dtoResource.getAndVerifyOk(
                        pool.getVips(),
                        APPLICATION_VIP_COLLECTION_JSON,
                        DtoVip[].class);
                if (expectedVip == null) {
                    assertEquals(0, poolVips.length);
                } else {
                    assertEquals(1, poolVips.length);
                    assertEquals(expectedVip, poolVips[0]);
                }
            }
        }

        private void assertChildVips(DtoVip[] poolVips) {
            // Traverse the pools associated with the pool.
            for (DtoVip originalVip: poolVips) {
                DtoVip retrievedVip = dtoResource.getAndVerifyOk(
                        originalVip.getUri(),
                        APPLICATION_VIP_JSON, DtoVip.class);
                assertEquals(originalVip, retrievedVip);
            }
        }

        private void assertParentChildRelationship(
                DtoLoadBalancer loadBalancer,
                DtoPool pool,
                DtoVip vip) {
            assertEquals(pool.getId(), vip.getPoolId());
            assertEquals(loadBalancer.getId(), vip.getLoadBalancerId());
        }

        @Test
        synchronized public void testCrud() throws Exception {
            DtoPool pool2 = createStockPool(loadBalancer.getId());

            // POST
            DtoVip vip2 = createStockVip(pool.getId());
            vipCounter++;
            verifyNumberOfVips(vipCounter);

            // POST another one
            DtoVip vip3 = createStockVip(pool2.getId());
            vipCounter++;
            verifyNumberOfVips(vipCounter);

            // POST with the same ID as the existing resource and get 409
            // CONFLICT.
            DtoError error = dtoResource.postAndVerifyError(
                    topLevelVipsUri, APPLICATION_VIP_JSON, vip3, CONFLICT);
            assertErrorMatches(error, RESOURCE_EXISTS, "VIP", vip3.getId());
            verifyNumberOfVips(vipCounter);

            // GET and check if it is the same as what we POSTed.
            DtoVip newVip = getVip(vip2.getUri());
            // It checks the id as well but they're identical because POST
            // accepts populated id and create the VIP with the id.
            assertEquals(newVip, vip2);

            DtoVip newVip2 = getVip(vip3.getUri());
            // It checks the id as well but they're identical because POST
            // accepts populated id and create the VIP with the id.
            assertEquals(newVip2, vip3);

            // PUT with the different parameters
            newVip2.setAdminStateUp(!newVip2.isAdminStateUp());
            DtoVip updatedVip2 = updateVip(newVip2);
            assertEquals(updatedVip2, newVip2);
            verifyNumberOfVips(vipCounter);

            // PUT with the populated `sessionPersistence`.
            newVip2.setSessionPersistence(VipSessionPersistence.SOURCE_IP);
            newVip2 = updateVip(newVip2);
            assertEquals(VipSessionPersistence.SOURCE_IP,
                    newVip2.getSessionPersistence());
            verifyNumberOfVips(vipCounter);

            // POST with the populated `sessionPersistence`.
            DtoVip sessionPersistenceVip = getVip(newVip2.getUri());
            sessionPersistenceVip.setId(UUID.randomUUID());
            sessionPersistenceVip.setSessionPersistence(
                    VipSessionPersistence.SOURCE_IP);
            postVip(sessionPersistenceVip);
            vipCounter++;
            verifyNumberOfVips(vipCounter);

            // DELETE all created VIPs.
            deleteVip(vip2.getUri());
            vipCounter--;
            verifyNumberOfVips(vipCounter);
            deleteVip(vip3.getUri());
            vipCounter--;
            verifyNumberOfVips(vipCounter);
        }

        @Test
        public void testCreateInitializesReferences() {
            DtoLoadBalancer loadBalancer = createStockLoadBalancer();
            DtoPool pool = getStockPool(loadBalancer.getId());
            pool = postPool(pool);
            DtoVip vip = createStockVip(pool.getId());

            checkBackrefs(vip.getLoadBalancer(), vip.getPool(), vip);
            assertEquals(loadBalancer.getUri(), vip.getLoadBalancer());
            assertEquals(pool.getUri(), vip.getPool());
        }

        @Test
        public void testUpdateUpdatesReferences() {
            checkBackrefs(loadBalancer.getUri(), pool.getUri(), vip);

            // Switch references to loadBalancer2 and pool2.
            DtoLoadBalancer loadBalancer2 = createStockLoadBalancer();
            DtoPool pool2 = createStockPool(loadBalancer2.getId());
            vip.setPoolId(pool2.getId());

            // References between vip and loadBalancer1 and pool1
            // should be cleared and replaced with references to/from
            // loadbalancer2 and pool2.
            vip = updateVip(vip);
            assertEquals(loadBalancer2.getUri(), vip.getLoadBalancer());
            assertEquals(pool2.getUri(), vip.getPool());
            checkBackrefs(loadBalancer.getUri(), pool.getUri(), null);
            checkBackrefs(loadBalancer2.getUri(), pool2.getUri(), vip);

            deleteVip(vip.getUri());

            // LoadBalancer2 and pool2 should no longer reference vip.
            checkBackrefs(loadBalancer2.getUri(), pool2.getUri(), null);
        }

        @Test
        public void testDeleteClearsBackrefs() {
            checkBackrefs(vip.getLoadBalancer(), vip.getPool(), vip);

            deleteVip(vip.getUri());
            checkBackrefs(vip.getLoadBalancer(), vip.getPool(), null);
        }

        @Test
        public void testCreateWithNullPoolId() {
            DtoVip vip = getStockVip(null);
            DtoError error = dtoResource.postAndVerifyError(
                    topLevelVipsUri, APPLICATION_VIP_JSON,
                    vip, BAD_REQUEST);
            assertEquals(error.getViolations().size(), 1);
            Map<String, String> map = error.getViolations().get(0);
            assertEquals(map.get("property"), "poolId");
            assertEquals(map.get("message"), "may not be null");
        }

        @Test
        public void testCreateWithBadPoolId() {
            DtoLoadBalancer loadBalancer = createStockLoadBalancer();
            DtoVip vip = getStockVip(UUID.randomUUID());
            postVipAndVerifyBadRequestError(
                    vip, "pool", vip.getPoolId());
        }

        @Test
        public void testUpdateWithBadVipId() throws Exception {
            DtoVip vip = getStockVip(pool.getId());
            vip.setId(UUID.randomUUID());
            vip.setUri(addIdToUri(topLevelVipsUri, vip.getId()));
            putVipAndVerifyNotFoundError(vip, "VIP", vip.getId());
        }

        @Test
        public void testUpdateWithBadPoolId() {
            vip.setPoolId(UUID.randomUUID());
            DtoError error = dtoResource.putAndVerifyBadRequest(
                    vip.getUri(), APPLICATION_VIP_JSON, vip);
            assertErrorMatches(error, RESOURCE_NOT_FOUND, "pool", vip.getPoolId());
        }


        @Test
        public void testCreateWithoutSessionPersistence() {
            DtoVip vip = getStockVip(pool.getId());
            vip.setSessionPersistence(null);
            dtoResource.postAndVerifyCreated(pool.getVips(),
                    APPLICATION_VIP_JSON, vip, DtoVip.class);
        }

        @Test
        public void testLoadBalancerIdIsIgnored() {
            UUID fakeUuid = UUID.randomUUID();
            vip.setLoadBalancerId(fakeUuid);
            vip = updateVip(vip);
            assertNotSame(fakeUuid, vip.getLoadBalancerId());

            assertParentChildRelationship(loadBalancer, pool, vip);
        }

        @Test
        public void testVipsOfPool() {
            DtoVip vip2 = createStockVip(pool.getId());
            vipCounter++;
            assertParentChildRelationship(loadBalancer, pool, vip2);

            DtoVip[] vips = getVips(loadBalancer.getVips());
            assertEquals(vipCounter, vips.length);

            // POST another one from the URI of the pool.
            DtoVip vip3 = getStockVip(pool.getId());
            vip3.setPoolId(pool.getId());
            vip3  = dtoResource.postAndVerifyCreated(pool.getVips(),
                    APPLICATION_VIP_JSON, vip3, DtoVip.class);
            vipCounter++;
            assertParentChildRelationship(loadBalancer, pool, vip3);

            // Traverse the pools associated with the load balancer.
            vips = getVips(pool.getVips());
            assertEquals(vipCounter, vips.length);

            assertChildVips(vips);
        }

        @Test
        public void testVipsOfPoolsAggregated() {
            DtoPool pool2 = createStockPool(loadBalancer.getId());
            DtoVip vip2 = createStockVip(pool2.getId());
            vipCounter++;
            assertParentChildRelationship(loadBalancer, pool2, vip2);

            DtoVip[] vipsOfPool = getVips(pool.getVips());
            assertChildVips(vipsOfPool);

            DtoVip[] vipsOfPool2 = getVips(pool2.getVips());
            assertChildVips(vipsOfPool2);

            DtoVip[] vipsOfLoadBalancer = getVips(loadBalancer.getVips());
            assertChildVips(vipsOfLoadBalancer);

            // Assert if the number of the VIPs is aggregated
            assertEquals(vipsOfLoadBalancer.length, vipCounter);

            // Add another pool and VIP
            DtoPool pool3 = createStockPool(loadBalancer.getId());
            DtoVip vip3 = createStockVip(pool3.getId());
            vipCounter++;
            vipsOfLoadBalancer = getVips(loadBalancer.getVips());

            assertEquals(vipsOfLoadBalancer.length, vipCounter);
        }

        @Test
        public void testVipsOfLoadBalancer() {
            DtoVip vip2 = createStockVip(pool.getId());
            vipCounter++;
            assertParentChildRelationship(loadBalancer, pool, vip2);

            DtoVip[] vips = getVips(loadBalancer.getVips());
            assertEquals(vipCounter, vips.length);

            // POST another one to the URI of the pool without the explicit
            // pool ID.
            DtoVip vip3 = getStockVip(pool.getId());
            vip3  = dtoResource.postAndVerifyCreated(pool.getVips(),
                    APPLICATION_VIP_JSON, vip3, DtoVip.class);
            vipCounter++;
            assertParentChildRelationship(loadBalancer, pool, vip3);

            // The size of VIPs of the load balancer associated with the
            // single pool should equal to the size of VIPs of the pool.
            DtoVip[] loadBalancerVips = getVips(loadBalancer.getVips());
            DtoVip[] poolVips = getVips(pool.getVips());
            assertEquals(vipCounter, loadBalancerVips.length);

            // The VIPs of the load balancer associated with the single pool
            // should equal to the VIPs of the pool.
            assertArrayMembersEqual(loadBalancerVips, poolVips);

            assertChildVips(poolVips);
        }
    }
}

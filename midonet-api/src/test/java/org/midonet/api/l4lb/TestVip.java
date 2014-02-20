/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.api.l4lb;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.midonet.api.zookeeper.StaticMockDirectory;
import org.midonet.client.dto.DtoError;
import org.midonet.client.dto.DtoLoadBalancer;
import org.midonet.client.dto.DtoPool;
import org.midonet.client.dto.DtoVip;
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
import static org.midonet.cluster.data.l4lb.VIP.VIP_SOURCE_IP;

@RunWith(Enclosed.class)
public class TestVip {
    public static class TestVipCrud extends L4LBTestBase {
        private final static Logger log = LoggerFactory
                .getLogger(VIP.class);

        @Before
        public void setUp() {
            super.setUp();
        }

        @After
        public void resetDirectory() throws  Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        private void verifyNumberOfVips(int num) {
            DtoVip[] vips = getVips(topLevelVipsUri);
            assertEquals(num, vips.length);
        }

        private void postVipAndVerifyBadRequestError(DtoVip vip, Object... args) {
            DtoError error = dtoWebResource.postAndVerifyBadRequest(
                    topLevelVipsUri, APPLICATION_VIP_JSON, vip);
            assertErrorMatches(error, RESOURCE_NOT_FOUND, args);
        }

        private void putVipAndVerifyNotFoundError(DtoVip vip, Object... args) {
            DtoError error = dtoWebResource.putAndVerifyError(vip.getUri(),
                    APPLICATION_VIP_JSON, vip, NOT_FOUND);
            assertErrorMatches(error, RESOURCE_NOT_FOUND, args);
        }

        private void checkBackrefs(
                URI loadBalancerUri, URI poolUri, DtoVip expectedVip) {
            // Check load balancer backreference.
            if (loadBalancerUri != null) {
                DtoLoadBalancer loadBalancer = getLoadBalancer(loadBalancerUri);
                DtoVip[] lbVips = dtoWebResource.getAndVerifyOk(
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
                DtoVip[] poolVips = dtoWebResource.getAndVerifyOk(
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

        @Test
        synchronized public void testCrud() throws Exception {
            int counter = 0;
            // VIPs should be empty
            verifyNumberOfVips(counter);

            DtoLoadBalancer lb = createStockLoadBalancer();
            DtoPool pool1 = createStockPool(lb.getId());
            DtoPool pool2 = createStockPool(lb.getId());

            // POST
            DtoVip vip = createStockVip(pool1.getId());
            verifyNumberOfVips(++counter);

            // POST another one
            DtoVip vip2 = createStockVip(pool2.getId());
            verifyNumberOfVips(++counter);

            // POST with the same ID as the existing resource and get 409
            // CONFLICT.
            DtoError error = dtoWebResource.postAndVerifyError(
                    topLevelVipsUri, APPLICATION_VIP_JSON, vip2, CONFLICT);
            assertErrorMatches(error, RESOURCE_EXISTS, "VIP", vip2.getId());
            verifyNumberOfVips(counter);

            // GET and check if it is the same as what we POSTed.
            DtoVip newVip = getVip(vip.getUri());
            // It checks the id as well but they're identical because POST
            // accepts populated id and create the VIP with the id.
            assertEquals(newVip, vip);

            DtoVip newVip2 = getVip(vip2.getUri());
            // It checks the id as well but they're identical because POST
            // accepts populated id and create the VIP with the id.
            assertEquals(newVip2, vip2);

            // PUT with the different parameters
            newVip2.setAdminStateUp(!newVip2.isAdminStateUp());
            DtoVip updatedVip2 = updateVip(newVip2);
            assertEquals(updatedVip2, newVip2);
            verifyNumberOfVips(counter);

            // PUT with the populated `sessionPersistence`.
            newVip2.setSessionPersistence(VIP_SOURCE_IP);
            newVip2 = updateVip(newVip2);
            assertEquals(newVip2.getSessionPersistence(), VIP_SOURCE_IP);
            verifyNumberOfVips(counter);

            // POST with the populated `sessionPersistence`.
            DtoVip sessionPersistenceVip = getVip(newVip2.getUri());
            sessionPersistenceVip.setId(UUID.randomUUID());
            sessionPersistenceVip.setSessionPersistence(VIP_SOURCE_IP);
            postVip(sessionPersistenceVip);
            verifyNumberOfVips(++counter);

            // DELETE all created VIPs.
            deleteVip(vip.getUri());
            verifyNumberOfVips(--counter);
            deleteVip(vip2.getUri());
            verifyNumberOfVips(--counter);
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
            // Create a VIP with loadBalancer1 and pool1.
            DtoLoadBalancer loadBalancer1 = createStockLoadBalancer();
            DtoPool pool1 = createStockPool(loadBalancer1.getId());
            DtoVip vip = createStockVip(pool1.getId());
            // VIP should refer loadBalancer1 and pool1, and vice-versa.
            assertEquals(loadBalancer1.getUri(), vip.getLoadBalancer());
            assertEquals(pool1.getUri(), vip.getPool());
            checkBackrefs(loadBalancer1.getUri(), pool1.getUri(), vip);

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
            checkBackrefs(loadBalancer1.getUri(), pool1.getUri(), null);
            checkBackrefs(loadBalancer2.getUri(), pool2.getUri(), vip);

            deleteVip(vip.getUri());

            // LoadBalancer2 and pool2 should no longer reference vip.
            checkBackrefs(loadBalancer2.getUri(), pool2.getUri(), null);
        }

        @Test
        public void testDeleteClearsBackrefs() {
            DtoLoadBalancer lb = createStockLoadBalancer();
            DtoPool pool = createStockPool(lb.getId());
            DtoVip vip = createStockVip(pool.getId());
            checkBackrefs(vip.getLoadBalancer(), vip.getPool(), vip);

            deleteVip(vip.getUri());
            checkBackrefs(vip.getLoadBalancer(), vip.getPool(), null);
        }

        @Test
        public void testCreateWithNullPoolId() {
            DtoVip vip = getStockVip(null);
            DtoError error = dtoWebResource.postAndVerifyError(
                    topLevelVipsUri, APPLICATION_VIP_JSON,
                    vip, BAD_REQUEST);
            assertEquals(error.getViolations().size(), 1);
            Map<String, String> map = error.getViolations().get(0);
            assertEquals(map.get("property"), "poolId");
            assertEquals(map.get("message"), "may not be null");
        }

        @Test
        public void testCreateWithBadPoolId() {
            DtoLoadBalancer lb = createStockLoadBalancer();
            DtoVip vip = getStockVip(UUID.randomUUID());
            postVipAndVerifyBadRequestError(
                    vip, "pool", vip.getPoolId());
        }

        @Test
        public void testUpdateWithBadVipId() throws Exception {
            DtoLoadBalancer loadBalancer = createStockLoadBalancer();
            DtoPool pool = createStockPool(loadBalancer.getId());
            DtoVip vip = getStockVip(pool.getId());
            vip.setId(UUID.randomUUID());
            vip.setUri(addIdToUri(topLevelVipsUri, vip.getId()));
            putVipAndVerifyNotFoundError(vip, "VIP", vip.getId());
        }

        @Test
        public void testUpdateWithBadPoolId() {
            DtoLoadBalancer loadBalancer = createStockLoadBalancer();
            DtoPool pool = createStockPool(loadBalancer.getId());
            DtoVip vip = createStockVip(pool.getId());
            vip.setPoolId(UUID.randomUUID());
            DtoError error = dtoWebResource.putAndVerifyBadRequest(
                    vip.getUri(), APPLICATION_VIP_JSON, vip);
            assertErrorMatches(error, RESOURCE_NOT_FOUND, "pool", vip.getPoolId());
        }

        @Test
        public void testLoadBalancerIdIsIgnored() {
            DtoLoadBalancer loadBalancer = createStockLoadBalancer();
            DtoPool pool = createStockPool(loadBalancer.getId());
            DtoVip vip = createStockVip(pool.getId());
            assertEquals(loadBalancer.getId(), vip.getLoadBalancerId());

            UUID fakeUuid = UUID.randomUUID();
            vip.setLoadBalancerId(fakeUuid);
            vip = updateVip(vip);
            assertNotSame(fakeUuid, vip.getLoadBalancerId());
            assertEquals(pool.getLoadBalancerId(), vip.getLoadBalancerId());
            assertEquals(loadBalancer.getId(), vip.getLoadBalancerId());
        }
    }
}

/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.api.l4lb;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.midonet.api.validation.MessageProperty;
import org.midonet.api.zookeeper.StaticMockDirectory;
import org.midonet.client.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.UUID;

import static javax.ws.rs.core.Response.Status.*;
import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNull;
import static org.midonet.api.validation.MessageProperty.RESOURCE_EXISTS;
import static org.midonet.api.validation.MessageProperty.RESOURCE_NOT_FOUND;
import static org.midonet.api.VendorMediaType.APPLICATION_VIP_COLLECTION_JSON;
import static org.midonet.api.VendorMediaType.APPLICATION_VIP_JSON;

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

        private void postVipAndVerifyNotFoundError(DtoVip vip, Object... args) {
            DtoError error = dtoWebResource.postAndVerifyError(topLevelVipsUri,
                    APPLICATION_VIP_JSON, vip, NOT_FOUND);
            assertErrorMatches(error, RESOURCE_NOT_FOUND, args);
        }

        private void putVipAndVerifyNotFoundError(DtoVip vip, Object... args) {
            DtoError error = dtoWebResource.putAndVerifyError(vip.getUri(),
                    APPLICATION_VIP_JSON, vip, NOT_FOUND);
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

            // POST
            DtoVip vip = createStockVip();
            verifyNumberOfVips(++counter);

            // POST another one
            DtoVip vip2 = createStockVip();
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

            // DELETE all created VIPs.
            deleteVip(vip.getUri());
            verifyNumberOfVips(--counter);
            deleteVip(vip2.getUri());
            verifyNumberOfVips(--counter);
        }

        @Test
        public void testCreateInitializesReferences() {
            DtoLoadBalancer loadBalancer = createStockLoadBalancer();
            DtoPool pool = createStockPool();
            DtoVip vip = createStockVip(loadBalancer.getId(), pool.getId());

            checkBackrefs(vip.getLoadBalancer(), vip.getPool(), vip);
            assertEquals(loadBalancer.getUri(), vip.getLoadBalancer());
            assertEquals(pool.getUri(), vip.getPool());
        }

        @Test
        public void testUpdateUpdatesReferences() {
            // Create a VIP without load balancer or pool.
            DtoVip vip = createStockVip(null, null);
            assertNull(vip.getLoadBalancer());
            assertNull(vip.getPool());

            // Add references to loadBalancer1 and pool1.
            DtoLoadBalancer loadBalancer1 = createStockLoadBalancer();
            vip.setLoadBalancerId(loadBalancer1.getId());
            DtoPool pool1 = createStockPool();
            vip.setPoolId(pool1.getId());
            vip = updateVip(vip);

            // VIP should reference loadBalancer1 and pool1, and vice-versa.
            assertEquals(loadBalancer1.getUri(), vip.getLoadBalancer());
            assertEquals(pool1.getUri(), vip.getPool());
            checkBackrefs(loadBalancer1.getUri(), pool1.getUri(), vip);

            // Switch references to loadBalancer2 and pool2.
            DtoLoadBalancer loadBalancer2 = createStockLoadBalancer();
            vip.setLoadBalancerId(loadBalancer2.getId());
            DtoPool pool2 = createStockPool();
            vip.setPoolId(pool2.getId());

            // References between vip and loadBalancer1 and pool1
            // should be cleared and replaced with references to/from
            // loadbalancer2 and pool2.
            vip = updateVip(vip);
            assertEquals(loadBalancer2.getUri(), vip.getLoadBalancer());
            assertEquals(pool2.getUri(), vip.getPool());
            checkBackrefs(loadBalancer1.getUri(), pool1.getUri(), null);
            checkBackrefs(loadBalancer2.getUri(), pool2.getUri(), vip);

            // Clear references.
            vip.setLoadBalancerId(null);
            vip.setPoolId(null);
            vip = updateVip(vip);

            // All references gone.
            assertNull(vip.getLoadBalancer());
            assertNull(vip.getPool());
            checkBackrefs(loadBalancer2.getUri(), pool2.getUri(), null);
        }

        @Test
        public void testDeleteClearsBackrefs() {
            DtoVip vip = createStockVip();
            checkBackrefs(vip.getLoadBalancer(), vip.getPool(), vip);

            deleteVip(vip.getUri());
            checkBackrefs(vip.getLoadBalancer(), vip.getPool(), null);
        }

        @Test
        public void testCreateWithoutLoadBalancer() {
            DtoPool pool = createStockPool();
            DtoVip vip = createStockVip(null, pool.getId());
            checkBackrefs(null, pool.getUri(), vip);
        }

        @Test
        public void testCreateWithoutPool() {
            DtoLoadBalancer loadBalancer = createStockLoadBalancer();
            DtoVip vip = createStockVip(loadBalancer.getId(), null);
            checkBackrefs(loadBalancer.getUri(), null, vip);
        }

        @Test
        public void testCreateWithBadLoadBalancerId() {
            DtoPool pool = createStockPool();
            DtoVip vip = getStockVip(UUID.randomUUID(), pool.getId());
            postVipAndVerifyNotFoundError(
                    vip, "load balancer", vip.getLoadBalancerId());
        }

        @Test
        public void testCreateWithBadPoolId() {
            DtoLoadBalancer lb = createStockLoadBalancer();
            DtoVip vip = getStockVip(lb.getId(), UUID.randomUUID());
            postVipAndVerifyNotFoundError(
                    vip, "pool", vip.getPoolId());
        }

        @Test
        public void testUpdateWithBadVipId() throws Exception {
            DtoVip vip = createStockVip();
            vip.setId(UUID.randomUUID());
            vip.setUri(addIdToUri(topLevelVipsUri, vip.getId()));
            putVipAndVerifyNotFoundError(vip, "VIP", vip.getId());
        }

        @Test
        public void testUpdateWithBadLoadBalancerId() {
            DtoVip vip = createStockVip();
            vip.setLoadBalancerId(UUID.randomUUID());
            putVipAndVerifyNotFoundError(
                    vip, "load balancer", vip.getLoadBalancerId());
        }

        @Test
        public void testUpdateWithBadPoolId() {
            DtoVip vip = createStockVip();
            vip.setPoolId(UUID.randomUUID());
            putVipAndVerifyNotFoundError(
                    vip, "pool", vip.getPoolId());
        }
    }
}

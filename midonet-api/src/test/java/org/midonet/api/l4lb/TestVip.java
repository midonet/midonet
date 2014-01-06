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
import org.midonet.client.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static javax.ws.rs.core.Response.Status.*;
import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNull;

import java.net.URI;

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
            DtoVip[] vips = dtoWebResource.getAndVerifyOk(topLevelVipsUri,
                    VendorMediaType.APPLICATION_VIP_JSON, DtoVip[].class);
            assertEquals(num, vips.length);
        }

        private void checkBackrefs(
                URI loadBalancerUri, URI poolUri, DtoVip expectedVip) {
            // Check load balancer backreference.
            DtoLoadBalancer loadBalancer = getLoadBalancer(loadBalancerUri);
            DtoVip[] lbVips = dtoWebResource.getAndVerifyOk(
                    loadBalancer.getVips(),
                    VendorMediaType.APPLICATION_VIP_COLLECTION_JSON,
                    DtoVip[].class);
            if (expectedVip == null) {
                assertEquals(0, lbVips.length);
            } else {
                assertEquals(1, lbVips.length);
                assertEquals(expectedVip, lbVips[0]);
            }

            // Check pool backreference.
            DtoPool pool = getPool(poolUri);
            DtoVip[] poolVips = dtoWebResource.getAndVerifyOk(
                    pool.getVips(),
                    VendorMediaType.APPLICATION_VIP_COLLECTION_JSON,
                    DtoVip[].class);
            if (expectedVip == null) {
                assertEquals(0, poolVips.length);
            } else {
                assertEquals(1, poolVips.length);
                assertEquals(expectedVip, poolVips[0]);
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
            dtoWebResource.postAndVerifyStatus(topLevelVipsUri,
                    VendorMediaType.APPLICATION_VIP_JSON, vip2,
                    CONFLICT.getStatusCode());
            verifyNumberOfVips(counter);

            // POST without the load balancer ID and get 400 BAD_REQUEST.
            DtoVip noLoadBalancerIdVip = getStockVip();
            noLoadBalancerIdVip.setLoadBalancerId(null);
            dtoWebResource.postAndVerifyBadRequest(topLevelVipsUri,
                    VendorMediaType.APPLICATION_VIP_JSON,
                    noLoadBalancerIdVip);
            verifyNumberOfVips(counter);

            // POST without the pool ID and get 400 BAD_REQUEST.
            DtoVip noPoolIdVip = getStockVip();
            noPoolIdVip.setPoolId(null);
            dtoWebResource.postAndVerifyBadRequest(topLevelVipsUri,
                    VendorMediaType.APPLICATION_VIP_JSON,
                    noPoolIdVip);
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
            assertEquals(newVip2.isAdminStateUp(),
                    !!newVip2.isAdminStateUp());
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
            DtoLoadBalancer loadBalancer1 = createStockLoadBalancer();
            DtoLoadBalancer loadBalancer2 = createStockLoadBalancer();
            DtoPool pool1 = createStockPool();
            DtoPool pool2 = createStockPool();

            DtoVip vip = postVip(getStockVip(loadBalancer1.getId(), pool1.getId()));
            checkBackrefs(loadBalancer1.getUri(), pool1.getUri(), vip);
            assertEquals(loadBalancer1.getUri(), vip.getLoadBalancer());
            assertEquals(pool1.getUri(), vip.getPool());

            // Switch references to loadBalancer2 and pool2.
            vip.setLoadBalancerId(loadBalancer2.getId());
            vip.setPoolId(pool2.getId());
            vip = updateVip(vip);

            // LoadBalancer1 and pool1 should no longer reference vip.
            checkBackrefs(loadBalancer1.getUri(), pool1.getUri(), null);

            // LoadBalancer2 and pool2 should.
            checkBackrefs(loadBalancer2.getUri(), pool2.getUri(), vip);

            // Vip's references should be updated as well.
            assertEquals(loadBalancer2.getUri(), vip.getLoadBalancer());
            assertEquals(pool2.getUri(), vip.getPool());
        }

        @Test
        public void testDeleteClearsBackrefs() {
            DtoVip vip = createStockVip();
            checkBackrefs(vip.getLoadBalancer(), vip.getPool(), vip);

            deleteVip(vip.getUri());
            checkBackrefs(vip.getLoadBalancer(), vip.getPool(), null);
        }
    }
}

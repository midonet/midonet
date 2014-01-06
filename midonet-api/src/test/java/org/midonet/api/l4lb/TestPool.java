/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.api.l4lb;

import java.net.URI;

import junit.framework.Assert;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.midonet.api.VendorMediaType;
import org.midonet.api.zookeeper.StaticMockDirectory;
import org.midonet.client.dto.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.midonet.api.VendorMediaType.APPLICATION_POOL_COLLECTION_JSON;

@RunWith(Enclosed.class)
public class TestPool {


    public static class TestPoolCrud extends L4LBTestBase {

        @Before
        public void setUp() {
            super.setUp();
        }

        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        private void verifyNumberOfPools(int num) {
            DtoPool[] pools = dtoWebResource.getAndVerifyOk(
                    topLevelPoolsUri,
                    VendorMediaType.APPLICATION_POOL_JSON,
                    DtoPool[].class);
            assertEquals(num, pools.length);
        }

        private void checkBackref(URI healthMonitorUri, DtoPool expectedPool) {
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

        @Test
        public void testCrud() throws Exception {

            // Pools should be empty
            verifyNumberOfPools(0);

            // Post
            DtoPool pool = createStockPool();
            verifyNumberOfPools(1);

            // Post another
            DtoPool pool2 = createStockPool();
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
            DtoHealthMonitor healthMonitor = createStockHealthMonitor();
            DtoPool pool = createStockPool(healthMonitor.getId());

            assertEquals(healthMonitor.getUri(), pool.getHealthMonitor());
            checkBackref(healthMonitor.getUri(), pool);
        }

        @Test
        public void testUpdateUpdatesReferences() {
            DtoHealthMonitor healthMonitor1 = createStockHealthMonitor();
            DtoHealthMonitor healthMonitor2 = createStockHealthMonitor();

            DtoPool pool = createStockPool(healthMonitor1.getId());
            assertEquals(healthMonitor1.getUri(), pool.getHealthMonitor());
            checkBackref(healthMonitor1.getUri(), pool);

            // Switch reference to healthMonitor2.
            pool.setHealthMonitorId(healthMonitor2.getId());
            pool = updatePool(pool);

            // healthMonitor1 should no longer reference pool.
            checkBackref(healthMonitor1.getUri(), null);

            // healthMonitor2 should.
            checkBackref(healthMonitor2.getUri(), pool);

            // Pool's healthMonitor URI should be updated, too.
            assertEquals(healthMonitor2.getUri(), pool.getHealthMonitor());
        }

        @Test
        public void testDeleteClearsBackref() {
            DtoHealthMonitor healthMonitor = createStockHealthMonitor();
            DtoPool pool = createStockPool(healthMonitor.getId());
            checkBackref(healthMonitor.getUri(), pool);

            deletePool(pool.getUri());
            checkBackref(healthMonitor.getUri(), null);
        }
    }
}

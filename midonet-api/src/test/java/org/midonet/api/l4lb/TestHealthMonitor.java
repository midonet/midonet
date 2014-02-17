/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.api.l4lb;

import junit.framework.Assert;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.midonet.api.VendorMediaType;
import org.midonet.api.validation.MessageProperty;
import org.midonet.api.zookeeper.StaticMockDirectory;
import org.midonet.client.dto.*;

import java.net.URI;
import java.util.UUID;

import static javax.ws.rs.core.Response.Status.*;
import static junit.framework.Assert.assertNull;
import static org.junit.Assert.assertEquals;
import static org.midonet.api.VendorMediaType.APPLICATION_HEALTH_MONITOR_COLLECTION_JSON;

@RunWith(Enclosed.class)
public class TestHealthMonitor {

    public static class TestHealthMonitorCrud extends L4LBTestBase {

        @Before
        public void setUp() {
            super.setUp();
        }

        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        private void verifyNumberOfHealthMonitors(int num) {
            DtoHealthMonitor[] healthMonitors = dtoWebResource.getAndVerifyOk(
                    topLevelHealthMonitorsUri,
                    APPLICATION_HEALTH_MONITOR_COLLECTION_JSON,
                    DtoHealthMonitor[].class);
            assertEquals(num, healthMonitors.length);
        }

        @Test
        synchronized public void testCrud() throws Exception {
            int counter = 0;

            // HealthMonitors should be empty
            verifyNumberOfHealthMonitors(counter);

            // POST
            DtoHealthMonitor healthMonitor = createStockHealthMonitor();
            verifyNumberOfHealthMonitors(++counter);

            // POST another one
            DtoHealthMonitor healthMonitor2 = createStockHealthMonitor();
            verifyNumberOfHealthMonitors(++counter);

            // POST with the same ID as the existing resource and get 409
            // CONFLICT.
            dtoWebResource.postAndVerifyStatus(topLevelHealthMonitorsUri,
                    VendorMediaType.APPLICATION_HEALTH_MONITOR_JSON,
                    healthMonitor2,
                    CONFLICT.getStatusCode());
            verifyNumberOfHealthMonitors(counter);

            // Get and check if it is the same as what we POSTed.
            DtoHealthMonitor newHealthMonitor = getHealthMonitor(healthMonitor.getUri());
            // assertPropertiesEqual(newHealthMonitor, healthMonitor);
            Assert.assertEquals(newHealthMonitor, healthMonitor);

            DtoHealthMonitor newHealthMonitor2 = getHealthMonitor(healthMonitor2.getUri());
            // assertPropertiesEqual(newHealthMonitor2, healthMonitor2);
            Assert.assertEquals(newHealthMonitor2, healthMonitor2);

            // PUT with the different property value
            newHealthMonitor.setAdminStateUp(false);
            DtoHealthMonitor updatedHealthMonitor = updateHealthMonitor(newHealthMonitor);
            Assert.assertEquals(updatedHealthMonitor, newHealthMonitor);
            Assert.assertNotSame(updatedHealthMonitor, healthMonitor);

            // Delete
            deleteHealthMonitor(healthMonitor.getUri());
            verifyNumberOfHealthMonitors(--counter);
            deleteHealthMonitor(healthMonitor2.getUri());
            verifyNumberOfHealthMonitors(--counter);
        }

        @Test
        public void testDeleteClearsReferencesFromPools() {
            DtoHealthMonitor hm = createStockHealthMonitor();
            DtoPool pool1 = createStockPool(hm.getId());
            DtoPool pool2 = createStockPool(hm.getId());

            assertEquals(hm.getUri(), pool1.getHealthMonitor());
            assertEquals(hm.getUri(), pool2.getHealthMonitor());

            deleteHealthMonitor(hm.getUri());

            pool1 = getPool(pool1.getUri());
            assertNull(pool1.getHealthMonitorId());
            assertNull(pool1.getHealthMonitor());

            pool2 = getPool(pool2.getUri());
            assertNull(pool2.getHealthMonitorId());
            assertNull(pool2.getHealthMonitor());
        }

        @Test
        public void testUpdateWithRandomHealthMonitorId() throws Exception {
            DtoHealthMonitor hm = createStockHealthMonitor();
            hm.setId(UUID.randomUUID());
            hm.setUri(addIdToUri(topLevelHealthMonitorsUri, hm.getId()));
            DtoError error = dtoWebResource.putAndVerifyError(
                    hm.getUri(),
                    VendorMediaType.APPLICATION_HEALTH_MONITOR_JSON,
                    hm, NOT_FOUND);
            assertErrorMatches(error, MessageProperty.RESOURCE_NOT_FOUND,
                               "health monitor", hm.getId());
        }
    }
}

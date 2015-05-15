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
package org.midonet.api.l4lb.e2e;

import java.util.UUID;

import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;

import org.midonet.cluster.rest_api.VendorMediaType;
import org.midonet.cluster.rest_api.validation.MessageProperty;
import org.midonet.client.dto.DtoError;
import org.midonet.client.dto.DtoHealthMonitor;
import org.midonet.client.dto.l4lb.LBStatus;

import static javax.ws.rs.core.Response.Status.CONFLICT;
import static javax.ws.rs.core.Response.Status.NOT_FOUND;
import static org.junit.Assert.assertEquals;
import static org.midonet.cluster.rest_api.VendorMediaType.APPLICATION_HEALTH_MONITOR_COLLECTION_JSON;
import static org.midonet.cluster.rest_api.VendorMediaType.APPLICATION_HEALTH_MONITOR_JSON;

@RunWith(Enclosed.class)
public class TestHealthMonitor {

    public static class TestHealthMonitorCrud extends L4LBTestBase {

        private void verifyNumberOfHealthMonitors(int num) {
            DtoHealthMonitor[] healthMonitors = dtoResource.getAndVerifyOk(
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
            dtoResource.postAndVerifyStatus(topLevelHealthMonitorsUri,
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
        public void testCreateWithoutType() {
            DtoHealthMonitor hm = getStockHealthMonitor();
            hm.setType(null);
            // `type` property of Health Monitor is mandatory.
            dtoResource.postAndVerifyBadRequest(topLevelHealthMonitorsUri,
                    APPLICATION_HEALTH_MONITOR_JSON, hm);
        }

        @Test
        public void testUpdateWithRandomHealthMonitorId() throws Exception {
            DtoHealthMonitor hm = createStockHealthMonitor();
            hm.setId(UUID.randomUUID());
            hm.setUri(addIdToUri(topLevelHealthMonitorsUri, hm.getId()));
            DtoError error = dtoResource.putAndVerifyError(
                    hm.getUri(),
                    VendorMediaType.APPLICATION_HEALTH_MONITOR_JSON,
                    hm, NOT_FOUND);
            assertErrorMatches(error, MessageProperty.RESOURCE_NOT_FOUND,
                               "health monitor", hm.getId());
        }

        @Test
        public void testCreateHealthMonitorAndStatusDefaultsToActive()
                throws Exception {
            DtoHealthMonitor hm = createStockHealthMonitor();
            assertEquals(LBStatus.ACTIVE, hm.getStatus());

            // Even if the users put values in the `status` property, it should
            // be ignored and `status` should default to UP.
            DtoHealthMonitor hm2 = getStockHealthMonitor();
            hm2.setStatus(null);
            hm2 = postHealthMonitor(hm2);
            assertEquals(LBStatus.ACTIVE, hm2.getStatus());
        }

        @Test
        public void testHealthMonitorCanNotBeChanged()
                throws Exception {
            DtoHealthMonitor hm = createStockHealthMonitor();
            assertEquals(LBStatus.ACTIVE, hm.getStatus());

            hm.setStatus(LBStatus.INACTIVE);
            hm = updateHealthMonitor(hm);
            assertEquals(LBStatus.ACTIVE, hm.getStatus());
        }
    }
}

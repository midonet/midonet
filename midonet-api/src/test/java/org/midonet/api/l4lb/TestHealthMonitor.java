/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.api.l4lb;

import java.net.URI;
import java.util.UUID;

import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;
import junit.framework.Assert;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.midonet.api.VendorMediaType;
import org.midonet.api.rest_api.DtoWebResource;
import org.midonet.api.rest_api.FuncTest;
import org.midonet.api.rest_api.Topology;
import org.midonet.api.zookeeper.StaticMockDirectory;
import org.midonet.client.dto.*;

import static javax.ws.rs.core.Response.Status.*;
import static junit.framework.Assert.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.midonet.api.VendorMediaType.APPLICATION_HEALTH_MONITOR_JSON;

@RunWith(Enclosed.class)
public class TestHealthMonitor {

    public static class TestHealthMonitorCrud extends JerseyTest {

        private DtoWebResource dtoWebResource;
        private Topology topology;
        private URI topLevelHealthMonitorsUri;

        public TestHealthMonitorCrud() {
            super(FuncTest.appDesc);
        }

        @Before
        public void setUp() {

            WebResource resource = resource();
            dtoWebResource = new DtoWebResource(resource);
            topology = new Topology.Builder(dtoWebResource).build();
            DtoApplication app = topology.getApplication();

            // URIs to use for operations
            topLevelHealthMonitorsUri = app.getHealthMonitors();
            assertNotNull(topLevelHealthMonitorsUri);
        }

        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        private void verifyNumberOfHealthMonitors(int num) {
            DtoHealthMonitor[] healthMonitors = dtoWebResource.getAndVerifyOk(
                    topLevelHealthMonitorsUri,
                    VendorMediaType.APPLICATION_HEALTH_MONITOR_JSON,
                    DtoHealthMonitor[].class);
            assertEquals(num, healthMonitors.length);
        }

        private DtoHealthMonitor getHealthMonitor(URI healthMonitorUri) {
            return dtoWebResource.getAndVerifyOk(
                    healthMonitorUri,
                    VendorMediaType.APPLICATION_HEALTH_MONITOR_JSON,
                    DtoHealthMonitor.class);
            }

        private URI postHealthMonitor(DtoHealthMonitor healthMonitor) {
            ClientResponse response = dtoWebResource.postAndVerifyStatus(
                    topLevelHealthMonitorsUri,
                    VendorMediaType.APPLICATION_HEALTH_MONITOR_JSON,
                    healthMonitor,
                    CREATED.getStatusCode());

            return response.getLocation();
        }

        private DtoHealthMonitor updateHealthMonitor(
                DtoHealthMonitor healthMonitor) {
            return dtoWebResource.putAndVerifyNoContent(healthMonitor.getUri(),
                    VendorMediaType.APPLICATION_HEALTH_MONITOR_JSON,
                    healthMonitor,
                    DtoHealthMonitor.class);
        }

        private void deleteHealthMonitor(URI healthMonitorUri) {
            dtoWebResource.deleteAndVerifyNoContent(healthMonitorUri,
                    APPLICATION_HEALTH_MONITOR_JSON);
        }

        private DtoHealthMonitor getStockHealthMonitor() {
            DtoHealthMonitor healthMonitor = new DtoHealthMonitor();
            // NOTE(tfukushima): Populating UUID of the health monitor because
            //   the API can create the resource with the specified UUID,
            //   which is very useful for the identical checks.
            healthMonitor.setId(UUID.randomUUID());
            healthMonitor.setType("TCP");
            healthMonitor.setDelay(5);
            healthMonitor.setTimeout(10);
            healthMonitor.setMaxRetries(10);
            healthMonitor.setAdminStateUp(true);
            healthMonitor.setStatus("ACTIVE");
            return healthMonitor;
        }

        @Test
        synchronized public void testCrud() throws Exception {
            int counter = 0;

            // HealthMonitors should be empty
            verifyNumberOfHealthMonitors(counter);

            // POST
            DtoHealthMonitor healthMonitor = getStockHealthMonitor();
            URI healthMonitorUri = postHealthMonitor(healthMonitor);
            verifyNumberOfHealthMonitors(++counter);

            // POST another one
            DtoHealthMonitor healthMonitor2 = getStockHealthMonitor();
            URI healthMonitorUri2 = postHealthMonitor(healthMonitor2);
            verifyNumberOfHealthMonitors(++counter);

            // POST with the same ID as the existing resource and get 409
            // CONFLICT.
            dtoWebResource.postAndVerifyStatus(topLevelHealthMonitorsUri,
                    VendorMediaType.APPLICATION_HEALTH_MONITOR_JSON,
                    healthMonitor2,
                    CONFLICT.getStatusCode());
            verifyNumberOfHealthMonitors(counter);

            // Get and check if it is the same as what we POSTed.
            DtoHealthMonitor newHealthMonitor = getHealthMonitor(healthMonitorUri);
            // assertPropertiesEqual(newHealthMonitor, healthMonitor);
            Assert.assertEquals(newHealthMonitor, healthMonitor);

            DtoHealthMonitor newHealthMonitor2 = getHealthMonitor(healthMonitorUri2);
            // assertPropertiesEqual(newHealthMonitor2, healthMonitor2);
            Assert.assertEquals(newHealthMonitor2, healthMonitor2);

            // PUT with the different property value
            newHealthMonitor.setAdminStateUp(false);
            DtoHealthMonitor updatedHealthMonitor = updateHealthMonitor(newHealthMonitor);
            Assert.assertEquals(updatedHealthMonitor, newHealthMonitor);
            Assert.assertNotSame(updatedHealthMonitor, healthMonitor);

            // Delete
            deleteHealthMonitor(healthMonitorUri);
            verifyNumberOfHealthMonitors(--counter);
            deleteHealthMonitor(healthMonitorUri2);
            verifyNumberOfHealthMonitors(--counter);
        }

    }
}

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

import static junit.framework.Assert.assertNotNull;
import static org.junit.Assert.assertEquals;
import static org.midonet.api.VendorMediaType.APPLICATION_HEALTH_MONITOR_JSON;

@RunWith(Enclosed.class)
public class TestHealthMonitor {


    public static class TestHealthMonitorCrud extends JerseyTest {

        private DtoWebResource dtoResource;
        private Topology topology;
        private URI healthMonitorsUri;

        public TestHealthMonitorCrud() {
            super(FuncTest.appDesc);
        }

        @Before
        public void setUp() {

            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);
            topology = new Topology.Builder(dtoResource).build();
            DtoApplication app = topology.getApplication();

            // URIs to use for operations
            healthMonitorsUri = app.getHealthMonitors();
            assertNotNull(healthMonitorsUri);
        }

        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        private void verifyNumberOfHealthMonitors(int num) {
            ClientResponse response = resource().uri(healthMonitorsUri)
                    .type(VendorMediaType.APPLICATION_HEALTH_MONITOR_JSON)
                    .get(ClientResponse.class);
            DtoHealthMonitor[] healthMonitors
                    = response.getEntity(DtoHealthMonitor[].class);
            assertEquals(200, response.getStatus());
            assertEquals(num, healthMonitors.length);
        }

        private DtoHealthMonitor getHealthMonitor(URI healthMonitorUri) {
            ClientResponse response = resource().uri(healthMonitorUri)
                    .type(VendorMediaType.APPLICATION_HEALTH_MONITOR_JSON)
                    .get(ClientResponse.class);
            assertEquals(200, response.getStatus());
            return response.getEntity(DtoHealthMonitor.class);
        }

        private URI postHealthMonitor(DtoHealthMonitor healthMonitor) {
            ClientResponse response = resource().uri(healthMonitorsUri)
                    .type(VendorMediaType.APPLICATION_HEALTH_MONITOR_JSON)
                    .post(ClientResponse.class, healthMonitor);
            assertEquals(201, response.getStatus());
            return response.getLocation();
        }

        private void deleteHealthMonitor(URI healthMonitorUri) {
            ClientResponse response = resource().uri(healthMonitorUri)
                    .type(APPLICATION_HEALTH_MONITOR_JSON)
                    .delete(ClientResponse.class);
            assertEquals(204, response.getStatus());
        }

        private DtoHealthMonitor getStockHealthMonitor() {
            DtoHealthMonitor healthMonitor = new DtoHealthMonitor();
            // NOTE(tfukushima): Populating UUID of the health monitor because
            //   the API can create the resource with the specified UUID,
            //   which is very useful for the identical checks.
            healthMonitor.setId(UUID.randomUUID());
            healthMonitor.setAdminStateUp(true);
            healthMonitor.setDelay(5);
            healthMonitor.setMaxRetries(10);
            healthMonitor.setType("TCP");
            healthMonitor.setTimeout(10);
            return healthMonitor;
        }

        @Test
        public void testCrud() throws Exception {

            // HealthMonitors should be empty
            verifyNumberOfHealthMonitors(0);

            // Post
            DtoHealthMonitor healthMonitor = getStockHealthMonitor();
            URI healthMonitorUri = postHealthMonitor(healthMonitor);
            verifyNumberOfHealthMonitors(1);

            // Post another
            DtoHealthMonitor healthMonitor2 = getStockHealthMonitor();
            URI healthMonitorUri2 = postHealthMonitor(healthMonitor2);
            verifyNumberOfHealthMonitors(2);

            // Get and check
            DtoHealthMonitor newHealthMonitor
                    = getHealthMonitor(healthMonitorUri);
            Assert.assertEquals(healthMonitor, newHealthMonitor);
            newHealthMonitor = getHealthMonitor(healthMonitorUri2);
            Assert.assertEquals(newHealthMonitor, healthMonitor2);

            // Delete
            deleteHealthMonitor(healthMonitorUri);
            verifyNumberOfHealthMonitors(1);
            deleteHealthMonitor(healthMonitorUri2);
            verifyNumberOfHealthMonitors(0);
        }

    }
}

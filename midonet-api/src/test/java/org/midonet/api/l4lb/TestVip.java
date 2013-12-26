/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.api.l4lb;

import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static javax.ws.rs.core.Response.Status.*;
import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;

import java.net.URI;
import java.util.UUID;

@RunWith(Enclosed.class)
public class TestVip {
    public static class TestVipCrud extends JerseyTest {
        private final static Logger log = LoggerFactory
                .getLogger(VIP.class);

        private DtoWebResource dtoWebResource;
        private Topology topology;
        private URI VIPS_URI;
        private URI LOAD_BALANCERS_URI;
        private URI POOLS_URI;
        private URI HEALTH_MONITORS_URI;

        public TestVipCrud() {
            super(FuncTest.appDesc);
        }

        @Before
        public void setUp() {
            WebResource resource = resource();
            dtoWebResource = new DtoWebResource(resource);
            topology = new Topology.Builder(dtoWebResource).build();
            DtoApplication app = topology.getApplication();

            // URIs to use for operations
            VIPS_URI = app.getVips();
            LOAD_BALANCERS_URI = app.getLoadBalancers();
            POOLS_URI = app.getPools();
            HEALTH_MONITORS_URI = app.getHealthMonitors();
            assertNotNull(VIPS_URI);
            assertNotNull(LOAD_BALANCERS_URI);
            assertNotNull(POOLS_URI);
            assertNotNull(HEALTH_MONITORS_URI);
        }

        @After
        public void resetDirectory() throws  Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        private void verifyNumberOfVips(int num) {
            ClientResponse response = resource().uri(VIPS_URI)
                    .type(VendorMediaType.APPLICATION_VIP_JSON)
                    .get(ClientResponse.class);
            DtoVip[] vips = response.getEntity(DtoVip[].class);
            assertEquals(OK.getStatusCode(), response.getStatus());
            assertEquals(num, vips.length);
        }

        private DtoLoadBalancer getStockLoadBalancer() {
            DtoLoadBalancer loadBalancer = new DtoLoadBalancer();
            // NOTE(tfukushima): Populating UUID of the load balancer because
            //   the API can create the resource with the specified UUID,
            //   which is very useful for the identity checks.
            loadBalancer.setId(UUID.randomUUID());
            loadBalancer.setAdminStateUp(true);
            return loadBalancer;
        }

        private DtoHealthMonitor getStockHealthMonitor() {
            DtoHealthMonitor healthMonitor = new DtoHealthMonitor();
            // NOTE(tfukushima): Populating UUID of the load balancer because
            //   the API can create the resource with the specified UUID,
            //   which is very useful for the identity checks.
            healthMonitor.setId(UUID.randomUUID());
            healthMonitor.setDelay(5);
            healthMonitor.setTimeout(10);
            healthMonitor.setMaxRetries(10);
            healthMonitor.setAdminStateUp(true);
            healthMonitor.setType("TCP");

            return healthMonitor;
        }

        private DtoPool getStockPool() {
            DtoPool pool = new DtoPool();
            pool.setId(UUID.randomUUID());
            DtoHealthMonitor healthMonitor = getStockHealthMonitor();
            dtoWebResource.postAndVerifyCreated(
                    HEALTH_MONITORS_URI,
                    VendorMediaType.APPLICATION_HEALTH_MONITOR_JSON,
                    healthMonitor,
                    DtoHealthMonitor.class);
            pool.setHealthMonitorId(healthMonitor.getId());
            pool.setLbMethod("ROUND_ROBIN");
            pool.setAdminStateUp(true);

            return pool;
        }

        private DtoVip getStockVip() {
            DtoVip vip = new DtoVip();
            // NOTE(tfukushima): Populating UUID of the load balancer because
            //   the API can create the resource with the specified UUID,
            //   which is very useful for the identical checks.
            vip.setId(UUID.randomUUID());
            // Create a load balancer associated with a VIP.
            DtoLoadBalancer loadBalancer = getStockLoadBalancer();
            loadBalancer = dtoWebResource.postAndVerifyCreated(
                    LOAD_BALANCERS_URI,
                    VendorMediaType.APPLICATION_LOAD_BALANCER_JSON,
                    loadBalancer, DtoLoadBalancer.class);
            vip.setLoadBalancerId(loadBalancer.getId());
            // Create a pool associated with a VIP.
            DtoPool pool = getStockPool();
            pool = dtoWebResource.postAndVerifyCreated(
                    POOLS_URI,
                    VendorMediaType.APPLICATION_POOL_JSON,
                    pool, DtoPool.class);
            vip.setPoolId(pool.getId());
            vip.setAddress("192.168.100.1");
            vip.setProtocolPort(80);
            vip.setSessionPersistence(VIP.VIP_SOURCE_IP);
            vip.setAdminStateUp(true);

            return vip;
        }

        private static void assertPropertiesEqual(DtoVip thisVip, DtoVip thatVip) {
            assertThat("The load_balancer_ids should be the same",
                    thisVip.getLoadBalancerId(),
                    equalTo(thatVip.getLoadBalancerId()));
            assertThat("The pool_ids should be the same",
                    thisVip.getPoolId(),
                    equalTo(thatVip.getPoolId()));
            assertThat("The addresses should be the same",
                    thisVip.getAddress(),
                    equalTo(thatVip.getAddress()));
            assertThat("The protocol_ports should be the same",
                    thisVip.getProtocolPort(),
                    equalTo(thatVip.getProtocolPort()));
            assertThat("The session_persistences should be the same",
                    thisVip.getSessionPersistence(),
                    equalTo(thatVip.getSessionPersistence()));
            assertThat("The admin_state_ups should be the same",
                    thisVip.getAdminStateUp(),
                    equalTo(thatVip.getAdminStateUp()));
        }

        @Test
        public void testCrud() throws Exception {
            int counter = 0;
            // VIPs should be empty
            verifyNumberOfVips(counter);

            // POST
            DtoVip vip = getStockVip();
            ClientResponse response1 = dtoWebResource.postAndVerifyStatus(
                    VIPS_URI, VendorMediaType.APPLICATION_VIP_JSON,
                    vip, CREATED.getStatusCode());
            URI vipUri = response1.getLocation();
            verifyNumberOfVips(++counter);

            // POST another one
            DtoVip vip2 = getStockVip();
            ClientResponse response2 = dtoWebResource.postAndVerifyStatus(
                    VIPS_URI, VendorMediaType.APPLICATION_VIP_JSON,
                    vip2, CREATED.getStatusCode());
            URI vip2Uri = response2.getLocation();
            verifyNumberOfVips(++counter);

            // POST with the same ID as the existing resource and get 409
            // CONFLICT.
            dtoWebResource.postAndVerifyStatus(VIPS_URI,
                    VendorMediaType.APPLICATION_VIP_JSON, vip2,
                    CONFLICT.getStatusCode());
            verifyNumberOfVips(counter);

            // POST without the load balancer ID and get 400 BAD_REQUEST.
            DtoVip noLoadBalancerIdVip = getStockVip();
            noLoadBalancerIdVip.setLoadBalancerId(null);
            dtoWebResource.postAndVerifyBadRequest(VIPS_URI,
                    VendorMediaType.APPLICATION_VIP_JSON,
                    noLoadBalancerIdVip);
            verifyNumberOfVips(counter);

            // POST without the pool ID and get 400 BAD_REQUEST.
            DtoVip noPoolIdVip = getStockVip();
            noPoolIdVip.setPoolId(null);
            dtoWebResource.postAndVerifyBadRequest(VIPS_URI,
                    VendorMediaType.APPLICATION_VIP_JSON,
                    noPoolIdVip);
            verifyNumberOfVips(counter);

            // GET and check if it is the same as what we POSTed.
            DtoVip newVip = dtoWebResource.getAndVerifyOk(vipUri,
                    VendorMediaType.APPLICATION_VIP_JSON, DtoVip.class);
            // It checks the id as well but they're identical because POST
            // accepts populated id and create the VIP with the id.
            assertPropertiesEqual(vip, newVip);

            DtoVip newVip2 = dtoWebResource.getAndVerifyOk(vip2Uri,
                    VendorMediaType.APPLICATION_VIP_JSON, DtoVip.class);
            // It checks the id as well but they're identical because POST
            // accepts populated id and create the VIP with the id.
            assertPropertiesEqual(vip2, newVip2);

            // PUT with the different parameters
            newVip2.setAdminStateUp(!newVip2.getAdminStateUp());
            assertEquals(newVip2.getAdminStateUp(),
                    !!newVip2.getAdminStateUp());
            DtoVip updatedVip2 = dtoWebResource.putAndVerifyNoContent(
                    vip2Uri, VendorMediaType.APPLICATION_VIP_JSON,
                    newVip2, DtoVip.class);
            assertPropertiesEqual(updatedVip2, newVip2);

            // DELETE all created VIPs.
            // FIXME(tfukushima): Replace the following `deleteAndVerifyStatus`
            //   with `deleteAndVerifyNoContent` when the bug is fixed.
            dtoWebResource.deleteAndVerifyStatus(vipUri,
                    VendorMediaType.APPLICATION_VIP_JSON,
                    NO_CONTENT.getStatusCode());
            verifyNumberOfVips(--counter);
            dtoWebResource.deleteAndVerifyStatus(vip2Uri,
                    VendorMediaType.APPLICATION_VIP_JSON,
                    NO_CONTENT.getStatusCode());
            verifyNumberOfVips(--counter);
        }
    }
}

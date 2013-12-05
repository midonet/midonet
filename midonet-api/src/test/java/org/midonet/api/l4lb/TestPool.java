/*
 * Copyright 2013 Midokura PTE LTD.
 */
package org.midonet.api.l4lb;

import java.net.URI;

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
import static org.midonet.api.VendorMediaType.APPLICATION_POOL_JSON;

@RunWith(Enclosed.class)
public class TestPool {


    public static class TestPoolCrud extends JerseyTest {

        private DtoWebResource dtoResource;
        private Topology topology;
        private URI poolsUri;

        public TestPoolCrud() {
            super(FuncTest.appDesc);
        }

        @Before
        public void setUp() {

            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);
            topology = new Topology.Builder(dtoResource).build();
            DtoApplication app = topology.getApplication();

            // URIs to use for operations
            poolsUri = app.getPools();
            assertNotNull(poolsUri);
        }

        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        private void verifyNumberOfPools(int num) {
            ClientResponse response = resource().uri(poolsUri)
                    .type(VendorMediaType.APPLICATION_POOL_JSON)
                    .get(ClientResponse.class);
            DtoPool[] pools = response.getEntity(DtoPool[].class);
            assertEquals(200, response.getStatus());
            assertEquals(num, pools.length);
        }

        private DtoPool getPool(URI poolUri) {
            ClientResponse response = resource().uri(poolUri)
                    .type(VendorMediaType.APPLICATION_POOL_JSON)
                    .get(ClientResponse.class);
            assertEquals(200, response.getStatus());
            return response.getEntity(DtoPool.class);
        }

        private URI postPool(DtoPool pool) {
            ClientResponse response = resource().uri(poolsUri)
                    .type(VendorMediaType.APPLICATION_POOL_JSON)
                    .post(ClientResponse.class, pool);
            assertEquals(201, response.getStatus());
            return response.getLocation();
        }

        private void deletePool(URI poolUri) {
            ClientResponse response = resource().uri(poolUri)
                    .type(APPLICATION_POOL_JSON)
                    .delete(ClientResponse.class);
            assertEquals(204, response.getStatus());
        }

        private DtoPool getStockPool() {
            DtoPool pool = new DtoPool();
            pool.setAdminStateUp(true);
            pool.setDescription("a big ol pool");
            pool.setName("BIGPOOL");
            pool.setProtocol("TCP");
            return pool;
        }

        @Test
        public void testCrud() throws Exception {

            // Pools should be empty
            verifyNumberOfPools(0);

            // Post
            DtoPool pool = getStockPool();
            URI newPoolUri = postPool(pool);
            verifyNumberOfPools(1);

            // Post another
            DtoPool pool2 = getStockPool();
            URI newPoolUri2 = postPool(pool2);
            verifyNumberOfPools(2);

            // Get and check
            DtoPool newPool = getPool(newPoolUri);
            Assert.assertEquals(pool, newPool);
            newPool = getPool(newPoolUri2);
            Assert.assertEquals(newPool, pool2);

            // Delete
            deletePool(newPoolUri);
            verifyNumberOfPools(1);
            deletePool(newPoolUri2);
            verifyNumberOfPools(0);
        }

    }
}

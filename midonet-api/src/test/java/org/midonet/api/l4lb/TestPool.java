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
import static org.midonet.api.VendorMediaType.APPLICATION_POOL_JSON;

@RunWith(Enclosed.class)
public class TestPool {


    public static class TestPoolCrud extends JerseyTest {

        private DtoWebResource dtoWebResource;
        private Topology topology;
        private URI topLevelPoolsUri;

        public TestPoolCrud() {
            super(FuncTest.appDesc);
        }

        @Before
        public void setUp() {

            WebResource resource = resource();
            dtoWebResource = new DtoWebResource(resource);
            topology = new Topology.Builder(dtoWebResource).build();
            DtoApplication app = topology.getApplication();

            // URIs to use for operations
            topLevelPoolsUri = app.getPools();
            assertNotNull(topLevelPoolsUri);
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

        private DtoPool getPool(URI poolUri) {
            ClientResponse response = resource().uri(poolUri)
                    .type(VendorMediaType.APPLICATION_POOL_JSON)
                    .get(ClientResponse.class);
            assertEquals(200, response.getStatus());
            return response.getEntity(DtoPool.class);
        }

        private URI postPool(DtoPool pool) {
            ClientResponse response = resource().uri(topLevelPoolsUri)
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
            // NOTE(tfukushima): Populating UUID of the pool because the API
            //   can create the resource with the specified UUID, which is
            //   very useful for the identical checks.
            pool.setId(UUID.randomUUID());
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

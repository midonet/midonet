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

@RunWith(Enclosed.class)
public class TestPoolMember {


    public static class TestPoolMemberCrud extends JerseyTest {

        private DtoWebResource dtoResource;
        private Topology topology;
        private URI poolMembersUri;

        public TestPoolMemberCrud() {
            super(FuncTest.appDesc);
        }

        @Before
        public void setUp() {

            WebResource resource = resource();
            dtoResource = new DtoWebResource(resource);
            topology = new Topology.Builder(dtoResource).build();
            DtoApplication app = topology.getApplication();

            // URIs to use for operations
            poolMembersUri = app.getPoolMembers();
            assertNotNull(poolMembersUri);
        }

        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        private void verifyNumberOfPoolMembers(int num) {
            ClientResponse response = resource().uri(poolMembersUri)
                    .type(VendorMediaType.APPLICATION_POOL_MEMBER_JSON)
                    .get(ClientResponse.class);
            DtoPoolMember[] poolMembers = response.getEntity(DtoPoolMember[].class);
            assertEquals(200, response.getStatus());
            assertEquals(num, poolMembers.length);
        }

        private DtoPoolMember getPoolMember(URI poolMemberUri) {
            ClientResponse response = resource().uri(poolMemberUri)
                    .type(VendorMediaType.APPLICATION_POOL_MEMBER_JSON)
                    .get(ClientResponse.class);
            assertEquals(200, response.getStatus());
            return response.getEntity(DtoPoolMember.class);
        }

        private URI postPoolMember(DtoPoolMember poolMember) {
            ClientResponse response = resource().uri(poolMembersUri)
                    .type(VendorMediaType.APPLICATION_POOL_MEMBER_JSON)
                    .post(ClientResponse.class, poolMember);
            assertEquals(201, response.getStatus());
            return response.getLocation();
        }

        private void deletePoolMember(URI poolMemberUri) {
            ClientResponse response = resource().uri(poolMemberUri)
                    .type(VendorMediaType.APPLICATION_POOL_MEMBER_JSON)
                    .delete(ClientResponse.class);
            assertEquals(204, response.getStatus());
        }

        private DtoPoolMember getStockPoolMember() {
            DtoPoolMember poolMember = new DtoPoolMember();
            // NOTE(tfukushima): Populating UUID of the pool member because
            //   the API can create the resource with the specified UUID,
            //   which is very useful for the identical checks.
            poolMember.setId(UUID.randomUUID());
            poolMember.setAddress("10.0.0.1");
            poolMember.setProtocolPort(80);
            poolMember.setStatus("UP");
            poolMember.setWeight(100);
            poolMember.setAdminStateUp(true);
            return poolMember;
        }

        @Test
        public void testCrud() throws Exception {

            // Members should be empty
            verifyNumberOfPoolMembers(0);

            // Post
            DtoPoolMember poolMember = getStockPoolMember();
            URI newPoolMemberUri = postPoolMember(poolMember);
            verifyNumberOfPoolMembers(1);

            // Post another
            DtoPoolMember poolMember2 = getStockPoolMember();
            URI newPoolMemberUri2 = postPoolMember(poolMember2);
            verifyNumberOfPoolMembers(2);

            // Get and check
            DtoPoolMember newPoolMember = getPoolMember(newPoolMemberUri);
            Assert.assertEquals(poolMember, newPoolMember);
            newPoolMember = getPoolMember(newPoolMemberUri2);
            Assert.assertEquals(newPoolMember, poolMember2);

            // Delete
            deletePoolMember(newPoolMemberUri);
            verifyNumberOfPoolMembers(1);
            deletePoolMember(newPoolMemberUri2);
            verifyNumberOfPoolMembers(0);
        }

    }
}

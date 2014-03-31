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
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static javax.ws.rs.core.Response.Status.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import static org.midonet.api.validation.MessageProperty.POOL_MEMBER_WEIGHT_NEGATIVE;
import static org.midonet.api.validation.MessageProperty.RESOURCE_EXISTS;
import static org.midonet.api.validation.MessageProperty.RESOURCE_NOT_FOUND;
import static org.midonet.api.VendorMediaType.APPLICATION_POOL_MEMBER_JSON;
import static org.midonet.api.VendorMediaType.APPLICATION_POOL_MEMBER_COLLECTION_JSON;

@RunWith(Enclosed.class)
public class TestPoolMember {


    public static class TestPoolMemberCrud extends L4LBTestBase {

        private DtoLoadBalancer loadBalancer;
        private DtoPool pool;

        @Before
        public void setUp() {
            super.setUp();
            loadBalancer = createStockLoadBalancer();
            pool = createStockPool(loadBalancer.getId());
        }

        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        private void verifyNumberOfPoolMembers(int num) {
            DtoPoolMember[] poolMembers = getPoolMembers(topLevelPoolMembersUri);
            assertEquals(num, poolMembers.length);
        }

        private void checkBackrefs(URI poolUri, DtoPoolMember... expectedMembers) {
            DtoPool pool = getPool(poolUri);
            DtoPoolMember[] actualMembers = getPoolMembers(pool.getPoolMembers());
            assertEquals(expectedMembers.length, actualMembers.length);

            List<DtoPoolMember> actualList = Arrays.asList(actualMembers);
            for (DtoPoolMember expectedMember : expectedMembers)
                assertTrue(actualList.contains(expectedMember));
        }

        @Test
        public void testCrud() throws Exception {

            // Members should be empty
            verifyNumberOfPoolMembers(0);

            // Post
            DtoPoolMember poolMember = createStockPoolMember(pool.getId());
            verifyNumberOfPoolMembers(1);

            // Post another
            DtoPoolMember poolMember2 = createStockPoolMember(pool.getId());
            verifyNumberOfPoolMembers(2);

            // Get and check
            DtoPoolMember newPoolMember = getPoolMember(poolMember.getUri());
            Assert.assertEquals(poolMember, newPoolMember);
            newPoolMember = getPoolMember(poolMember2.getUri());
            Assert.assertEquals(poolMember2, newPoolMember);

            // Delete
            deletePoolMember(poolMember.getUri());
            verifyNumberOfPoolMembers(1);
            deletePoolMember(poolMember2.getUri());
            verifyNumberOfPoolMembers(0);
        }

        @Test
        public void assertCreateAddsReferences() {
            checkBackrefs(pool.getUri()); // No members.

            DtoPoolMember member1 = createStockPoolMember(pool.getId());
            assertEquals(pool.getUri(), member1.getPool());
            checkBackrefs(pool.getUri(), member1);

            DtoPoolMember member2 = createStockPoolMember(pool.getId());
            assertEquals(pool.getUri(), member2.getPool());
            checkBackrefs(pool.getUri(), member1, member2);
        }

        @Test
        public void assertUpdateUpdatesReferences() {
            DtoPool pool2 = createStockPool(loadBalancer.getId());

            DtoPoolMember member1 = createStockPoolMember(pool.getId());
            DtoPoolMember member2 = createStockPoolMember(pool.getId());
            checkBackrefs(pool.getUri(), member1, member2);

            // Switch member2 to pool2.
            member2.setPoolId(pool2.getId());
            member2 = updatePoolMember(member2);
            assertEquals(pool2.getUri(), member2.getPool());
            checkBackrefs(pool.getUri(), member1);
            checkBackrefs(pool2.getUri(), member2);

            // Switch member1 to pool2.
            member1.setPoolId(pool2.getId());
            member1 = updatePoolMember(member1);
            assertEquals(pool2.getUri(), member1.getPool());
            checkBackrefs(pool.getUri()); // No members
            checkBackrefs(pool2.getUri(), member1, member2);
        }

        @Test
        public void testDeletePoolMemberRemovesReferencesFromPool() {
            DtoPoolMember member1 = createStockPoolMember(pool.getId());
            DtoPoolMember member2 = createStockPoolMember(pool.getId());
            checkBackrefs(pool.getUri(), member1, member2);

            deletePoolMember(member1.getUri());
            checkBackrefs(pool.getUri(), member2);

            deletePoolMember(member2.getUri());
            checkBackrefs(pool.getUri()); // No members.
        }

        @Test
        public void testDeletePoolRemovesReferencesFromPoolMembers() {
            DtoPoolMember member1 = createStockPoolMember(pool.getId());
            assertEquals(pool.getId(), member1.getPoolId());
            assertEquals(pool.getUri(), member1.getPool());

            DtoPoolMember member2 = createStockPoolMember(pool.getId());
            assertEquals(pool.getId(), member2.getPoolId());
            assertEquals(pool.getUri(), member2.getPool());

            deletePool(pool.getUri());
            // Strongly associated resources are deleted by cascading.
            dtoWebResource.getAndVerifyNotFound(member1.getUri(),
                    VendorMediaType.APPLICATION_POOL_MEMBER_JSON);
            dtoWebResource.getAndVerifyNotFound(member2.getUri(),
                    VendorMediaType.APPLICATION_POOL_MEMBER_JSON);
        }

        @Test
        public void testCreateWithDuplicateId() {
            DtoPoolMember member1 = createStockPoolMember(pool.getId());
            DtoPoolMember member2 = getStockPoolMember(pool.getId());
            member2.setId(member1.getId());
            DtoError error = dtoWebResource.postAndVerifyError(
                    topLevelPoolMembersUri, APPLICATION_POOL_MEMBER_JSON,
                    member2, CONFLICT);
            assertErrorMatches(
                    error, RESOURCE_EXISTS, "pool member", member1.getId());
        }

        @Test
        public void testCreateWithNullPoolId() {
            DtoPoolMember member = getStockPoolMember();
            DtoError error = dtoWebResource.postAndVerifyBadRequest(
                    topLevelPoolMembersUri,
                    APPLICATION_POOL_MEMBER_JSON, member);
            assertErrorMatchesPropMsg(error, "poolId", "may not be null");
        }

        @Test
        public void testCreateWithBadPoolId() {
            DtoPoolMember member = getStockPoolMember(UUID.randomUUID());
            DtoError error = dtoWebResource.postAndVerifyError(
                    topLevelPoolMembersUri, APPLICATION_POOL_MEMBER_JSON,
                    member, BAD_REQUEST);
            assertErrorMatches(
                    error, RESOURCE_NOT_FOUND, "pool", member.getPoolId());
        }

        @Test
        public void testCreateWithNegativeWeight() {
            DtoPoolMember member = getStockPoolMember(pool.getId());
            member.setWeight(-1);
            DtoError error = dtoWebResource.postAndVerifyBadRequest(
                    topLevelPoolMembersUri, APPLICATION_POOL_MEMBER_JSON, member);
            assertErrorMatchesPropMsg(error, "weight", "must be greater than or equal to 0");
        }

        @Test
        public void testCreateWithBadIpAddress() {
            DtoPoolMember member = getStockPoolMember(pool.getId());
            member.setAddress("10.10.10.999");
            DtoError error = dtoWebResource.postAndVerifyBadRequest(
                    topLevelPoolMembersUri,
                    APPLICATION_POOL_MEMBER_JSON, member);
            assertErrorMatchesPropMsg(error,
                    "address", "is not a valid IP address");
        }

        @Test
        public void testCreateWithNegativePort() {
            DtoPoolMember member = getStockPoolMember(pool.getId());
            member.setProtocolPort(-1);
            DtoError error = dtoWebResource.postAndVerifyBadRequest(
                    topLevelPoolMembersUri,
                    APPLICATION_POOL_MEMBER_JSON, member);
            assertErrorMatchesPropMsg(error,
                    "protocolPort", "must be greater than or equal to 0");
        }

        @Test
        public void testCreateWithPortGreaterThan65535() {
            DtoPoolMember member = getStockPoolMember(pool.getId());
            member.setProtocolPort(65536);
            DtoError error = dtoWebResource.postAndVerifyBadRequest(
                    topLevelPoolMembersUri,
                    APPLICATION_POOL_MEMBER_JSON, member);
            assertErrorMatchesPropMsg(error,
                    "protocolPort", "must be less than or equal to 65535");
        }

        @Test
        public void testGetWithBadPoolMemberId() throws Exception {
            UUID id = UUID.randomUUID();
            DtoError error = dtoWebResource.getAndVerifyNotFound(
                    addIdToUri(topLevelPoolMembersUri, id),
                    APPLICATION_POOL_MEMBER_JSON);
            assertErrorMatches(error, RESOURCE_NOT_FOUND, "pool member", id);
        }

        @Test
        public void testUpdateWithBadPoolMemberId() throws Exception {
            DtoPoolMember member = createStockPoolMember(pool.getId());
            member.setId(UUID.randomUUID());
            member.setUri(addIdToUri(topLevelPoolMembersUri, member.getId()));
            DtoError error = dtoWebResource.putAndVerifyNotFound(
                    member.getUri(), APPLICATION_POOL_MEMBER_JSON, member);
            assertErrorMatches(error, RESOURCE_NOT_FOUND,
                               "pool member", member.getId());
        }

        @Test
        public void testUpdateWithBadPoolId() {
            DtoPoolMember member = createStockPoolMember(pool.getId());
            member.setPoolId(UUID.randomUUID());
            DtoError error = dtoWebResource.putAndVerifyBadRequest(
                    member.getUri(), APPLICATION_POOL_MEMBER_JSON, member);
            assertErrorMatches(error, RESOURCE_NOT_FOUND, "pool", member.getPoolId());
        }

        @Test
        public void testUpdateWithNegativeWeight() {
            DtoPoolMember member = createStockPoolMember(pool.getId());
            member.setWeight(-1);
            DtoError error = dtoWebResource.putAndVerifyBadRequest(
                    member.getUri(), APPLICATION_POOL_MEMBER_JSON, member);
            assertErrorMatchesPropMsg(error, "weight", "must be greater than or equal to 0");
        }

        @Test
        public void testUpdateWeight() {
            DtoPoolMember member = getStockPoolMember(pool.getId());
            member.setWeight(0); // Should default to 1.
            member = postPoolMember(member);
            assertEquals(1, member.getWeight());

            // Update to 5.
            member.setWeight(5);
            updatePoolMember(member);
            member = getPoolMember(member.getUri());
            assertEquals(5, member.getWeight());

            // Update with the default (1).
            member.setWeight(0);
            updatePoolMember(member);
            member = getPoolMember(member.getUri());
            assertEquals(1, member.getWeight());
        }

        @Test
        public void testUpdateWithBadIpAddress() {
            DtoPoolMember member = createStockPoolMember(pool.getId());
            member.setAddress("10.10.10.999");
            DtoError error = dtoWebResource.putAndVerifyBadRequest(
                    member.getUri(), APPLICATION_POOL_MEMBER_JSON, member);
            assertErrorMatchesPropMsg(error,
                    "address", "is not a valid IP address");
        }

        @Test
        public void testUpdateWithNegativePort() {
            DtoPoolMember member = createStockPoolMember(pool.getId());
            member.setProtocolPort(-1);
            DtoError error = dtoWebResource.putAndVerifyBadRequest(
                    member.getUri(), APPLICATION_POOL_MEMBER_JSON, member);
            assertErrorMatchesPropMsg(error,
                    "protocolPort", "must be greater than or equal to 0");
        }

        @Test
        public void testUpdateWithPortGreaterThan65535() {
            DtoPoolMember member = createStockPoolMember(pool.getId());
            member.setProtocolPort(65536);
            DtoError error = dtoWebResource.putAndVerifyBadRequest(
                    member.getUri(), APPLICATION_POOL_MEMBER_JSON, member);
            assertErrorMatchesPropMsg(error,
                    "protocolPort", "must be less than or equal to 65535");
        }

        @Test
        public void testDeleteWithBadPoolMemberId() throws Exception {
            // Succeeds because delete is idempotent.
            deletePoolMember(addIdToUri(topLevelPoolMembersUri, UUID.randomUUID()));
        }

        @Test
        public void testCreatePoolMemberAndStatusDefaulsToUp()
                throws Exception {
            DtoPoolMember member = createStockPoolMember(pool.getId());
            assertEquals(DtoPoolMember.PoolMemberStatus.UP,
                    member.getStatus());

            // Even if the users put values in the `status` property, it should
            // be ignored and `status` should default to UP.
            DtoPoolMember member2 = getStockPoolMember(pool.getId());
            member2.setStatus(null);
            member2 = postPoolMember(member2);
            assertEquals(DtoPoolMember.PoolMemberStatus.UP,
                    member2.getStatus());
        }

        @Test
        public void testPoolMemberStatusCanNotBeChanged()
                throws Exception {
            DtoPoolMember member = createStockPoolMember(pool.getId());
            assertEquals(DtoPoolMember.PoolMemberStatus.UP,
                    member.getStatus());

            member.setStatus(DtoPoolMember.PoolMemberStatus.DOWN);
            member = updatePoolMember(member);
            assertEquals(DtoPoolMember.PoolMemberStatus.UP,
                    member.getStatus());
        }
    }
}

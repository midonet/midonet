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
package org.midonet.cluster.rest_api.e2e;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;

import org.midonet.client.dto.DtoError;
import org.midonet.client.dto.DtoLoadBalancer;
import org.midonet.client.dto.DtoPool;
import org.midonet.client.dto.DtoPoolMember;
import org.midonet.client.dto.l4lb.LBStatus;
import org.midonet.cluster.services.rest_api.MidonetMediaTypes;

import static javax.ws.rs.core.Response.Status.CONFLICT;
import static javax.ws.rs.core.Response.Status.NOT_FOUND;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.midonet.cluster.rest_api.validation.MessageProperty.IP_ADDR_INVALID;
import static org.midonet.cluster.rest_api.validation.MessageProperty.MAX_VALUE;
import static org.midonet.cluster.rest_api.validation.MessageProperty.MIN_VALUE;
import static org.midonet.cluster.rest_api.validation.MessageProperty.NON_NULL;
import static org.midonet.cluster.rest_api.validation.MessageProperty.RESOURCE_EXISTS;
import static org.midonet.cluster.rest_api.validation.MessageProperty.RESOURCE_NOT_FOUND;
import static org.midonet.cluster.services.rest_api.MidonetMediaTypes.APPLICATION_POOL_MEMBER_JSON;

@RunWith(Enclosed.class)
public class TestPoolMember {

    public static class TestPoolMemberCrud extends L4LBTestBase {

        private DtoLoadBalancer loadBalancer;
        private DtoPool pool;
        private DtoPoolMember member;
        private int poolMemberCounter;

        private static final int WEIGHT_MIN_VALUE = 1;
        private static final int PROTOCOL_PORT_MIN_VALUE = 0;
        private static final int PROTOCOL_PORT_MAX_VALUE = 65535;

        @Before
        public void setUp() throws Exception {
            super.setUp();
            loadBalancer = createStockLoadBalancer();
            pool = createStockPool(loadBalancer.getId());
            poolMemberCounter = 0;
            // Members should be empty
            verifyNumberOfPoolMembers(poolMemberCounter);
            checkBackrefs(pool.getUri());  // No members.
            member = createStockPoolMember(pool.getId());
            poolMemberCounter++;
            verifyNumberOfPoolMembers(poolMemberCounter);
            assertEquals(pool.getUri(), member.getPool());
            checkBackrefs(pool.getUri(), member);
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
            // Post another
            DtoPoolMember poolMember2 = createStockPoolMember(pool.getId());
            poolMemberCounter++;
            verifyNumberOfPoolMembers(poolMemberCounter);

            // Get and check
            DtoPoolMember newPoolMember = getPoolMember(member.getUri());
            Assert.assertEquals(member, newPoolMember);
            newPoolMember = getPoolMember(poolMember2.getUri());
            Assert.assertEquals(poolMember2, newPoolMember);

            // Delete
            deletePoolMember(member.getUri());
            poolMemberCounter--;
            verifyNumberOfPoolMembers(poolMemberCounter);
            deletePoolMember(poolMember2.getUri());
            poolMemberCounter--;
            verifyNumberOfPoolMembers(poolMemberCounter);
        }

        @Test
        public void assertCreateAddsReferences() {
            DtoPoolMember member2 = createStockPoolMember(pool.getId());
            assertEquals(pool.getUri(), member2.getPool());
            checkBackrefs(pool.getUri(), member, member2);
        }

        @Test
        public void assertUpdateUpdatesReferences() {
            DtoPool pool2 = createStockPool(loadBalancer.getId());

            DtoPoolMember member2 = createStockPoolMember(pool.getId());
            checkBackrefs(pool.getUri(), member, member2);

            // Switch member2 to pool2.
            member2.setPoolId(pool2.getId());
            member2 = updatePoolMember(member2);
            assertEquals(pool2.getUri(), member2.getPool());
            checkBackrefs(pool.getUri(), member);
            checkBackrefs(pool2.getUri(), member2);

            // Switch member to pool2.
            member.setPoolId(pool2.getId());
            member = updatePoolMember(member);
            assertEquals(pool2.getUri(), member.getPool());
            checkBackrefs(pool.getUri()); // No members
            checkBackrefs(pool2.getUri(), member, member2);
        }

        @Test
        public void testDeletePoolMemberRemovesReferencesFromPool() {
            DtoPoolMember member2 = createStockPoolMember(pool.getId());
            checkBackrefs(pool.getUri(), member, member2);

            deletePoolMember(member.getUri());
            checkBackrefs(pool.getUri(), member2);

            deletePoolMember(member2.getUri());
            checkBackrefs(pool.getUri()); // No members.
        }

        @Test
        public void testDeletePoolRemovesReferencesFromPoolMembers() {
            assertEquals(pool.getId(), member.getPoolId());
            assertEquals(pool.getUri(), member.getPool());

            DtoPoolMember member2 = createStockPoolMember(pool.getId());
            assertEquals(pool.getId(), member2.getPoolId());
            assertEquals(pool.getUri(), member2.getPool());

            /* This ensures that the deletePool call below succeeds. */
            activatePoolHMMappingStatus(pool.getId());

            deletePool(pool.getUri());
            // Strongly associated resources are deleted by cascading.
            dtoResource.getAndVerifyNotFound(member.getUri(),
                    MidonetMediaTypes.APPLICATION_POOL_MEMBER_JSON());
            dtoResource.getAndVerifyNotFound(member2.getUri(),
                    MidonetMediaTypes.APPLICATION_POOL_MEMBER_JSON());
        }

        @Test
        public void testCreateWithDuplicateId() {
            DtoPoolMember member2 = getStockPoolMember(pool.getId());
            member2.setId(member.getId());
            DtoError error = dtoResource.postAndVerifyError(
                    topLevelPoolMembersUri, APPLICATION_POOL_MEMBER_JSON(),
                    member2, CONFLICT);
            assertErrorMatches(
                    error, RESOURCE_EXISTS, "PoolMember", member.getId());
        }

        @Test
        public void testCreateWithNullPoolId() {
            DtoPoolMember member = getStockPoolMember();
            DtoError error = dtoResource.postAndVerifyBadRequest(
                    topLevelPoolMembersUri,
                    APPLICATION_POOL_MEMBER_JSON(), member);
            assertErrorMatchesPropMsg(error, "poolId", NON_NULL);
        }

        @Test
        public void testCreateWithBadPoolId() {
            DtoPoolMember member = getStockPoolMember(UUID.randomUUID());
            DtoError error = dtoResource.postAndVerifyError(
                    topLevelPoolMembersUri, APPLICATION_POOL_MEMBER_JSON(),
                    member, NOT_FOUND);
            assertErrorMatches(error, RESOURCE_NOT_FOUND, "Pool",
                               member.getPoolId());
        }

        @Test
        public void testCreateWithNegativeWeight() {
            DtoPoolMember member = getStockPoolMember(pool.getId());
            member.setWeight(-1);
            DtoError error = dtoResource.postAndVerifyBadRequest(
                    topLevelPoolMembersUri, APPLICATION_POOL_MEMBER_JSON(), member);
            assertErrorMatchesPropMsg(
                    error, "weight", MIN_VALUE, WEIGHT_MIN_VALUE);

        }

        @Test
        public void testCreateWithNegativeWeightUnderPool() {
            DtoPoolMember member = getStockPoolMember(pool.getId());
            member.setWeight(-1);
            DtoError error = dtoResource.postAndVerifyBadRequest(
                    pool.getPoolMembers(),
                    APPLICATION_POOL_MEMBER_JSON(),
                    member);
            assertErrorMatchesPropMsg(
                    error, "weight", MIN_VALUE, WEIGHT_MIN_VALUE);
        }

        @Test
        public void testCreateWithBadIpAddress() {
            DtoPoolMember member = getStockPoolMember(pool.getId());
            member.setAddress("10.10.10.999");
            DtoError error = dtoResource.postAndVerifyBadRequest(
                    topLevelPoolMembersUri,
                    APPLICATION_POOL_MEMBER_JSON(), member);
            assertErrorMatchesPropMsg(error, "address", IP_ADDR_INVALID);
        }

        @Test
        public void testCreateWithNegativePort() {
            DtoPoolMember member = getStockPoolMember(pool.getId());
            member.setProtocolPort(-1);
            DtoError error = dtoResource.postAndVerifyBadRequest(
                    topLevelPoolMembersUri,
                    APPLICATION_POOL_MEMBER_JSON(), member);
            assertErrorMatchesPropMsg(
                    error, "protocolPort", MIN_VALUE, PROTOCOL_PORT_MIN_VALUE);
        }

        @Test
        public void testCreateWithPortGreaterThan65535() {
            DtoPoolMember member = getStockPoolMember(pool.getId());
            member.setProtocolPort(65536);
            DtoError error = dtoResource.postAndVerifyBadRequest(
                    topLevelPoolMembersUri,
                    APPLICATION_POOL_MEMBER_JSON(), member);
            assertErrorMatchesPropMsg(
                    error, "protocolPort", MAX_VALUE, PROTOCOL_PORT_MAX_VALUE);
        }

        @Test
        public void testGetWithBadPoolMemberId() throws Exception {
            UUID id = UUID.randomUUID();
            DtoError error = dtoResource.getAndVerifyNotFound(
                    addIdToUri(topLevelPoolMembersUri, id),
                    APPLICATION_POOL_MEMBER_JSON());
            assertErrorMatches(error, RESOURCE_NOT_FOUND, "PoolMember", id);
        }

        @Test
        public void testUpdateWithBadPoolMemberId() throws Exception {
            member.setId(UUID.randomUUID());
            member.setUri(addIdToUri(topLevelPoolMembersUri, member.getId()));
            DtoError error = dtoResource.putAndVerifyNotFound(
                member.getUri(), APPLICATION_POOL_MEMBER_JSON(), member);
            assertErrorMatches(error, RESOURCE_NOT_FOUND,
                               "PoolMember", member.getId());
        }

        @Test
        public void testUpdateWithBadPoolId() {
            member.setPoolId(UUID.randomUUID());
            DtoError error = dtoResource.putAndVerifyNotFound(
                    member.getUri(), APPLICATION_POOL_MEMBER_JSON(), member);
            assertErrorMatches(
                    error, RESOURCE_NOT_FOUND, "Pool", member.getPoolId());
        }

        @Test
        public void testUpdateWithNegativeWeight() {
            member.setWeight(-1);
            DtoError error = dtoResource.putAndVerifyBadRequest(
                    member.getUri(), APPLICATION_POOL_MEMBER_JSON(), member);
            assertErrorMatchesPropMsg(
                    error, "weight", MIN_VALUE, WEIGHT_MIN_VALUE);
        }

        @Test
        public void testUpdateWeight() {
            DtoPoolMember member = getStockPoolMember(pool.getId());
            member = postPoolMember(member);
            assertEquals(1, member.getWeight());

            // Update to 5.
            member.setWeight(5);
            updatePoolMember(member);
            member = getPoolMember(member.getUri());
            assertEquals(5, member.getWeight());
        }

        @Test
        public void testUpdateWeightUnderPool() {
            DtoPoolMember member = getStockPoolMember(pool.getId());
            member.setWeight(1); // Should default to 1.
            member = postPoolMember(pool.getPoolMembers(), member);
            assertEquals(1, member.getWeight());
        }

        @Test
        public void testUpdateWithInvalidWeightUnderPool() {
            DtoPoolMember member = getStockPoolMember(pool.getId());
            member.setWeight(0); // Should default to 1.
            DtoError error = dtoResource.postAndVerifyBadRequest(
                    pool.getPoolMembers(),
                    APPLICATION_POOL_MEMBER_JSON(),
                    member);
            assertErrorMatchesPropMsg(
                    error, "weight", MIN_VALUE, WEIGHT_MIN_VALUE);
        }

        @Test
        public void testUpdateWithBadIpAddress() {
            member.setAddress("10.10.10.999");
            DtoError error = dtoResource.putAndVerifyBadRequest(
                    member.getUri(), APPLICATION_POOL_MEMBER_JSON(), member);
            assertErrorMatchesPropMsg(error, "address", IP_ADDR_INVALID);
        }

        @Test
        public void testUpdateWithNegativePort() {
            member.setProtocolPort(-1);
            DtoError error = dtoResource.putAndVerifyBadRequest(
                    member.getUri(), APPLICATION_POOL_MEMBER_JSON(), member);
            assertErrorMatchesPropMsg(
                    error, "protocolPort", MIN_VALUE, PROTOCOL_PORT_MIN_VALUE);
        }

        @Test
        public void testUpdateWithPortGreaterThan65535() {
            member.setProtocolPort(65536);
            DtoError error = dtoResource.putAndVerifyBadRequest(
                    member.getUri(), APPLICATION_POOL_MEMBER_JSON(), member);
            assertErrorMatchesPropMsg(
                    error, "protocolPort", MAX_VALUE, PROTOCOL_PORT_MAX_VALUE);
        }

        @Test
        public void testDeleteWithBadPoolMemberId() throws Exception {
            // Succeeds because delete is idempotent.
            deletePoolMember(addIdToUri(topLevelPoolMembersUri, UUID.randomUUID()));
        }

        @Test
        public void testCreatePoolMemberAndStatusDefaultsToActive()
                throws Exception {
            assertEquals(LBStatus.ACTIVE, member.getStatus());

            // Even if the users put values in the `status` property, it should
            // be ignored and `status` should default to UP.
            DtoPoolMember member2 = getStockPoolMember(pool.getId());
            member2.setStatus(null);
            member2 = postPoolMember(member2);
            assertEquals(LBStatus.ACTIVE, member2.getStatus());
        }

        @Test
        public void testPoolMemberStatusCanNotBeChanged()
                throws Exception {
            assertEquals(LBStatus.ACTIVE, member.getStatus());

            member.setStatus(LBStatus.INACTIVE);
            member = updatePoolMember(member);
            assertEquals(LBStatus.ACTIVE, member.getStatus());
        }

        @Test
        public void testPoolMemberUnsettableProperties()
                throws Exception {
            String oldAddress = member.getAddress();
            member.setAddress("192.168.100.100");
            member = updatePoolMember(member);
            assertEquals(member.getAddress(), oldAddress);

            int oldPort = member.getProtocolPort();
            member.setProtocolPort(8080);
            member = updatePoolMember(member);
            assertEquals(member.getProtocolPort(), oldPort);
        }
    }
}

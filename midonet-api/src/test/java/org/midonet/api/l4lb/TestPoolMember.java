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
import org.midonet.api.zookeeper.StaticMockDirectory;
import org.midonet.client.dto.*;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(Enclosed.class)
public class TestPoolMember {


    public static class TestPoolMemberCrud extends L4LBTestBase {

        @Before
        public void setUp() {
            super.setUp();
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
            DtoPoolMember poolMember = createStockPoolMember();
            verifyNumberOfPoolMembers(1);

            // Post another
            DtoPoolMember poolMember2 = createStockPoolMember();
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
            DtoPool pool = createStockPool();
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
            DtoPool pool1 = createStockPool();
            DtoPool pool2 = createStockPool();

            DtoPoolMember member1 = createStockPoolMember(pool1.getId());
            DtoPoolMember member2 = createStockPoolMember(pool1.getId());
            checkBackrefs(pool1.getUri(), member1, member2);

            // Switch member2 to pool2.
            member2.setPoolId(pool2.getId());
            member2 = updatePoolMember(member2);
            assertEquals(pool2.getUri(), member2.getPool());
            checkBackrefs(pool1.getUri(), member1);
            checkBackrefs(pool2.getUri(), member2);

            // Switch member1 to pool2.
            member1.setPoolId(pool2.getId());
            member1 = updatePoolMember(member1);
            assertEquals(pool2.getUri(), member1.getPool());
            checkBackrefs(pool1.getUri()); // No members
            checkBackrefs(pool2.getUri(), member1, member2);
        }

        @Test
        public void testDeletePoolMemberRemovesReferencesFromPool() {
            DtoPool pool = createStockPool();
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
            DtoPool pool = createStockPool();
            DtoPoolMember member1 = createStockPoolMember(pool.getId());
            assertEquals(pool.getId(), member1.getPoolId());
            assertEquals(pool.getUri(), member1.getPool());

            DtoPoolMember member2 = createStockPoolMember(pool.getId());
            assertEquals(pool.getId(), member2.getPoolId());
            assertEquals(pool.getUri(), member2.getPool());

            deletePool(pool.getUri());

            member1 = getPoolMember(member1.getUri());
            assertNull(member1.getPoolId());
            assertNull(member1.getPool());

            member2 = getPoolMember(member2.getUri());
            assertNull(member2.getPoolId());
            assertNull(member2.getPool());
        }
    }
}

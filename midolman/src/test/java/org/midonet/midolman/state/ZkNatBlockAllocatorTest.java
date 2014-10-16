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

package org.midonet.midolman.state;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.apache.curator.test.TestingServer;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;

import org.midonet.packets.IPv4Addr;
import org.midonet.util.eventloop.CallingThreadReactor;
import org.midonet.util.functors.Callback;

import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.either;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.number.OrderingComparison.greaterThanOrEqualTo;
import static org.hamcrest.number.OrderingComparison.lessThanOrEqualTo;

public class ZkNatBlockAllocatorTest {

    private TestingServer server;
    private ZkConnection zk;
    private ZkNatBlockAllocator allocator;
    private PathBuilder paths;

    @Before
    public void setup() throws Exception {
        server = new TestingServer(true);
        zk = new ZkConnection(server.getConnectString(), Integer.MAX_VALUE, null);
        zk.open();
        paths = new PathBuilder("/midolman");
        allocator = new ZkNatBlockAllocator(zk, paths,  new CallingThreadReactor());

        zk.getZooKeeper().create(paths.getBasePath(), null,
                                 Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        zk.getZooKeeper().create(paths.getNatPath(), null,
                                 Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
    }

    @After
    public void teardown() throws Exception {
        zk.close();
        server.close();
    }

    static class NatBlockResult implements Callback<NatBlock, Exception> {
        private NatBlock result;
        private Exception exception;
        private CountDownLatch latch = new CountDownLatch(1);

        public NatBlock getResult() throws Exception {
            if (exception != null)
                throw exception;
            return result;
        }

        public void await() throws InterruptedException {
            latch.await();
        }

        @Override
        public void onSuccess(NatBlock data) {
            result = data;
            latch.countDown();
        }

        @Override
        public void onTimeout() { }

        @Override
        public void onError(Exception e) {
            exception = e;
            latch.countDown();
        }
    }

    @Test
    public void testPathsAreCreatedCorrectly() throws Exception {
        IPv4Addr ip = IPv4Addr.random();
        UUID device = UUID.randomUUID();
        NatBlockResult res = new NatBlockResult();
        allocator.allocateBlockInRange(new NatRange(device, ip, 0, 0xFFFF), res);
        res.await();

        String devicePath = paths.getNatDevicePath(device);
        Stat s = zk.getZooKeeper().exists(devicePath, false);
        assertThat(s, is(notNullValue()));
        assertThat(s.getNumChildren(), is(1));

        String ipPath = paths.getNatIpPath(device, ip);
        s = zk.getZooKeeper().exists(ipPath, false);
        assertThat(s.getNumChildren(), is(NatBlock.TOTAL_BLOCKS));

        int owned = 0;
        for (int i = 0; i < NatBlock.TOTAL_BLOCKS; ++i) {
            String blockPath = paths.getNatBlockPath(device, ip, i);
            s = zk.getZooKeeper().exists(blockPath, false);
            assertThat(s, is(notNullValue()));

            String ownershipPath = paths.getNatBlockOwnershipPath(device, ip, i);
            s = zk.getZooKeeper().exists(ownershipPath, false);
            if (s != null)
                owned += 1;
        }

        assertThat(owned, is(1));
    }

    // Utility functions that synchronize the test thread with the operations
    // on the embedded Zookeeper.
    private NatBlock allocateBlock(NatRange natRange) throws Exception {
        return allocateBlock(natRange, allocator);
    }

    private NatBlock allocateBlock(NatRange natRange,
                                   NatBlockAllocator allocator) throws Exception {
        NatBlockResult res = new NatBlockResult();
        allocator.allocateBlockInRange(natRange, res);
        res.await();
        return res.getResult();
    }

    private void freeBlock(NatBlock natBlock) throws Exception {
        String path = paths.getNatBlockOwnershipPath(
            natBlock.deviceId, natBlock.ip, natBlock.blockIndex);
        final CountDownLatch latch = new CountDownLatch(1);
        zk.getZooKeeper().exists(path, new Watcher() {
            @Override
            public void process(WatchedEvent event) {
                latch.countDown();
            }
        });
        allocator.freeBlock(natBlock);
        latch.await();
    }

    @Test
    public void testSimpleAllocateBlocks() throws Exception {
        IPv4Addr ip = IPv4Addr.random();
        UUID device = UUID.randomUUID();
        NatRange request = new NatRange(device, ip, 0, 0xFFFF);

        NatBlock result = allocateBlock(request);
        for (int i = 0; i < NatBlock.TOTAL_BLOCKS; ++i) {
            String ownershipPath = paths.getNatBlockOwnershipPath(device, ip, i);
            Stat s = zk.getZooKeeper().exists(ownershipPath, false);
            if (s != null)
                assertThat(i, is(result.blockIndex));
        }

        NatBlock secondResult = allocateBlock(request);
        int owned = 0;
        for (int i = 0; i < NatBlock.TOTAL_BLOCKS; ++i) {
            String ownershipPath = paths.getNatBlockOwnershipPath(device, ip, i);
            Stat s = zk.getZooKeeper().exists(ownershipPath, false);
            if (s != null) {
                assertThat(i, either(is(result.blockIndex))
                                     .or(is(secondResult.blockIndex)));
                owned += 1;
            }
        }

        assertThat(owned, is(2));
    }

    @Test
    public void testFreeBlock() throws Exception {
        IPv4Addr ip = IPv4Addr.random();
        UUID device = UUID.randomUUID();
        NatRange request = new NatRange(device, ip, 0, 1);

        allocateBlock(request);
        String path = paths.getNatBlockOwnershipPath(device, ip, 0);
        Stat s = zk.getZooKeeper().exists(path, false);
        assertThat(s, is(notNullValue()));

        freeBlock(new NatBlock(device, ip, 0));
        s = zk.getZooKeeper().exists(path, false);
        assertThat(s, is(nullValue()));
    }

    @Test
    public void testBlocksAllocatedAndFreedWithinRange() throws Exception {
        IPv4Addr ip = IPv4Addr.random();
        UUID device = UUID.randomUUID();
        // 65 is block 2 and 757 is block 11
        NatRange request = new NatRange(device, ip, 65, 757);

        Set<NatBlock> results = new HashSet<>();
        NatBlock result;
        while ((result = allocateBlock(request)) != NatBlock.NO_BLOCK) {
            assertThat(result.tpPortStart,
                       allOf(greaterThanOrEqualTo(64), lessThanOrEqualTo(704)));
            assertThat(result.tpPortEnd,
                       allOf(greaterThanOrEqualTo(127), lessThanOrEqualTo(767)));
            results.add(result);
        }

        assertThat(results.size(), is(11));

        int startBlock = request.tpPortStart / NatBlock.BLOCK_SIZE;
        int endBlock = request.tpPortEnd / NatBlock.BLOCK_SIZE;
        for (int i = startBlock; i <= endBlock - startBlock; ++i) {
            freeBlock(new NatBlock(device, ip, i));
            String path = paths.getNatBlockOwnershipPath(device, ip, i);
            Stat s = zk.getZooKeeper().exists(path, false);
            assertThat(s, is(nullValue()));
        }
    }

    @Test
    public void testAbandonedBlocksAreReclaimed() throws Exception {
        IPv4Addr ip = IPv4Addr.random();
        UUID device = UUID.randomUUID();
        NatRange request = new NatRange(device, ip, 0, 1);

        ZkConnection otherZk = new ZkConnection(
                        server.getConnectString(), Integer.MAX_VALUE, null);
        otherZk.open();
        ZkNatBlockAllocator otherAllocator = new ZkNatBlockAllocator(
                        otherZk, paths, new CallingThreadReactor());

        NatBlock result = allocateBlock(request, otherAllocator);
        assertThat(result.tpPortStart, is(0));
        assertThat(result.tpPortEnd, is(63));

        result = allocateBlock(request);
        assertThat(result, is(NatBlock.NO_BLOCK));

        otherZk.close();

        result = allocateBlock(request);
        assertThat(result.tpPortStart, is(0));
        assertThat(result.tpPortEnd, is(63));
    }

    @Test
    public void testAllocateLruBlock() throws Exception {
        IPv4Addr ip = IPv4Addr.random();
        UUID device = UUID.randomUUID();
        NatRange request = new NatRange(device, ip, 128, 247);

        ZkConnection[] conns = new ZkConnection[2];
        NatBlock[] results = new NatBlock[2];
        for (int i = 0; i < 2; ++i) {
            ZkConnection otherZk = new ZkConnection(
                    server.getConnectString(), Integer.MAX_VALUE, null);
            conns[i] = otherZk;
            otherZk.open();
            ZkNatBlockAllocator otherAllocator = new ZkNatBlockAllocator(
                    otherZk, paths, new CallingThreadReactor());

            results[i] = allocateBlock(request, otherAllocator);
        }

        conns[1].close();
        conns[0].close();

        NatBlock result = allocateBlock(request);
        assertThat(result.tpPortStart, is(results[1].tpPortStart));
        assertThat(result.tpPortEnd, is(results[1].tpPortEnd));

        result = allocateBlock(request);
        assertThat(result.tpPortStart, is(results[0].tpPortStart));
        assertThat(result.tpPortEnd, is(results[0].tpPortEnd));
    }
}

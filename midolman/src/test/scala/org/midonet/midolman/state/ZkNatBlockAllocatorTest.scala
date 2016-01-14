/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.midolman.state

import java.util.UUID
import java.util.concurrent.{TimeUnit, CountDownLatch}

import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration._

import com.google.common.util.concurrent.MoreExecutors
import org.apache.curator.framework.{CuratorFramework, CuratorFrameworkFactory}
import org.apache.zookeeper.{WatchedEvent, Watcher}
import org.junit.runner.RunWith
import org.scalatest.{OneInstancePerTest, Matchers, FeatureSpecLike}
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.util.CuratorTestFramework
import org.midonet.midolman.state.NatBlockAllocator.NoFreeNatBlocksException
import org.midonet.packets.IPv4Addr
import org.midonet.util.MockUnixClock

@RunWith(classOf[JUnitRunner])
class ZkNatBlockAllocatorTest extends FeatureSpecLike
                              with CuratorTestFramework
                              with Matchers
                              with OneInstancePerTest {

    private var clock: MockUnixClock = null
    private var allocator: ZkNatBlockAllocator = null
    private var recycler: ZkNatBlockRecycler = null

    override def setup(): Unit = {
        clock = new MockUnixClock
        allocator = new ZkNatBlockAllocator(curator, clock)
        recycler = new ZkNatBlockRecycler(
            curator, MoreExecutors.sameThreadExecutor(), clock)
    }

    override def teardown(): Unit =
        curator.delete()
               .deletingChildrenIfNeeded()
               .forPath(ZkNatBlockAllocator.natPath)

    scenario("Paths are created correctly") {
        val ip = IPv4Addr.random
        val device = UUID.randomUUID
        allocateBlock(new NatRange(device, ip, 0, 0xFFFF))

        val devicePath = ZkNatBlockAllocator.natDevicePath(device)
        var s = curator.checkExists().forPath(devicePath)
        s.getNumChildren should be (1)

        val ipPath = ZkNatBlockAllocator.natIpPath(device, ip)
        s = curator.checkExists().forPath(ipPath)
        s.getNumChildren should be (1)

        var owned = 0
        var created = 0
        var i = 0
        while (i < NatBlock.TOTAL_BLOCKS) {
            val blockPath = ZkNatBlockAllocator.blockPath(device, ip, i)
            var s = curator.checkExists().forPath(blockPath)
            if (s ne null) {
                created += 1
            }
            val ownershipPath = ZkNatBlockAllocator.ownershipPath(device, ip, i)
            s = curator.checkExists().forPath(ownershipPath)
            if (s ne null) {
                owned += 1
            }
            i += 1
        }
        owned should be (1)
        created should be (owned)
    }

    scenario ("Allocates blocks") {
        val ip = IPv4Addr.random
        val device = UUID.randomUUID
        val request = new NatRange(device, ip, 0, 0xFFFF)

        val firstResult = allocateBlock(request)
        var i = 0
        while (i < NatBlock.TOTAL_BLOCKS) {
            val ownershipPath = ZkNatBlockAllocator.ownershipPath(device, ip, i)
            val s = curator.checkExists().forPath(ownershipPath)
            if (s ne null) {
                i should be (firstResult.blockIndex)
            }
            i += 1
        }

        val secondResult = allocateBlock(request)

        var owned = 0
        i = 0
        while (i < NatBlock.TOTAL_BLOCKS) {
            val ownershipPath = ZkNatBlockAllocator.ownershipPath(device, ip, i)
            val s = curator.checkExists().forPath(ownershipPath)
            if (s ne null) {
                i should (be (firstResult.blockIndex) or be (secondResult.blockIndex))
                owned += 1
            }
            i += 1
        }

        owned should be (2)
    }

    scenario ("Frees blocks") {
        val ip = IPv4Addr.random
        val device = UUID.randomUUID
        val request = new NatRange(device, ip, 0, 1)

        allocateBlock(request)
        val ownershipPath = ZkNatBlockAllocator.ownershipPath(device, ip, 0)
        curator.checkExists().forPath(ownershipPath) should not be null

        freeBlock(new NatBlock(device, ip, 0))
        curator.checkExists().forPath(ownershipPath) should be (null)
    }

    scenario ("Blocks are allocated and freed within range") {
        val ip = IPv4Addr.random
        val device = UUID.randomUUID
        // 65 is block 2 and 757 is block 11
        val request = new NatRange(device, ip, 65, 757)
        val results = new mutable.HashSet[NatBlock]()
        try {
            while (true) {
                val result = allocateBlock(request)
                result.tpPortStart should (be >= 64 and be <= 704)
                result.tpPortEnd should (be >= 127 and be <= 767)
                results.add(result)
            }
        } catch { case NoFreeNatBlocksException => }

        results.size should be (11)

        val startBlock = request.tpPortStart / NatBlock.BLOCK_SIZE
        val endBlock = request.tpPortEnd / NatBlock.BLOCK_SIZE
        var i = startBlock
        while (i < endBlock) {
            freeBlock(new NatBlock(device, ip, i))
            val ownershipPath = ZkNatBlockAllocator.ownershipPath(device, ip, i)
            curator.checkExists().forPath(ownershipPath) should be (null)
            i += 1
        }
    }

    scenario ("Abandoned blocks are reclaimed") {
        val ip = IPv4Addr.random
        val device = UUID.randomUUID
        val request = new NatRange(device, ip, 0, 1)

        val otherCurator = CuratorFrameworkFactory.newClient(
            zk.getConnectString, sessionTimeoutMs, cnxnTimeoutMs, retryPolicy)
        otherCurator.start()
        val otherAllocator = new ZkNatBlockAllocator(otherCurator, clock)

        var result = allocateBlock(request, otherAllocator)
        result.tpPortStart should be (0)
        result.tpPortEnd should be (63)

        intercept[NoFreeNatBlocksException.type] {
            allocateBlock(request)
        }

        otherCurator.close()

        result = allocateBlock(request)
        result.tpPortStart should be (0)
        result.tpPortEnd should be (63)
    }

    scenario ("Allocates LRU block") {
        val ip = IPv4Addr.random
        val device = UUID.randomUUID
        val request = new NatRange(device, ip, 128, 247)

        val conns = new Array[CuratorFramework](2)
        val results = new Array[NatBlock](2)
        for (i <- 0 until 2) {
            val otherCurator = CuratorFrameworkFactory.newClient(
            zk.getConnectString, sessionTimeoutMs, cnxnTimeoutMs, retryPolicy)
            otherCurator.start()
            conns(i) = otherCurator
            val otherAllocator = new ZkNatBlockAllocator(otherCurator, clock)
            results(i) = allocateBlock(request, otherAllocator)
        }

        conns(1).close()
        conns(0).close()

        var result = allocateBlock(request)
        result.tpPortStart should be (results(1).tpPortStart)
        result.tpPortEnd should  be (results(1).tpPortEnd)

        result = allocateBlock(request)
        result.tpPortStart should be (results(0).tpPortStart)
        result.tpPortEnd should  be (results(0).tpPortEnd)
    }

    scenario ("Blocks are GCed") {
        val device = UUID.randomUUID
        val ip = IPv4Addr.random

        allocateAndFree(device, ip)

        clock.time = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1) + 1
        val blocks = Await.result(recycler.recycle(), 3 minutes)
        blocks should be (1)
        val path = ZkNatBlockAllocator.natIpPath(device, ip)
        curator.checkExists().forPath(path) should be (null)
    }

    scenario ("Router entry is GCed") {
        val device = UUID.randomUUID
        val ip = IPv4Addr.random

        allocateAndFree(device, ip)

        clock.time = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1) + 1
        var blocks = Await.result(recycler.recycle(), 3 minutes)
        blocks should be (1)
        val ipPath = ZkNatBlockAllocator.natIpPath(device, ip)
        curator.checkExists().forPath(ipPath) should be (null)

        val devPath = ZkNatBlockAllocator.natDevicePath(device)
        curator.checkExists().forPath(devPath) should not be null

        clock.time = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1) + 1
        blocks = Await.result(recycler.recycle(), 3 minutes)
        blocks should be (0)
        curator.checkExists().forPath(devPath) should be (null)
    }

    scenario ("Blocks for multiple IPs are GCed") {
        val device = UUID.randomUUID
        val ip = IPv4Addr.random
        val otherIp = IPv4Addr.random

        allocateAndFree(device, ip)
        allocateAndFree(device, otherIp)

        clock.time = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1) + 1
        val blocks = Await.result(recycler.recycle(), 3 minutes)
        blocks should be (2)
        var ipPath = ZkNatBlockAllocator.natIpPath(device, ip)
        curator.checkExists().forPath(ipPath) should be (null)
        ipPath = ZkNatBlockAllocator.natIpPath(device, otherIp)
        curator.checkExists().forPath(ipPath) should be (null)
    }

    scenario ("Blocks for multiple devices are GCed") {
        val device = UUID.randomUUID
        val otherDevice = UUID.randomUUID
        val ip = IPv4Addr.random
        val otherIp = IPv4Addr.random

        allocateAndFree(device, ip)
        allocateAndFree(device, otherIp)
        allocateAndFree(otherDevice, ip)
        allocateAndFree(otherDevice, otherIp)

        clock.time = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1) + 1
        val blocks = Await.result(recycler.recycle(), 3 minutes)
        blocks should be (4)
        var ipPath = ZkNatBlockAllocator.natIpPath(device, ip)
        curator.checkExists().forPath(ipPath) should be (null)
        ipPath = ZkNatBlockAllocator.natIpPath(device, otherIp)
        curator.checkExists().forPath(ipPath) should be (null)
        ipPath = ZkNatBlockAllocator.natIpPath(otherDevice, ip)
        curator.checkExists().forPath(ipPath) should be (null)
        ipPath = ZkNatBlockAllocator.natIpPath(otherDevice, otherIp)
        curator.checkExists().forPath(ipPath) should be (null)
    }

    scenario ("Blocks are not GCed if one is taken") {
        val device = UUID.randomUUID
        val ip = IPv4Addr.random

        allocateBlock(new NatRange(device, ip, 0, 1))

        clock.time = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1) + 1
        val blocks = Await.result(recycler.recycle(), 3 minutes)
        blocks should be (0)
        val ipPath = ZkNatBlockAllocator.natIpPath(device, ip)
        curator.checkExists().forPath(ipPath) should not be null
    }

    private def allocateBlock(natRange: NatRange,
                              allocator: ZkNatBlockAllocator = allocator) =
        Await.result(allocator.allocateBlockInRange(natRange), 3 minutes)

    private def freeBlock(natBlock: NatBlock) = {
        val ownershipPath = ZkNatBlockAllocator.ownershipPath(
            natBlock.deviceId, natBlock.ip, natBlock.blockIndex)
        val latch = new CountDownLatch(1)
        curator.checkExists().usingWatcher(new Watcher() {
            override def process(event: WatchedEvent): Unit =
                latch.countDown()
        }).forPath(ownershipPath)
        allocator.freeBlock(natBlock)
        latch.await(3, TimeUnit.MINUTES) should be (true)
    }

    def allocateAndFree(device: UUID, ip: IPv4Addr): Unit = {
        val request = new NatRange(device, ip, 0, 1)
        val result = allocateBlock(request)
        freeBlock(result)
    }
}

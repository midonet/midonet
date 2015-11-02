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

import java.util.{ArrayList, UUID}
import java.util.concurrent.{ThreadLocalRandom, TimeUnit, Executors}

import org.apache.curator.framework.api.transaction.CuratorTransactionFinal

import scala.concurrent.{ExecutionContext, Promise, Future}

import com.typesafe.scalalogging.Logger
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.api.{CuratorEvent, BackgroundCallback}
import org.apache.zookeeper.KeeperException.Code
import org.apache.zookeeper.{CreateMode, KeeperException}
import org.slf4j.LoggerFactory

import org.midonet.packets.IPv4Addr
import org.midonet.util.UnixClock
import org.midonet.util.concurrent.{ExecutionContextOps, NamedThreadFactory}

object ZkNatBlockAllocator {
    val natPath = "/nat"

    final def natDevicePath(deviceId: UUID) =
        natPath + "/" + deviceId

    final def natIpPath(deviceId: UUID, ip: IPv4Addr) =
        natDevicePath(deviceId) + "/" + ip

    final def blockPath(deviceId: UUID, ip: IPv4Addr, blockIdx: Int) =
        natIpPath(deviceId, ip) + "/" + blockIdx

    final def ownershipPath(deviceId: UUID, ip: IPv4Addr, blockIdx: Int) =
        blockPath(deviceId, ip, blockIdx) + "/taken"

    val backgroundCallbackToFuture = new BackgroundCallback {
        override def processResult(client: CuratorFramework,
                                   event: CuratorEvent): Unit = {
            val p = event.getContext.asInstanceOf[Promise[CuratorEvent]]
            if (event.getResultCode == KeeperException.Code.OK.intValue()) {
                p.trySuccess(event)
            } else {
                val ex = KeeperException.create(
                    Code.get(event.getResultCode), event.getPath)
                p.tryFailure(ex)
            }
        }
    }
}

/**
 * This class uses ZooKeeper to allocate NAT blocks adhering to the following
 * requirements:
 * - NAT blocks have a fixed size;
 * - NAT blocks are scoped by device and associated with a given IP;
 * - A block is randomly chosen from the set of unused NAT blocks;
 * - If there are no unused blocks, we choose the least recently used free one
 * (having been freed either explicitly or because its owner host went down).
 *
 * Refer to the documentation for details on the algorithm.
 */
class ZkNatBlockAllocator(
        zk: CuratorFramework,
        clock: UnixClock) extends NatBlockAllocator {
    import ZkNatBlockAllocator._

    private val log = Logger(LoggerFactory.getLogger(
        "org.midonet.state.nat-block-allocator"))
    private implicit val ec = ExecutionContext.callingThread

    private val executor = Executors.newScheduledThreadPool(1,
        new NamedThreadFactory("nat-block-allocator", isDaemon = true))

    private val recycler = new ZkNatBlockRecycler(zk, executor, clock)
    private val recycleTask = new Runnable() {
        override def run(): Unit = {
            recycler.recycle()
            executor.schedule(this, 5, TimeUnit.HOURS)
        }
    }

    {
        val factor = ThreadLocalRandom.current.nextInt(5) + 1
        executor.schedule(recycleTask, factor, TimeUnit.HOURS)
    }

    override def allocateBlockInRange(natRange: NatRange): Future[NatBlock] = {
        val startBlock = natRange.tpPortStart / NatBlock.BLOCK_SIZE
        val endBlock = natRange.tpPortEnd / NatBlock.BLOCK_SIZE
        val blocks = (startBlock to endBlock).toIndexedSeq
        val blockPaths = blocks map (blockPath(natRange.deviceId, natRange.ip, _))
        Future.traverse(blockPaths)(fetchBlock) flatMap { results =>
            val block = chooseBlock(results, blocks)
            if (block >= 0) {
                claimBlock(block, natRange)
            } else {
                Future.failed(NatBlockAllocator.NoFreeNatBlocksException)
            }
        } recoverWith {
            case ex: KeeperException.NodeExistsException =>
                // We raced with another node and lost. Retry.
                allocateBlockInRange(natRange)
            case ex: KeeperException.NoNodeException =>
                createBlocksStructure(blockPaths)
                allocateBlockInRange(natRange)
        }
    }

    override def freeBlock(natBlock: NatBlock): Unit = {
        log.debug(s"Freeing $natBlock")
        val p = ownershipPath(natBlock.deviceId, natBlock.ip, natBlock.blockIndex)
        zk.delete().guaranteed().inBackground().forPath(p)
    }

    private def chooseBlock(
            results: IndexedSeq[CuratorEvent],
            blocks: IndexedSeq[Int]): Int = {
        val virginBlocks = new ArrayList[Integer]
        var lruBlock = -1
        var lruBlockZxid = Long.MaxValue
        var i = 0
        while (i < results.size) {
            val stat = results(i).getStat
            if (stat.getNumChildren == 0) {
                // Pzxid is the (undocumented) zxid of the last modified child
                // and Czxid is the zxid at node creation time
                val pzxid = stat.getPzxid
                if (pzxid == stat.getCzxid) {
                    virginBlocks.add(blocks(i))
                } else if (pzxid < lruBlockZxid) {
                    lruBlockZxid = pzxid
                    lruBlock = blocks(i)
                }
            }
            i += 1
        }
        if (virginBlocks.size > 0) {
            val block = ThreadLocalRandom.current.nextInt(0, virginBlocks.size)
            virginBlocks.get(block)
        } else {
            lruBlock
        }
    }

    private def fetchBlock(path: String): Future[CuratorEvent] = {
        val getPromise = Promise[CuratorEvent]()
        zk.getData()
          .inBackground(backgroundCallbackToFuture, getPromise, executor)
          .forPath(path)
        getPromise.future
    }

    private def createBlocksStructure(paths: Seq[String]): Unit = try {
        // Create any missing parents
        zk.create().creatingParentsIfNeeded().forPath(paths.head)
        // Create other blocks
        if (paths.tail.nonEmpty) {
            paths.tail.foldLeft(zk.inTransaction)((acc, p) => acc.create().forPath(p).and())
                .asInstanceOf[CuratorTransactionFinal]
                .commit()
        }
    } catch { case ignored: KeeperException.NodeExistsException => }

    private def claimBlock(block: Int, natRange: NatRange): Future[NatBlock] = {
        log.debug(s"Trying to claim block $block for $natRange")
        val p = Promise[CuratorEvent]()
        zk.create()
          .withMode(CreateMode.EPHEMERAL)
          .inBackground(backgroundCallbackToFuture, p, executor)
          .forPath(ownershipPath(natRange.deviceId, natRange.ip, block))
        p.future map { _ =>
            log.debug(s"Claimed block $block for $natRange")
            new NatBlock(natRange.deviceId, natRange.ip, block)
        }
    }
}

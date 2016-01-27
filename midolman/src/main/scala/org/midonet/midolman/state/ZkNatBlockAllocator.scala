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
import java.util.concurrent.{ThreadLocalRandom, TimeUnit, Executors}

import scala.concurrent.{ExecutionContext, Promise, Future}
import scala.util.Random

import com.google.inject.Inject
import com.google.inject.name.Named
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
class ZkNatBlockAllocator @Inject()(
        @Named("GPA_CURATOR") zk: CuratorFramework,
        clock: UnixClock) extends NatBlockAllocator {
    import ZkNatBlockAllocator._

    private val log = Logger(LoggerFactory.getLogger(
        "org.midonet.state.nat-block-allocator"))
    private implicit val ec = ExecutionContext.callingThread

    private val executor = Executors.newScheduledThreadPool(1,
        new NamedThreadFactory("nat-block-allocator"))

    private val recycler = new ZkNatBlockRecycler(zk, executor, clock)
    private val recycleTask = new Runnable() {
        override def run(): Unit = {
            recycler.recycle() onSuccess {
                case recycledBlocks if recycledBlocks > 0 =>
                    log.info(s"Recycled $recycledBlocks blocks")
            }
            executor.schedule(this, 5, TimeUnit.HOURS)
        }
    }

    {
        val factor = ThreadLocalRandom.current.nextInt(5) + 1
        executor.schedule(recycleTask, factor, TimeUnit.HOURS)
    }

    override def allocateBlockInRange(natRange: NatRange): Future[NatBlock] = {
        val ipPath = natIpPath(natRange.deviceId, natRange.ip)
        val startBlock = natRange.tpPortStart / NatBlock.BLOCK_SIZE
        val endBlock = natRange.tpPortEnd / NatBlock.BLOCK_SIZE
        val blocks = Random.shuffle(startBlock to endBlock).toIndexedSeq
        fetchChildren(ipPath) flatMap { results =>
            val used = results.getChildren
            blocks.find(b => !used.contains(b.toString)) match {
                case Some(virginBlock) =>
                    claimBlock(virginBlock, natRange)
                case None =>
                    val blockPaths = blocks map (blockPath(natRange.deviceId, natRange.ip, _))
                    Future.traverse(blockPaths)(fetchBlock) flatMap { results =>
                        val block = chooseLruBlock(results, blocks)
                        if (block >= 0) {
                            claimBlock(block, natRange)
                        } else {
                            Future.failed(NatBlockAllocator.NoFreeNatBlocksException)
                        }
                    }
            }
        } recoverWith {
            case ex: KeeperException.NodeExistsException =>
                // We raced with another node and lost. Retry.
                allocateBlockInRange(natRange)
            case ex: KeeperException.NoNodeException =>
                createStructure(ipPath)
                allocateBlockInRange(natRange)
        }
    }

    override def freeBlock(natBlock: NatBlock): Unit = {
        log.debug(s"Freeing $natBlock")
        val p = ownershipPath(natBlock.deviceId, natBlock.ip, natBlock.blockIndex)
        zk.delete().guaranteed().inBackground().forPath(p)
    }

    private def chooseLruBlock(
            results: IndexedSeq[CuratorEvent],
            blocks: IndexedSeq[Int]): Int = {
        var lruBlock = -1
        var lruBlockZxid = Long.MaxValue
        var i = 0
        while (i < results.size) {
            val stat = results(i).getStat
            if (stat.getNumChildren == 0) {
                // Pzxid is the (undocumented) zxid of the last modified child
                val pzxid = stat.getPzxid
                if (pzxid < lruBlockZxid) {
                    lruBlockZxid = pzxid
                    lruBlock = blocks(i)
                }
            }
            i += 1
        }
        lruBlock
    }

    private def fetchBlock(path: String): Future[CuratorEvent] = {
        val getPromise = Promise[CuratorEvent]()
        zk.getData
          .inBackground(backgroundCallbackToFuture, getPromise, executor)
          .forPath(path)
        getPromise.future
    }

    private def fetchChildren(path: String): Future[CuratorEvent] = {
        val getPromise = Promise[CuratorEvent]()
        zk.getChildren()
          .inBackground(backgroundCallbackToFuture, getPromise, executor)
          .forPath(path)
        getPromise.future
    }

    private def createStructure(path: String): Unit = try {
        zk.create().creatingParentsIfNeeded().forPath(path)
    } catch { case ignored: KeeperException.NodeExistsException => }

    private def claimBlock(block: Int, natRange: NatRange): Future[NatBlock] = {
        log.debug(s"Trying to claim block $block for $natRange")
        val p = Promise[CuratorEvent]()
        zk.create()
          .creatingParentsIfNeeded()
          .withMode(CreateMode.EPHEMERAL)
          .inBackground(backgroundCallbackToFuture, p, executor)
          .forPath(ownershipPath(natRange.deviceId, natRange.ip, block))
        p.future map { _ =>
            log.debug(s"Claimed block $block for $natRange")
            new NatBlock(natRange.deviceId, natRange.ip, block)
        }
    }
}

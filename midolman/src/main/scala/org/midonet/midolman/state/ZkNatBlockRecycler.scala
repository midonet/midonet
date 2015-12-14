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

import java.util.Collections
import java.util.concurrent.{TimeUnit, Executor}

import scala.collection.JavaConversions._
import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, Promise, Future}
import scala.util.control.NonFatal

import com.typesafe.scalalogging.Logger
import org.apache.curator.framework.api.transaction.CuratorTransaction
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.api.{CuratorEvent, BackgroundCallback}
import org.apache.zookeeper.data.Stat
import org.apache.zookeeper.KeeperException
import org.apache.zookeeper.KeeperException.Code
import org.slf4j.LoggerFactory

import org.midonet.util.UnixClock
import org.midonet.util.concurrent.ExecutionContextOps

object ZkNatBlockRecycler {
    private val ONE_DAY: Long = TimeUnit.DAYS.toMillis(1)
}

/**
 * Recycles unused blocks belonging to a particular (device, ip) tuple.
 * It only recycles iff all the blocks under a particular ip are unused.
 *
 * Note that a very rare race condition can occur:
 * 1) a port migrates, along with connections using some NAT bindings with
 * ports from blocks belonging to the host from which the block is migrating
 * 2) a recycle operation happens and the block containing the NAT binding is
 * freed, along with all other blocks under that IP (so no host was using them)
 * 3) another host grabs that block by randomly choosing it over all the blocks
 * for that IP
 * 4) a connection for the same dst ip is created and the host simulating that
 * connection randomly chooses the same port as an existing, migrated connection.
 *
 * We shuffle the list of nodes we obtain from ZK to guarantee that if there's
 * ever a problematic element, othe NAT blocks in the ZK directory structure
 * will eventually get cleaned up.
 */
class ZkNatBlockRecycler(
        zk: CuratorFramework,
        executor: Executor,
        clock: UnixClock) {
    import ZkNatBlockAllocator._
    import ZkNatBlockRecycler._

    private val log = Logger(LoggerFactory.getLogger(
        "org.midonet.state.nat-block-allocator"))
    private implicit val ex = ExecutionContext.callingThread
    private val bytes = Array[Byte](1)

    /**
     * Recycles unneeded nodes in the NAT leasing ZK directory structure.
     * Only one recycle operation is carried out in a 24h period.
     * @return A future holding the number of blocks recycled.
     */
    def recycle(): Future[Int] = {
        val p = Promise[Int]()
        zk.getData
          .inBackground(tryStartRecycling, p, executor)
          .forPath(natPath)
        p.future
    }

    private val tryStartRecycling = new BackgroundCallback {
        override def processResult(client: CuratorFramework,
                                   event: CuratorEvent): Unit = {
            val p = event.getContext.asInstanceOf[Promise[Int]]
            if (event.getResultCode == KeeperException.Code.OK.intValue()) {
                if (clock.time - event.getStat.getMtime > ONE_DAY) {
                    zk.setData()
                      .withVersion(event.getStat.getVersion)
                      .inBackground(isGarbageman, p, executor)
                      .forPath(natPath, bytes)
                } else {
                    log.debug("Skipping NAT block recycling: too soon after last operation")
                    p.success(0)
                }
            } else if (event.getResultCode != KeeperException.Code.NONODE.intValue()) {
                fail(event, p)
            }
        }
    }

    private val isGarbageman = new BackgroundCallback {
        override def processResult(client: CuratorFramework,
                                   event: CuratorEvent): Unit = {
            val p = event.getContext.asInstanceOf[Promise[Int]]
            if (event.getResultCode == KeeperException.Code.OK.intValue()) {
                log.info("Recycling NAT blocks")
                zk.getChildren
                  .inBackground(recycleTopLevel, event.getContext, executor)
                  .forPath(natPath)
            } else if (event.getResultCode == KeeperException.Code.BADVERSION.intValue()) {
                log.info("Stopping: recycle already being performed by another host")
                p success 0
            } else {
                fail(event, p)
            }
        }
    }

    private val recycleTopLevel = new BackgroundCallback {
        override def processResult(client: CuratorFramework,
                                   event: CuratorEvent): Unit = {
            val p = event.getContext.asInstanceOf[Promise[Int]]
            if (event.getResultCode == KeeperException.Code.OK.intValue()) {
                var ps = ListBuffer[Future[Int]]()
                val children = event.getChildren
                Collections.shuffle(children)
                val it = children.iterator()
                while (it.hasNext) {
                    val p = Promise[Int]()
                    val path = event.getPath + "/" + it.next()
                    zk.getChildren
                      .inBackground(recycleDevice, p, executor)
                      .forPath(path)
                    ps += p.future
                }
                p.completeWith(Future.sequence(ps).map(_.sum))
            } else {
                fail(event, p)
            }
        }
    }

    private val recycleDevice = new BackgroundCallback {
        override def processResult(client: CuratorFramework,
                                   event: CuratorEvent): Unit = {
            val p = event.getContext.asInstanceOf[Promise[Int]]
            if (event.getResultCode == KeeperException.Code.OK.intValue()) {
                if (event.getChildren.size() == 0) {
                    zk.delete()
                      .withVersion(event.getStat.getVersion)
                      .inBackground()
                      .forPath(event.getPath)
                    p success 0
                } else {
                    try {
                        val children = event.getChildren
                        Collections.shuffle(children)
                        p success (children map {
                            event.getPath + "/" + _
                        } map recycleIp sum)
                    } catch { case NonFatal(e) =>
                        p failure e
                    }
                }
            } else {
                fail(event, p)
            }
        }
    }

    private def recycleIp(path: String): Int = {
        val stat = new Stat()
        val blocks = zk.getChildren.storingStatIn(stat).forPath(path)
        Collections.shuffle(blocks)
        val blockStats = blocks map { path + "/" + _ } map { block =>
            (block, zk.checkExists().forPath(block))
        }
        maybeRecycleBlocks(path, stat, blockStats)
    }

    private def maybeRecycleBlocks(
            ipPath: String, ipStat: Stat, data: Seq[(String, Stat)]): Int = {
        val tx = data.foldLeft(zk.inTransaction()) { case (acc, (path, stat)) =>
            if (stat.getNumChildren == 0)
                delNode(acc, path, stat)
            else
                return 0 // returns from maybeRecycleBlocks
        }
        delNode(tx, ipPath, ipStat).commit()
        data.size
    }

    private def delNode(tx: CuratorTransaction, path: String, stat: Stat) =
        tx.delete.withVersion(stat.getVersion).forPath(path).and()

    private def fail(event: CuratorEvent, p: Promise[Int]): Unit =
        fail(
            event.getPath,
            KeeperException.create(Code.get(event.getResultCode), event.getPath),
            p)

    private def fail(path: String, e: Throwable, p: Promise[Int]): Unit = {
        log.error(s"Failed to recycle blocks for $path", e)
        p.failure(e)
    }
}

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

import java.util.concurrent.{TimeUnit, Executor}

import scala.collection.JavaConversions._
import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, Promise, Future}
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

import com.typesafe.scalalogging.Logger
import org.apache.curator.framework.api.transaction.CuratorTransaction
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.api.{CuratorEvent, BackgroundCallback}
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
 * connection randomly chooses the same port as an existing, migrated connection
 */
class ZkNatBlockRecycler(
        zk: CuratorFramework,
        executor: Executor,
        clock: UnixClock) {
    import ZkNatBlockAllocator._
    import ZkNatBlockRecycler._

    private val log = Logger(LoggerFactory.getLogger("nat-block-allocator"))
    private implicit val ex = ExecutionContext.callingThread
    private val bytes = Array[Byte](1)

    def recycle(): Future[Int] = {
        val p = Promise[Int]()
        zk.getChildren
          .inBackground(tryStartRecycling, p, executor)
          .forPath(natPath)
        p.future
    }

    private val tryStartRecycling = new BackgroundCallback {
        override def processResult(client: CuratorFramework,
                                   event: CuratorEvent): Unit =
            if (event.getResultCode == KeeperException.Code.OK.intValue()) {
                if (clock.time - event.getStat.getMtime > ONE_DAY) {
                    zk.setData()
                      .withVersion(event.getStat.getVersion)
                      .inBackground(isGarbageman, event.getContext, executor)
                      .forPath(natPath, bytes)
                } else {
                    log.debug("Skipping NAT block recycling: too soon after last operation")
                    event.getContext.asInstanceOf[Promise[Int]].success(0)
                }
            } else if (event.getResultCode != KeeperException.Code.NONODE.intValue()) {
                fail(event)
            }
    }

    private val isGarbageman = new BackgroundCallback {
        override def processResult(client: CuratorFramework,
                                   event: CuratorEvent): Unit =
            if (event.getResultCode == KeeperException.Code.OK.intValue()) {
                log.info("Recycling NAT blocks")
                zk.getChildren
                  .inBackground(recycleTopLevel, event.getContext, executor)
                  .forPath(natPath)
            } else if (event.getResultCode == KeeperException.Code.BADVERSION.intValue()) {
                log.info("Stopping: recycle already being performed by another host")
                event.getContext.asInstanceOf[Promise[Int]].success(0)
            } else {
                fail(event)
            }
    }

    private val recycleTopLevel = new BackgroundCallback {
        override def processResult(client: CuratorFramework,
                                   event: CuratorEvent): Unit =
            if (event.getResultCode == KeeperException.Code.OK.intValue()) {
                forEachChild(recycleDevice, event)
            } else {
                fail(event)
            }
    }

    private val recycleDevice = new BackgroundCallback {
        override def processResult(client: CuratorFramework,
                                   event: CuratorEvent): Unit =
            if (event.getResultCode == KeeperException.Code.OK.intValue()) {
                if (event.getChildren.size() == 0) {
                    zk.delete()
                      .withVersion(event.getStat.getVersion)
                      .inBackground()
                      .forPath(event.getPath)
                    event.getContext.asInstanceOf[Promise[Int]].success(0)
                } else {
                    forEachChild(recycleIp, event)
                }
            } else {
                fail(event)
            }
    }

    private val recycleIp = new BackgroundCallback {
        override def processResult(client: CuratorFramework,
                                   event: CuratorEvent): Unit =
            if (event.getResultCode == KeeperException.Code.OK.intValue()) {
                Future.traverse(event.getChildren map { child =>
                    event.getPath + "/" + child
                })(fetchAsync) andThen {
                    case Success(data) =>
                        maybeRecycleBlocks(event, data)
                    case Failure(e) =>
                        fail(event, e)
                }
            } else {
                fail(event)
            }
    }

    private def maybeRecycleBlocks(
            ipNode: CuratorEvent, data: Seq[CuratorEvent]): Unit = {
        val p = ipNode.getContext.asInstanceOf[Promise[Int]]
        val tx = data.foldLeft(zk.inTransaction()) { (acc, blockNode) =>
            if (blockNode.getStat.getNumChildren == 0) {
                delNode(acc, blockNode)
            } else {
                p success 0
                return // returns from maybeRecycleBlocks
            }
        }
        try {
            delNode(tx, ipNode).commit()
            p success data.size
        } catch { case NonFatal(e) =>
            fail(ipNode, e)
        }
    }

    private def delNode(tx: CuratorTransaction, event: CuratorEvent) =
        tx.delete
          .withVersion(event.getStat.getVersion)
          .forPath(event.getPath)
          .and()

    private def fetchAsync(path: String): Future[CuratorEvent] = {
        val p = Promise[CuratorEvent]()
        zk.getData()
          .inBackground(backgroundCallbackToFuture, p, executor)
          .forPath(path)
        p.future
    }

    private def forEachChild[T](
            continue: BackgroundCallback, event: CuratorEvent): Unit = {
        val upstreamPromise = event.getContext.asInstanceOf[Promise[Int]]
        var ps = ListBuffer[Future[Int]]()
        val it = event.getChildren.iterator()
        while (it.hasNext) {
            val p = Promise[Int]()
            val path = event.getPath + "/" + it.next()
            zk.getChildren
              .inBackground(continue, p, executor)
              .forPath(path)
            ps += p.future
        }
        upstreamPromise.completeWith(Future.sequence(ps).map(_.sum))
    }

    private def fail(event: CuratorEvent): Unit =
        fail(event, KeeperException.create(
            Code.get(event.getResultCode), event.getPath))

    private def fail(event: CuratorEvent, e: Throwable): Unit = {
        log.error(s"Failed to recycle blocks for ${event.getPath}", e)
        event.getContext.asInstanceOf[Promise[Int]].failure(e)
    }
}

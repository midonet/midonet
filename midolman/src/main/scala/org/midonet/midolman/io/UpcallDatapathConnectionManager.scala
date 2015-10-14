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
package org.midonet.midolman.io

import java.util.concurrent.{TimeUnit, ConcurrentHashMap}
import java.util.concurrent.locks.ReentrantLock

import scala.concurrent.{Promise, ExecutionContext, Future}
import scala.util.{Failure, Success}

import akka.actor.ActorSystem
import akka.util.Timeout

import org.slf4j.{Logger, LoggerFactory}

import org.midonet.ErrorCode.{EBUSY, EEXIST, EADDRINUSE}
import org.midonet.midolman.PacketsEntryPoint.{GetWorkers, Workers}
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.state.FlowStatePackets
import org.midonet.midolman.{PacketWorkflow, NetlinkCallbackDispatcher, PacketsEntryPoint}
import org.midonet.netlink.BufferPool
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.odp._
import org.midonet.odp.protos.OvsDatapathConnection
import org.midonet.util.concurrent.NanoClock
import org.midonet.util.{BatchCollector, Bucket}
import org.midonet.util.eventloop.SelectLoop

/**
 * The UpcallDatapathConnectionManager will create a new netlink channel
 * (datapath connection) for each datapath port in the system. It then asks
 * the datapath to send notifications (packets) for this port through this
 * dedicated channel. IO for these channels is non-blocking and uses a
 * select loop.
 */
trait UpcallDatapathConnectionManager {

    def createAndHookDpPort(dp: Datapath, port: DpPort, t: ChannelType)
        (implicit ec: ExecutionContext, as: ActorSystem): Future[(DpPort, Int)]

    def deleteDpPort(datapath: Datapath, port: DpPort)
        (implicit ec: ExecutionContext, as: ActorSystem): Future[_]
}

/**
 * Base class for specific UpcallDatapathConnectionManager's, depending on
 * the possible threading model.
 */
abstract class UpcallDatapathConnectionManagerBase(
        val config: MidolmanConfig,
        val tbPolicy: TokenBucketPolicy) extends UpcallDatapathConnectionManager {

    protected val log: Logger

    private val workers = Promise[Workers]()

    protected def makeConnection(name: String, bucket: Bucket,
                                 channelType: ChannelType)
    : ManagedDatapathConnection

    protected def stopConnection(conn: ManagedDatapathConnection)

    protected val portToChannel = new ConcurrentHashMap[(Datapath, Int),
                                                        ManagedDatapathConnection]()

    protected def setUpcallHandler(conn: OvsDatapathConnection,
                                   w: Workers)
                                  (implicit as: ActorSystem)

    protected def makeBufferPool() = new BufferPool(1, 8, 8*1024)

    def askForWorkers()
                     (implicit ec: ExecutionContext, as: ActorSystem) = {
        if (!workers.isCompleted) {
            workers.tryCompleteWith(doAskForWorkers())
        }
        workers.future
    }

    private def doAskForWorkers()
                               (implicit ec: ExecutionContext, as: ActorSystem)
    : Future[Workers] = {
        implicit val tout = Timeout(3, TimeUnit.SECONDS)
        (PacketsEntryPoint ? GetWorkers).mapTo[Workers].recoverWith { case e =>
            doAskForWorkers()
        }
    }

    def getDispatcher()(implicit as: ActorSystem) =
        NetlinkCallbackDispatcher.makeBatchCollector()

    def createAndHookDpPort(datapath: Datapath, port: DpPort, t: ChannelType)
                           (implicit ec: ExecutionContext, as: ActorSystem)
    : Future[(DpPort, Int)] = {

        val connName = "port-upcall-" + port.getName
        log.info("creating datapath connection for {}", port.getName)

        var conn: ManagedDatapathConnection = null
        try {
            conn = makeConnection(connName, tbPolicy.link(port, t), t)
        } catch {
            case e: Throwable =>
                tbPolicy.unlink(port)
                return Future.failed(e)
        }

        conn.start()
        askForWorkers() flatMap { workers =>
            val dpConn = conn.getConnection
            dpConn setCallbackDispatcher getDispatcher()
            setUpcallHandler(dpConn, workers)
            ensurePortPid(port, datapath, dpConn)
        } andThen {
            case Success((createdPort, _)) =>
                portToChannel.put((datapath, createdPort.getPortNo.intValue), conn)
            case Failure(e) =>
                log.error("failed to create or retrieve datapath port "
                          + port.getName, e)
                stopConnection(conn)
                tbPolicy.unlink(port)
        }
    }

    def ensurePortPid(port: DpPort, dp: Datapath, con: OvsDatapathConnection)(
                      implicit ec: ExecutionContext) = {
        val dpConnOps = new OvsConnectionOps(con)
        log.info("creating datapath port {}", port)
        dpConnOps.createPort(port, dp) recoverWith {
            // Error code changed in OVS in May-2013 from EBUSY to EEXIST
            // http://openvswitch.org/pipermail/dev/2013-May/027947.html
            case ex: NetlinkException
                if ex.getErrorCodeEnum == EEXIST ||
                   ex.getErrorCodeEnum == EBUSY ||
                   ex.getErrorCodeEnum == EADDRINUSE =>
                dpConnOps.delPort(port, dp) flatMap { _ =>
                    dpConnOps.createPort(port, dp)
                }
        } map { (_, con.getChannel.getLocalAddress.getPid) }
    }

    def deleteDpPort(datapath: Datapath, port: DpPort)(
        implicit ec: ExecutionContext, as: ActorSystem): Future[_] =
        portToChannel.remove((datapath, port.getPortNo)) match {
            case null => Future.successful(null)
            case conn =>
                val (delCb, delFuture) =
                    OvsConnectionOps.callbackBackedFuture[DpPort]()
                conn.getConnection.portsDelete(port, datapath, delCb)

                delFuture recover { case ex: NetlinkException =>
                    // Although recovers all NetlinkException protectively,
                    // currently expected exceptions are: ENOENT and ENODEV
                    log.info("Ignoring error while deleting datapath port "
                             + port.getName, ex)
                    port
                } map { v =>
                    stopConnection(conn)
                    tbPolicy.unlink(port)
                }
        }

    protected def makeUpcallHandler(workers: Workers)
                                   (implicit as: ActorSystem) =
        new BatchCollector[Packet] {

            val BATCH_SIZE: Int = 16
            val NUM_WORKERS = workers.list.length
            var packets = Array.ofDim[Packet](workers.list.length, BATCH_SIZE)
            var cursors = Array.fill[Int](NUM_WORKERS)(0)
            val log = LoggerFactory.getLogger("PacketInHook")

            def endBatch(worker: Int) {
                if (cursors(worker) > 0) {
                    workers.list(worker) ! PacketWorkflow.HandlePackets(packets(worker))
                    cursors(worker) = 0
                    packets(worker) = new Array[Packet](BATCH_SIZE)
                }
            }

            override def endBatch() {
                var i = 0
                while (i < NUM_WORKERS) {
                    endBatch(i)
                    i += 1
                }
            }

            override def submit(data: Packet) {
                log.trace("accumulating packet: {}", data.getMatch)

                data.startTimeNanos = NanoClock.DEFAULT.tick

                if (FlowStatePackets.isStateMessage(data.getMatch)) {
                    var i = 0
                    while (i < NUM_WORKERS) {
                        addToWorkerBatch(i, data)
                        i += 1
                    }
                } else {
                    val worker = Math.abs(data.getMatch.connectionHash) % NUM_WORKERS
                    addToWorkerBatch(worker, data)
                }
            }

            private def addToWorkerBatch(worker: Int, data: Packet): Unit = {
                packets(worker)(cursors(worker)) = data
                cursors(worker) += 1
                if (cursors(worker) == BATCH_SIZE)
                    endBatch(worker)
            }
        }
}

/**
 * UpcallDatapathConnectionManager with a one-to-one threading model: each
 * channel gets its own thread and select loop.
 */
class OneToOneDpConnManager(c: MidolmanConfig,
                            tbPolicy: TokenBucketPolicy) extends
        UpcallDatapathConnectionManagerBase(c, tbPolicy) {

    protected override val log = LoggerFactory.getLogger(this.getClass)

    override def makeConnection(name: String, bucket: Bucket,
                                channelType: ChannelType) =
        new SelectorBasedDatapathConnection(name, config, true, bucket, makeBufferPool())

    override def stopConnection(conn: ManagedDatapathConnection) {
        conn.stop()
    }

    protected override def setUpcallHandler(conn: OvsDatapathConnection,
                                            w: Workers)
                                           (implicit as: ActorSystem) {
        conn.datapathsSetNotificationHandler(makeUpcallHandler(w))
    }
}

/**
 * UpcallDatapathConnectionManager with a one-to-many threading model: a single
 * thread and a single select loop is used for all the input channels.
 */
class OneToManyDpConnManager(c: MidolmanConfig,
                             tbPolicy: TokenBucketPolicy)
        extends UpcallDatapathConnectionManagerBase(c, tbPolicy) {

    val threadPair = new SelectorThreadPair("upcall", config, false)

    private val lock = new ReentrantLock()

    val sendPool = makeBufferPool()

    protected override val log = LoggerFactory.getLogger(this.getClass)

    private var upcallHandler: BatchCollector[Packet] = null

    override def makeConnection(name: String, bucket: Bucket,
                                channelType: ChannelType) = {
        if (!threadPair.isRunning)
            threadPair.start()

        val priority = channelType match {
            case OverlayTunnel => SelectLoop.Priority.HIGH
            case _ => SelectLoop.Priority.NORMAL
        }
        val conn = threadPair.addConnection(bucket, sendPool, priority)
        conn.getConnection.setUsingSharedNotificationHandler(true)
        conn
    }

    override def stopConnection(conn: ManagedDatapathConnection) {
        threadPair.removeConnection(conn)
    }

    protected override def setUpcallHandler(conn: OvsDatapathConnection,
                                            w: Workers)
                                           (implicit as: ActorSystem) {
        lock.lock()
        try {
            if (upcallHandler == null) {
                upcallHandler = makeUpcallHandler(w)
                threadPair.getReadLoop.setEndOfLoopCallback(new Runnable {
                    override def run() {
                        upcallHandler.endBatch()
                    }
                })
            }
        } finally {
            lock.unlock()
        }

        conn.datapathsSetNotificationHandler(upcallHandler)
    }
}

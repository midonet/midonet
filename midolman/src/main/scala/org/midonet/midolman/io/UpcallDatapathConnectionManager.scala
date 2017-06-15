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

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

import scala.collection.IndexedSeq
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

import akka.actor.ActorSystem

import com.codahale.metrics.MetricRegistry

import org.slf4j.{Logger, LoggerFactory}

import org.midonet.ErrorCode
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.{NetlinkCallbackDispatcher, PacketWorker}
import org.midonet.netlink.BufferPool
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.odp._
import org.midonet.odp.protos.OvsDatapathConnection
import org.midonet.packets._
import org.midonet.util.concurrent.NanoClock
import org.midonet.util.eventloop.SelectLoop
import org.midonet.util.{BatchCollector, Bucket}

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

    import org.midonet.odp.FlowMatchMessageType._

    protected val log: Logger

    protected def makeConnection(name: String, bucket: Bucket,
                                 channelType: ChannelType)
    : ManagedDatapathConnection

    protected def stopConnection(conn: ManagedDatapathConnection)

    protected val portToChannel = new ConcurrentHashMap[(Datapath, Int),
                                                        ManagedDatapathConnection]()

    protected def setUpcallHandler(conn: OvsDatapathConnection)

    protected def makeBufferPool() = new BufferPool(1, 8, 8*1024)

    def getDispatcher()(implicit as: ActorSystem) =
        NetlinkCallbackDispatcher.makeBatchCollector()

    override def createAndHookDpPort(datapath: Datapath, port: DpPort, t: ChannelType)
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

        val dpConn = conn.getConnection
        dpConn setCallbackDispatcher getDispatcher()
        setUpcallHandler(dpConn)
        ensurePortPid(port, datapath, dpConn) andThen {
            case Success((createdPort, _)) =>
                log.debug(s"Successfully created or reclaimed port $createdPort")
                portToChannel.put((datapath, createdPort.getPortNo.intValue), conn)
            case Failure(e) =>
                log.error(s"Failed to create or reclaim datapath port ${port.getName}", e)
                stopConnection(conn)
                tbPolicy.unlink(port)
        }
    }

    private def isExisting(ex: NetlinkException) = ex.getErrorCodeEnum match {
        // Error code changed in OVS in May-2013 from EBUSY to EEXIST
        // http://openvswitch.org/pipermail/dev/2013-May/027947.html
        case ErrorCode.EEXIST | ErrorCode.EBUSY | ErrorCode.EADDRINUSE => true
        case _ => false
    }

    private def ensurePortPid(port: DpPort, dp: Datapath, con: OvsDatapathConnection)
                             (implicit ec: ExecutionContext) = {
        val dpConnOps = new OvsConnectionOps(con)
        log.info(s"Creating datapath port $port on datapath ${dp.getName}.")
        dpConnOps.createPort(port, dp) recoverWith {
            case ex: NetlinkException if isExisting(ex) =>
                if (config.reclaimDatapath) {
                    // NOTE: set port operation not supported, need to get
                    // the port and recreate it with the same port number
                    dpConnOps.getPort(port.getName, dp) flatMap { existingPort =>
                        log.info(s"Reclaiming datapath port $existingPort.")
                        dpConnOps.delPort(existingPort, dp) flatMap { _ =>
                            dpConnOps.createPort(existingPort, dp)
                        }
                    }
                } else {
                    log.debug(s"Existing port $port, recreate it.")
                    dpConnOps.delPort(port, dp) flatMap { _ =>
                        dpConnOps.createPort(port, dp)
                    }
                }
            case t: Throwable =>
                log.error(s"Error while creating datapath port $port on " +
                          s"datapath ${dp.getName}.")
                Future.failed(t)
        } map { (_, con.getChannel.getLocalAddress.getPid) }
    }

    override def deleteDpPort(datapath: Datapath, port: DpPort)
                    (implicit ec: ExecutionContext, as: ActorSystem): Future[_] =
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

    protected def makeUpcallHandler(workers: IndexedSeq[PacketWorker]) =
        new BatchCollector[Packet] {

            private val contextProvider: ThreadLocal[PerThreadICMPErrorContext] =
                new ThreadLocal[PerThreadICMPErrorContext] {
                    override def initialValue(): PerThreadICMPErrorContext =
                        new PerThreadICMPErrorContext
                }

            val NUM_WORKERS = workers.length
            val log = LoggerFactory.getLogger("PacketInHook")

            override def endBatch() {
                // noop
            }

            override def submit(data: Packet): Boolean = {
                log.trace("accumulating packet: {}", data.getMatch)
                data.startTimeNanos = NanoClock.DEFAULT.tick

                if (isFlowStateMessage(data.getMatch) &&
                    FlowStateEthernet.isLegacyFlowState(data.getEthernet)) {
                    log.debug(s"Legacy Flow state received (hash = " +
                              s"${FlowStateEthernet.getConnectionHash(data.getEthernet)}), " +
                              s"submit to all workers.")
                    var i = 0
                    /* This code is problematic for the HTB. The HTB will take
                     * one token for a flow state message, but each worker will
                     * return one token for the flow state message.
                     * This can potentially cause the number of HTB messages
                     * available to explode.
                     * This was fixed by MI-2367, but we need to reintroduce
                     * this code for backwards compatibility as we don't know to
                     * which worker this *legacy* flow state should go.
                     * This will only be exercised during rolling upgrades.
                     */
                    var submitted = false
                    while (i < workers.length) {
                        // order is important here, we want submit to run, even
                        // if submitted is already true
                        submitted = workers(i).submit(data) || submitted
                        i += 1
                    }
                    submitted
                } else {
                    val hash = getConnectionHash(data)
                    val worker = Math.abs(hash) % NUM_WORKERS
                    log.debug(s"Connection hash: $hash -> going to worker $worker")
                    workers(worker).submit(data)
                }
            }

            private def getConnectionHash(data: Packet): Int = {
                if (isFlowStateMessage(data.getMatch)) {
                    log.debug("Message is flow state, get connection " +
                              "hash from IP header")
                    FlowStateEthernet.getConnectionHash(data.getEthernet)
                } else if (isICMPError(data.getMatch)) {
                    log.debug("Message is icmp error, get connection hash " +
                              "from original IP header in ICMP payload")
                    val origFlowMatch = contextProvider.get()
                        .originalFlowMatch(data.getMatch.getIcmpData)
                    origFlowMatch.inverseConnectionHash
                } else if (isFlowTracedOnEgress(data.getMatch)) {
                    log.debug("Message is flow traced, get the inverse " +
                              "connection hash, where the flow state went.")
                    data.getMatch.inverseTransportConnectionHash()
                } else {
                    data.getMatch.connectionHash
                }
            }
        }
}

/**
  * Private context for each upcall thread, so we allocate as little
  * as possible.
  */
private final class PerThreadICMPErrorContext {
    private val flowMatch = new FlowMatch
    private val origIpHeader = new IPv4
    private val srcIpAddr = new IPv4Addr(0)
    private val dstIpAddr = new IPv4Addr(0)

    def originalFlowMatch(icmpData: Array[Byte]): FlowMatch = {
        flowMatch.clear()
        val buffer = ByteBuffer.wrap(icmpData)
        origIpHeader.deserialize(buffer)
        val origTransportHeader =
            origIpHeader.getPayload.asInstanceOf[Transport]
        srcIpAddr.setAddr(origIpHeader.getSourceAddress)
        dstIpAddr.setAddr(origIpHeader.getDestinationAddress)
        flowMatch.setNetworkSrc(srcIpAddr)
            .setNetworkDst(dstIpAddr)
            .setNetworkProto(origIpHeader.getProtocol)
            .setSrcPort(origTransportHeader.getSourcePort)
            .setDstPort(origTransportHeader.getDestinationPort)
    }
}

/**
 * UpcallDatapathConnectionManager with a one-to-one threading model: each
 * channel gets its own thread and select loop.
 */
class OneToOneDpConnManager(c: MidolmanConfig,
                            workers: IndexedSeq[PacketWorker],
                            tbPolicy: TokenBucketPolicy,
                            metrics: MetricRegistry)
        extends UpcallDatapathConnectionManagerBase(c, tbPolicy) {

    protected override val log = LoggerFactory.getLogger(this.getClass)

    override def makeConnection(name: String, bucket: Bucket,
                                channelType: ChannelType) =
        new SelectorBasedDatapathConnection(name, config, true, bucket,
                                            makeBufferPool(), metrics)

    override def stopConnection(conn: ManagedDatapathConnection) {
        conn.stop()
    }

    protected override def setUpcallHandler(conn: OvsDatapathConnection) {
        conn.datapathsSetNotificationHandler(makeUpcallHandler(workers))
    }
}

/**
 * UpcallDatapathConnectionManager with a one-to-many threading model: a single
 * thread and a single select loop is used for all the input channels.
 */
class OneToManyDpConnManager(c: MidolmanConfig,
                             workers: IndexedSeq[PacketWorker],
                             tbPolicy: TokenBucketPolicy,
                             metrics: MetricRegistry)
        extends UpcallDatapathConnectionManagerBase(c, tbPolicy) {

    val threadPair = new SelectorThreadPair("upcall", config, false, metrics)

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

    protected override def setUpcallHandler(conn: OvsDatapathConnection) {
        lock.lock()
        try {
            if (upcallHandler == null) {
                upcallHandler = makeUpcallHandler(workers)
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

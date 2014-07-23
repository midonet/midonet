/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.io

import java.util.concurrent.locks.ReentrantLock
import scala.concurrent.{ExecutionContext, Future}
import scala.collection.mutable
import scala.util.{Failure, Success}

import akka.actor.ActorSystem
import akka.util.Timeout
import com.yammer.metrics.core.Clock
import org.slf4j.{Logger, LoggerFactory}

import org.midonet.midolman.PacketsEntryPoint.{GetWorkers, Workers}
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.{PacketsEntryPoint, NetlinkCallbackDispatcher, DeduplicationActor}
import org.midonet.netlink.BufferPool
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.netlink.exceptions.NetlinkException.ErrorCode.EBUSY
import org.midonet.netlink.exceptions.NetlinkException.ErrorCode.EEXIST
import org.midonet.odp._
import org.midonet.odp.protos.OvsDatapathConnection
import org.midonet.util.{Bucket, BatchCollector}

trait UpcallDatapathConnectionManager {

    def createAndHookDpPort(dp: Datapath, port: DpPort, t: ChannelType)
        (implicit ec: ExecutionContext, as: ActorSystem): Future[(DpPort, Int)]

    def deleteDpPort(datapath: Datapath, port: DpPort)
        (implicit ec: ExecutionContext, as: ActorSystem): Future[Boolean]
}

abstract class UpcallDatapathConnectionManagerBase(
        val config: MidolmanConfig,
        val tbPolicy: TokenBucketPolicy) extends UpcallDatapathConnectionManager {

    protected val log: Logger

    protected def makeConnection(name: String, bucket: Bucket)
    : ManagedDatapathConnection

    protected def stopConnection(conn: ManagedDatapathConnection)

    protected val portToChannel = mutable.Map[(Datapath, Int),
                                              ManagedDatapathConnection]()

    protected def setUpcallHandler(conn: OvsDatapathConnection,
                                   w: Workers)
                                  (implicit as: ActorSystem)

    protected def makeBufferPool() = new BufferPool(1, 8, 8*1024)

    def askForWorkers()
               (implicit ec: ExecutionContext, as: ActorSystem) = {
        implicit val tout = Timeout(3000L)
        (PacketsEntryPoint ? GetWorkers).mapTo[Workers]
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
            conn = makeConnection(connName, tbPolicy.link(port, t))
        } catch {
            case e: Throwable =>
                tbPolicy.unlink(port)
                return Future.failed(e)
        }

        initConnection(conn) zip askForWorkers() flatMap {
            case (_, workers) =>
                val dpConn = conn.getConnection
                dpConn setCallbackDispatcher getDispatcher()
                setUpcallHandler(dpConn, workers)
                ensurePortPid(port, datapath, dpConn)
        } andThen {
            case Success((createdPort, _)) =>
                val kv = ((datapath, createdPort.getPortNo.intValue), conn)
                portToChannel += kv
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
        log.info("creating datapath {}", port)
        dpConnOps.createPort(port, dp) recoverWith {
            // Error code changed in OVS in May-2013 from EBUSY to EEXIST
            // http://openvswitch.org/pipermail/dev/2013-May/027947.html
            case ex: NetlinkException
                if ex.getErrorCodeEnum == EEXIST ||
                    ex.getErrorCodeEnum == EBUSY =>
                dpConnOps.getPort(port.getName, dp) flatMap {
                    case existingPort =>
                        log.info("setting upcall PID for existing port: {}",
                                 port.getName)
                        // OvsDatapathConnectionImpl#_doPortsSet() sets the port
                        // upcall pid to the channel sending the request.
                        dpConnOps.setPort(existingPort, dp)
                }
        } map { (_, con.getChannel.getLocalAddress.getPid) }
    }

    def deleteDpPort(datapath: Datapath, port: DpPort)(
        implicit ec: ExecutionContext, as: ActorSystem): Future[Boolean] =
        portToChannel.get((datapath, port.getPortNo)) match {
            case None => Future.successful(true)
            case Some(conn) =>
                val (delCb, delFuture) =
                    OvsConnectionOps.callbackBackedFuture[DpPort]()
                conn.getConnection.portsDelete(port, datapath, delCb)

                delFuture recover {
                    case ex: NetlinkException =>
                        // Although recovers all NetlinkException protectively,
                        // currently expected exceptions are: ENOENT and ENODEV
                        log.info("Ignoring error while deleting datapath port "
                            + port.getName, ex)
                        port
                } andThen {
                    case Success(v) =>
                        stopConnection(conn)
                        portToChannel.remove((datapath, port.getPortNo))
                        tbPolicy.unlink(port)
                } map { _ => true }
        }

    protected def initConnection(conn: ManagedDatapathConnection) = {
        val (initCb, initFuture) =
            OvsConnectionOps.callbackBackedFuture[java.lang.Boolean]()
        conn.start(initCb)
        initFuture
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
                    workers.list(worker) ! DeduplicationActor.HandlePackets(packets(worker))
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

                data.processUserspaceKeys()
                data.startTimeNanos = Clock.defaultClock().tick()

                val worker = Math.abs(data.getMatch.connectionHash) % NUM_WORKERS
                packets(worker)(cursors(worker)) = data
                cursors(worker) += 1
                if (cursors(worker) == BATCH_SIZE)
                    endBatch(worker)
            }
        }
}

class OneToOneDpConnManager(c: MidolmanConfig,
                            tbPolicy: TokenBucketPolicy) extends
        UpcallDatapathConnectionManagerBase(c, tbPolicy) {

    protected override val log = LoggerFactory.getLogger(this.getClass)

    override def makeConnection(name: String, bucket: Bucket) =
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

class OneToManyDpConnManager(c: MidolmanConfig,
                             tbPolicy: TokenBucketPolicy)
        extends UpcallDatapathConnectionManagerBase(c, tbPolicy) {

    val threadPair = new SelectorThreadPair("upcall", config, false)

    private val lock = new ReentrantLock()

    val sendPool = makeBufferPool()

    protected override val log = LoggerFactory.getLogger(this.getClass)

    private var upcallHandler: BatchCollector[Packet] = null

    override def makeConnection(name: String, bucket: Bucket) = {
        if (!threadPair.isRunning)
            threadPair.start()
        val conn = threadPair.addConnection(bucket, sendPool)
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

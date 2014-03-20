/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman.io

import java.util.concurrent.TimeoutException
import scala.concurrent.{ExecutionContext, Promise, Future}
import scala.collection.mutable
import scala.util.{Failure, Success}

import akka.actor.ActorSystem
import akka.util.Timeout
import com.yammer.metrics.core.Clock
import org.slf4j.{Logger, LoggerFactory}

import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.{PacketsEntryPoint, NetlinkCallbackDispatcher, DeduplicationActor}
import org.midonet.midolman.PacketsEntryPoint.{GetWorkers, Workers}
import org.midonet.netlink.Callback
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.netlink.exceptions.NetlinkException.ErrorCode.ENOENT
import org.midonet.netlink.exceptions.NetlinkException.ErrorCode.EEXIST
import org.midonet.netlink.exceptions.NetlinkException.ErrorCode.EBUSY
import org.midonet.odp._
import org.midonet.util.BatchCollector
import org.midonet.odp.protos.OvsDatapathConnection

abstract class UpcallDatapathConnectionManager(val config: MidolmanConfig) {
    protected val log: Logger

    protected def makeConnection(name: String): ManagedDatapathConnection
    protected def stopConnection(conn: ManagedDatapathConnection)

    protected val portToChannel = mutable.Map[(Datapath, Int), ManagedDatapathConnection]()


    def portCreated(datapath: Datapath, port: DpPort, conn: ManagedDatapathConnection) {
        portToChannel.put((datapath, port.getPortNo), conn)
    }

    def portDeleted(datapath: Datapath, port: DpPort) {
        portToChannel.remove((datapath, port.getPortNo))
    }

    protected def setUpcallHandler(
        conn: OvsDatapathConnection, w: Workers)(implicit as: ActorSystem)

    def createAndHookDpPort(datapath: Datapath, port: DpPort)(
        implicit ec: ExecutionContext, as: ActorSystem):
            Future[(Datapath, DpPort, ManagedDatapathConnection)] = {

        val connName = "port-upcall-" + port.getName
        log.info("creating datapath connection for {}", port.getName)

        var conn: ManagedDatapathConnection = null
        try {
            conn = makeConnection(connName)
        } catch { case e: Throwable =>
            return Future.failed[(Datapath, DpPort, ManagedDatapathConnection)](e)
        }

        val (initCb, initFuture) = newCallbackBackedFuture[java.lang.Boolean]()
        conn.start(initCb)

        val dpConn = conn.getConnection

        implicit val tout = Timeout(3000L)
        val workersFuture = (PacketsEntryPoint ? GetWorkers).mapTo[Workers]

        initFuture flatMap { _ => workersFuture } flatMap { workers =>
            val (createCb, createFuture) = newCallbackBackedFuture[DpPort]()
            dpConn.setCallbackDispatcher(NetlinkCallbackDispatcher.makeBatchCollector())
            setUpcallHandler(dpConn, workers)
            log.info("creating datapath port: {}", port.getName)
            dpConn.portsCreate(datapath, port, createCb)
            createFuture recoverWith {
                // Error code changed in OVS in May-2013 from EBUSY to EEXIST
                // http://openvswitch.org/pipermail/dev/2013-May/027947.html
                case ex: NetlinkException
                    if ex.getErrorCodeEnum == EEXIST ||
                        ex.getErrorCodeEnum == EBUSY =>

                    log.info("datapath port already exists: {}", port.getName)
                    val (getCb, getFuture) = newCallbackBackedFuture[DpPort]()
                    log.info("retrieving datapath port: {}", port.getName)
                    dpConn.portsGet(port.getName, datapath, getCb)
                    getFuture flatMap {
                        case p =>
                            log.info("setting upcall PID for pre-existing port: {}", port.getName)
                            val (setCb, setFuture) = newCallbackBackedFuture[DpPort]()
                            dpConn.portsSet(p, datapath, setCb)
                            setFuture
                    }
            } andThen {
                case Failure(e) =>
                    log.error(port.getName + ": failed to create or retrieve datapath port", e)
                    conn.stop()
            }
        } map { (datapath, _, conn) }
    }

    def deleteDpPort(datapath: Datapath, port: DpPort)(
        implicit ec: ExecutionContext, as: ActorSystem): Future[Boolean] =
        portToChannel.get((datapath, port.getPortNo)) match {
            case None => Future.successful(true)
            case Some(conn) =>
                val (delCb, delFuture) = newCallbackBackedFuture[DpPort]()
                conn.getConnection.portsDelete(port, datapath, delCb)

                delFuture recover {
                    case ex: NetlinkException if ex.getErrorCodeEnum == ENOENT =>
                        port
                } andThen {
                    case Success(v) =>
                        cleanUp(conn)
                        portToChannel.remove((datapath, port.getPortNo))
                } map { _ => true }
        }

    private def cleanUp(conn: ManagedDatapathConnection): Unit = {
        stopConnection(conn)
    }

    protected def newCallbackBackedFuture[T](): (Callback[T], Future[T]) = {
        val promise = Promise[T]()

        val cb = new Callback[T] {
            override def onSuccess(data: T) {
                promise.success(data)
            }

            override def onTimeout() {
                promise.failure(new TimeoutException("Netlink request timed out"))
            }

            override def onError(e: NetlinkException) {
                promise.failure(e)
            }
        }

        (cb, promise.future)
    }

    protected def makeUpcallHandler(workers: Workers)(implicit as: ActorSystem) =
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
                val eth = data.getPacket
                FlowMatches.addUserspaceKeys(eth, data.getMatch)
                data.setStartTimeNanos(Clock.defaultClock().tick())

                val worker = Math.abs(data.getMatch.hashCode) % NUM_WORKERS
                packets(worker)(cursors(worker)) = data
                cursors(worker) += 1
                if (cursors(worker) == BATCH_SIZE)
                    endBatch(worker)
            }
        }
}

class OneToOneDpConnManager(c: MidolmanConfig) extends
        UpcallDatapathConnectionManager(c) {

    protected override val log = LoggerFactory.getLogger(this.getClass)

    override def makeConnection(name: String) =
        new SelectorBasedDatapathConnection(name, config, true)

    override def stopConnection(conn: ManagedDatapathConnection) {
        conn.stop()
    }

    protected override def setUpcallHandler(
            conn: OvsDatapathConnection, w: Workers)(implicit as: ActorSystem) {
        conn.datapathsSetNotificationHandler(makeUpcallHandler(w))
    }
}

class OneToManyDpConnManager(c: MidolmanConfig) extends
        UpcallDatapathConnectionManager(c) {

    val threadPair = new SelectorThreadPair("upcall", config, false)

    protected override val log = LoggerFactory.getLogger(this.getClass)

    private var upcallHandler: BatchCollector[Packet] = null

    override def makeConnection(name: String) = {
        if (!threadPair.isRunning)
            threadPair.start()
        threadPair.addConnection()
    }

    override def stopConnection(conn: ManagedDatapathConnection) {
        threadPair.removeConnection(conn)
    }

    protected override def setUpcallHandler(
            conn: OvsDatapathConnection, w: Workers)(implicit as: ActorSystem) {
        if (upcallHandler == null)
            upcallHandler = makeUpcallHandler(w)

        conn.datapathsSetNotificationHandler(upcallHandler)
    }
}

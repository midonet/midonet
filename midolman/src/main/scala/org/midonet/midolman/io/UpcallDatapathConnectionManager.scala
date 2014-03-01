/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman.io

import java.util.concurrent.TimeoutException
import scala.concurrent.{ExecutionContext, Promise, Future}
import scala.collection.mutable
import scala.util.{Failure, Success}

import akka.actor.ActorSystem
import com.yammer.metrics.core.Clock
import org.slf4j.LoggerFactory

import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.{NetlinkCallbackDispatcher, DeduplicationActor}
import org.midonet.netlink.Callback
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.netlink.exceptions.NetlinkException.ErrorCode.ENOENT
import org.midonet.netlink.exceptions.NetlinkException.ErrorCode.EEXIST
import org.midonet.netlink.exceptions.NetlinkException.ErrorCode.EBUSY
import org.midonet.odp.{FlowMatches, Packet, DpPort, Datapath}
import org.midonet.util.BatchCollector

class UpcallDatapathConnectionManager(val config: MidolmanConfig) {
    private val log = LoggerFactory.getLogger(this.getClass)

    private val portToChannel = mutable.Map[(Datapath, Int), ManagedDatapathConnection]()

    def portCreated(datapath: Datapath, port: DpPort, conn: ManagedDatapathConnection) {
        portToChannel.put((datapath, port.getPortNo), conn)
    }

    def portDeleted(datapath: Datapath, port: DpPort) {
        portToChannel.remove((datapath, port.getPortNo))
    }

    def createAndHookDpPort(datapath: Datapath, port: DpPort)(
            implicit ec: ExecutionContext, as: ActorSystem):
            Future[(Datapath, DpPort, ManagedDatapathConnection)] = {

        val connName = "port-upcall-" + port.getName
        log.info("creating datapath connection for {}", port.getName)
        val conn = new SelectorBasedDatapathConnection(connName, config, true)

        val (initCb, initFuture) = newCallbackBackedFuture[java.lang.Boolean]()
        conn.start(initCb)

        val dpConn = conn.getConnection

        initFuture flatMap { res =>
            val (createCb, createFuture) = newCallbackBackedFuture[DpPort]()
            dpConn.setCallbackDispatcher(NetlinkCallbackDispatcher.makeBatchCollector())
            dpConn.datapathsSetNotificationHandler(makeUpcallHandler())
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
                    case Success(v) => conn.stop()
                } map { _ => true }
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

    protected def makeUpcallHandler()(implicit as: ActorSystem) = new BatchCollector[Packet] {
        val BATCH_SIZE = 16
        var packets = new Array[Packet](BATCH_SIZE)
        var cursor = 0
        val log = LoggerFactory.getLogger("PacketInHook")

        override def endBatch() {
            if (cursor > 0) {
                log.trace("batch of {} packets", cursor)
                DeduplicationActor ! DeduplicationActor.HandlePackets(packets)
                packets = new Array[Packet](BATCH_SIZE)
                cursor = 0
            }
        }

        override def submit(data: Packet) {
            log.trace("accumulating packet: {}", data.getMatch)
            val eth = data.getPacket
            FlowMatches.addUserspaceKeys(eth, data.getMatch)
            data.setStartTimeNanos(Clock.defaultClock().tick())
            packets(cursor) = data
            cursor += 1
            if (cursor == BATCH_SIZE)
                endBatch()
        }
    }

}

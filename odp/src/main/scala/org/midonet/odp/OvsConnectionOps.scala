/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.odp

import scala.collection.JavaConversions.asScalaSet
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise

import org.midonet.netlink.Callback
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.odp.ports.NetDevPort
import org.midonet.odp.protos.OvsDatapathConnection
import org.midonet.util.BatchCollector

class OvsConnectionOps(val ovsCon: OvsDatapathConnection) {

    import OvsConnectionOps._

    def enumDps()(implicit ec: ExecutionContext) =
        toFuture[java.util.Set[Datapath]]{ ovsCon datapathsEnumerate _ } map { _ toSet }

    def createDp(name: String) =
        toFuture[Datapath] { ovsCon datapathsCreate(name, _) }

    def getDp(name: String) =
        toFuture[Datapath] { ovsCon datapathsGet(name, _) }

    def delDp(name: String) =
        toFuture[Datapath] { ovsCon datapathsDelete(name, _) }

    def enumPorts(dp: Datapath)(implicit ec: ExecutionContext) =
        toFuture[java.util.Set[DpPort]] { ovsCon portsEnumerate(dp, _) } map { _ toSet }

    def createPort(port: DpPort, dp: Datapath) =
        toFuture[DpPort] { ovsCon portsCreate(dp, port, _) }

    def setPort(port: DpPort, dp: Datapath) =
        toFuture[DpPort] { ovsCon portsSet(port, dp, _) }

    def getPort(name: String, dp: Datapath) =
        toFuture[DpPort] { ovsCon portsGet(name, dp, _) }

    def delPort(port: DpPort, dp: Datapath) =
        toFuture[DpPort] { ovsCon portsDelete(port, dp, _) }

    def enumFlows(dp: Datapath)(implicit ec: ExecutionContext) =
        toFuture[java.util.Set[Flow]] { ovsCon flowsEnumerate(dp, _) } map { _ toSet }

    def flushFlows(dp: Datapath) =
        toFuture[java.lang.Boolean] { ovsCon flowsFlush(dp, _) }

    def createFlow(flow: Flow, dp: Datapath) =
        toFuture[Flow] { ovsCon flowsCreate(dp, flow, _) }

    def getFlow(flowMatch: FlowMatch, dp: Datapath) =
        toFuture[Flow] { ovsCon flowsGet(dp, flowMatch, _) }

    def delFlow(flow: Flow, dp: Datapath) =
        toFuture[Flow] { ovsCon flowsDelete(dp, flow, _) }

    def execPacket(packet: Packet, dp: Datapath) =
        toFuture[java.lang.Boolean] { ovsCon packetsExecute(dp, packet, _) }

    def firePacket(packet: Packet, dp: Datapath) =
        ovsCon packetsExecute(dp, packet, null)

    def ensureDp(name: String)(implicit ec: ExecutionContext) =
        getDp(name) recoverWith { case ex => createDp(name) }

    def ensureNetDevPort(name: String, dp: Datapath)
                        (implicit ec: ExecutionContext) =
        getPort(name, dp) recoverWith {
            case ex => createPort(new NetDevPort(name), dp)
        }

    def setHandler(dp: Datapath, handler: BatchCollector[Packet] = NoOpHandler)
                  (implicit ec: ExecutionContext) = {
        ovsCon.datapathsSetNotificationHandler(handler)
        Future.successful(true)
    }
}

object OvsConnectionOps {

    def callback[T](p: Promise[T]) = new Callback[T] {
        def onSuccess(dp: T) { p.trySuccess(dp) }
        def onTimeout() { p.tryFailure(new Exception("timeout exception")) }
        def onError(ex: NetlinkException) { p.tryFailure(ex) }
    }

    def toFuture[T](action: Callback[T] => Unit): Future[T] = {
        val p = Promise[T]()
        action(callback(p))
        p.future
    }

    def callbackBackedFuture[T](): (Callback[T], Future[T]) = {
        val p = Promise[T]()
        (callback(p), p.future)
    }

    object NoOpHandler extends BatchCollector[Packet] {
        def submit(p: Packet) { }
        def endBatch() { }
    }

}

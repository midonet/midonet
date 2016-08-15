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
package org.midonet.odp

import java.util.{List => JList}
import scala.collection.JavaConversions.asScalaSet
import scala.collection.JavaConverters._
import scala.concurrent._
import scala.concurrent.duration._

import org.midonet.netlink.Callback
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.odp.flows._
import org.midonet.odp.ports.NetDevPort
import org.midonet.odp.protos.OvsDatapathConnection
import org.midonet.packets.{MAC, IPv4Addr}
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

    def createFlow(flow: Flow, dp: Datapath): Future[Flow] =
        toFuture[Flow] { ovsCon flowsCreate(dp, flow, _) }

    def getFlow(flowMatch: FlowMatch, dp: Datapath): Future[Flow] =
        toFuture[Flow] { ovsCon flowsGet(dp, flowMatch, _) }

    def delFlow(flow: Flow, dp: Datapath) =
        toFuture[Flow] { ovsCon flowsDelete(dp, flow.getMatch.getKeys, _) }

    def execPacket(packet: Packet, actions: JList[FlowAction], dp: Datapath) =
        toFuture[java.lang.Boolean] {
            ovsCon packetsExecute(dp, packet, actions, _)
        }

    def firePacket(packet: Packet, actions: JList[FlowAction], dp: Datapath) =
        ovsCon packetsExecute(dp, packet, actions, null)

    def ensureDp(name: String)(implicit ec: ExecutionContext) =
        getDp(name) recoverWith { case ex => createDp(name) }

    def ensureNetDevPort(name: String, dp: Datapath)
                        (implicit ec: ExecutionContext) =
        getPort(name, dp) recoverWith {
            case ex => createPort(new NetDevPort(name), dp)
        }
}

object OvsConnectionOps {

    def callback[T](p: Promise[T]) = new Callback[T] {
        def onSuccess(dp: T) { p.trySuccess(dp) }
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
        override def submit(p: Packet) = true
        override def endBatch() { }
    }

    def prepareDatapath(dpName: String, ifName: String)
                       (implicit ec: ExecutionContext) = {
        val con = new OvsConnectionOps(DatapathClient.createConnection())

        val dpF = con.ensureDp(dpName)
        Await.result(dpF flatMap{ con.ensureNetDevPort(ifName, _) }, 2 seconds)

        (con, Await.result(dpF, 2 seconds))
    }

}

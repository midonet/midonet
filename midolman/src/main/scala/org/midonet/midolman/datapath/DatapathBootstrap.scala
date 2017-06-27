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

package org.midonet.midolman.datapath

import java.nio.ByteBuffer

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.control.NonFatal

import com.codahale.metrics.MetricRegistry

import org.midonet.ErrorCode
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.flows.NativeFlowMatchList
import org.midonet.midolman.io.SelectorBasedDatapathConnection
import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.netlink._
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.netlink.rtnetlink.{LinkOps, NeighOps}
import org.midonet.odp.OpenVSwitch.Flow.Attr
import org.midonet.odp._

object DatapathBootstrap extends MidolmanLogging {

    override def logSource = "org.midonet.datapath-control.bootstrap"

    class DatapathBootstrapError(msg: String, cause: Throwable)
        extends Exception(msg, cause)

    def bootstrap(
            config: MidolmanConfig,
            channelFactory: NetlinkChannelFactory,
            families: OvsNetlinkFamilies): Datapath = {
        val channel = channelFactory.create(blocking = false)
        val writer = new NetlinkBlockingWriter(channel)
        val reader = new NetlinkTimeoutReader(channel, 1 minute)
        val buf = BytesUtil.instance.allocate(2 * 1024)
        val protocol = new OvsProtocol(channel.getLocalAddress.getPid, families)
        try {
            if (config.reclaimDatapath) {
                log.debug(s"Reclaiming recirc veths.")
                reclaimRecircVeth(config)
                log.debug(s"Reclaiming datapath ${config.datapathName}")
                reclaimDatapath(protocol, config.datapathName, buf, reader, writer)
            } else {
                log.debug(s"Recreating recirc veth interfaces.")
                recreateRecircVeth(config)
                log.debug(s"Recreating datapath ${config.datapathName}")
                recreateDatapath(protocol, config.datapathName, buf, reader, writer)
            }
        } finally {
            channel.close()
        }
    }

    def reclaimFlows(datapath: Datapath,
                     config: MidolmanConfig,
                     metrics: MetricRegistry): NativeFlowMatchList = {
        log.debug(s"Reclaiming existing flows in the datapath ${datapath.getName}")
        val conn = new SelectorBasedDatapathConnection(
            datapath.getName, config, metrics)
        conn.start()
        val dpConn = conn.getConnection
        val ops = new OvsConnectionOps(dpConn)
        val flowList = new NativeFlowMatchList()
        val flowMatchExtractor = new FlowMatchExtractor(flowList)
        val flowListFuture = ops.iterFlows(datapath, flowMatchExtractor)
        try {
            val numFlows = Await.result(flowListFuture, 10 seconds)
            log.debug(s"$numFlows flows retrieved and stored successfully.")
        } catch {
            case NonFatal(e) =>
                log.warn(s"Failed to retrieve existing flows from datapath, " +
                         s"flushing flows.", e)
                val flushFlowsFuture = ops.flushFlows(datapath)
                Await.result(flushFlowsFuture, 10 seconds)
        } finally {
            conn.stop()
        }
        flowList
    }

    private class FlowMatchExtractor(flowList: NativeFlowMatchList)
        extends Reader[Object] with AttributeHandler {

        override def deserializeFrom(buf: ByteBuffer): Object = {
            buf.getInt() // read datapath index
            NetlinkMessage.scanAttributes(buf, this)
            null
        }

        override def use(buf: ByteBuffer,
                         id: Short): Unit = {
            NetlinkMessage.unnest(id) match {
                case Attr.Key =>
                    // The attribute is a flow match, push the buffer offheap
                    flowList.pushFlowMatch(buf)
                case _ => // ignore
            }
        }
    }

    private def reclaimDatapath(protocol: OvsProtocol,
                                datapathName: String,
                                buf: ByteBuffer,
                                reader: NetlinkTimeoutReader,
                                writer: NetlinkBlockingWriter): Datapath = {
        protocol.prepareDatapathGet(0, datapathName, buf)
        try {
            writeAndRead(writer, reader, buf)
            parseDatapath(buf)
        } catch {
            case t: NetlinkException if isMissing(t) =>
                createDatapath(protocol, datapathName, buf, reader, writer)
            case t: Throwable =>
                log.warn("Datapath exists, but failed to reclaim it. Try " +
                         "recreating as fallback.")
                recreateDatapath(protocol, datapathName, buf, reader, writer)
        }
    }

    private def recreateDatapath(protocol: OvsProtocol,
                                 datapathName: String,
                                 buf: ByteBuffer,
                                 reader: NetlinkTimeoutReader,
                                 writer: NetlinkBlockingWriter): Datapath = {
        protocol.prepareDatapathDel(0, datapathName, buf)
        try {
            writeAndRead(writer, reader, buf)
        } catch {
            case t: NetlinkException if isMissing(t) => // ignore
            case t: Throwable =>
                throw new DatapathBootstrapError(
                    "Datapath exists but failed to delete it during recreation.", t)
        }
        createDatapath(protocol, datapathName, buf, reader, writer)
    }

    private def createDatapath(protocol: OvsProtocol,
                               datapathName: String,
                               buf: ByteBuffer,
                               reader: NetlinkTimeoutReader,
                               writer: NetlinkBlockingWriter): Datapath = {
        buf.clear()
        protocol.prepareDatapathCreate(datapathName, buf)
        try {
            writeAndRead(writer, reader, buf)
        } catch {
            case t: Throwable =>
                throw new DatapathBootstrapError(
                    "Failed to create the datapath", t)
        }
        parseDatapath(buf)
    }

    private def parseDatapath(buf: ByteBuffer): Datapath =
        try {
            buf.position(NetlinkMessage.GENL_HEADER_SIZE)
            Datapath.buildFrom(buf)
        } catch {
            case t: Throwable =>
                throw new DatapathBootstrapError(
                    "Failed to parse the datapath", t)
        }

    private def isMissing(ex: NetlinkException) = ex.getErrorCodeEnum match {
        case ErrorCode.ENODEV | ErrorCode.ENOENT | ErrorCode.ENXIO => true
        case _ => false
    }

    private def writeAndRead(
            writer: NetlinkBlockingWriter,
            reader: NetlinkTimeoutReader,
            buf: ByteBuffer): Unit = {
        writer.write(buf)
        buf.clear()
        reader.read(buf)
        buf.flip()
    }

    private def recreateRecircVeth(config: MidolmanConfig): Unit = {
        val recircConfig = config.datapath.recircConfig
        try {
            LinkOps.deleteLink(recircConfig.recircHostName)
        } catch { case NonFatal(ignored) => }
        createRecircVeth(config)
    }

    private def reclaimRecircVeth(config: MidolmanConfig): Unit = {
        val recircConfig = config.datapath.recircConfig
        try {
            LinkOps.getLinkByName(recircConfig.recircHostName)
        } catch {
            case NonFatal(_) =>
                log.debug("Non-existing recirc veth, create it.")
                createRecircVeth(config)
        }
    }

    private def createRecircVeth(config: MidolmanConfig): Unit = {
        val recircConfig = config.datapath.recircConfig
        val LinkOps.Veth(hostSide, _) = LinkOps.createVethPair(
            recircConfig.recircHostName,
            recircConfig.recircMnName,
            up = true,
            recircConfig.recircHostMac,
            recircConfig.recircMnMac)
        LinkOps.setAddress(
            hostSide,
            recircConfig.recircHostAddr.subnet(recircConfig.subnet.getPrefixLen))
        NeighOps.addNeighEntry(
            hostSide,
            recircConfig.recircMnAddr,
            recircConfig.recircMnMac)
    }
}

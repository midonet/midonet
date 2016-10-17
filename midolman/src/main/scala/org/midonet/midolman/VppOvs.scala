/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.midolman

import scala.concurrent.duration._

import java.nio.ByteBuffer
import java.util.ArrayList
import java.util.concurrent.TimeUnit

import org.midonet.midolman.logging.MidolmanLogging
import org.midonet.netlink.{BytesUtil, NetlinkChannelFactory, NetlinkMessage}
import org.midonet.netlink.{NetlinkBlockingWriter, NetlinkTimeoutReader, NLFlag}
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.odp.{Datapath, DpPort, Flow}
import org.midonet.odp.{FlowMask, FlowMatch, OvsNetlinkFamilies, OvsProtocol}
import org.midonet.odp.flows.{FlowAction, FlowActions}
import org.midonet.odp.flows.{FlowKeys, FlowKeyEtherType}
import org.midonet.odp.ports.NetDevPort
import org.midonet.packets.IPv6Addr

object VppOvs {
    val NullMac = Array[Byte](0, 0, 0, 0, 0, 0)
    val NullIpv6 = IPv6Addr(0L, 0L)
    val NoProtocol = 0.toByte
}

class VppOvs(dp: Datapath) extends MidolmanLogging {
    import VppOvs._

    override def logSource = s"org.midonet.vpp"

    val channel = new NetlinkChannelFactory().create(blocking = false)
    val families = OvsNetlinkFamilies.discover(channel)
    val proto = new OvsProtocol(channel.getLocalAddress.getPid, families)
    val writer = new NetlinkBlockingWriter(channel)
    val reader = new NetlinkTimeoutReader(channel,
                                          Duration(60, TimeUnit.SECONDS))
    val buf = BytesUtil.instance.allocateDirect(2 * 1024 * 1024)
    val flowMatch = new FlowMatch()
    val fmask = new FlowMask()
    val actions = new ArrayList[FlowAction]

    var seq = 0

    private def writeRead[T](buf: ByteBuffer, f: ByteBuffer => T): T = {
        seq += 1

        buf.putInt(NetlinkMessage.NLMSG_SEQ_OFFSET, seq)
        writer.write(buf)

        // read messages until we find the correct response
        buf.clear()
        reader.read(buf)
        var i = 0
        while (buf.getInt(i + NetlinkMessage.NLMSG_SEQ_OFFSET) != seq) {
            i += buf.getInt(i + NetlinkMessage.NLMSG_LEN_OFFSET)

            if (i >= buf.position) {
                buf.clear()
                reader.read(buf)
                i = 0
            }
        }

        buf.position(i + NetlinkMessage.GENL_HEADER_SIZE)
        buf.limit(i + buf.getInt(i + NetlinkMessage.NLMSG_LEN_OFFSET))
        val deserialized = f(buf)
        buf.clear()
        deserialized
    }

    private def createFlow(dp: Datapath,
                           fmatch: FlowMatch,
                           mask: FlowMask,
                           actions: java.util.List[FlowAction]): Unit = {
        buf.clear()
        proto.prepareFlowCreate(
            dp.getIndex, fmatch.getKeys, actions, mask, buf, NLFlag.ACK)
        writeRead(buf, Flow.deserializer.deserializeFrom)
    }

    private def deleteFlow(dp: Datapath, fmatch: FlowMatch): Unit = {
        buf.clear()
        proto.prepareFlowDelete(dp.getIndex, fmatch.getKeys, buf)
        writeRead(buf, Flow.buildFrom)
    }

    private def buildFlowMatch(inputPort: Int) = {
        flowMatch.clear()
        flowMatch.addKey(FlowKeys.inPort(inputPort))
        flowMatch.addKey(FlowKeys.etherType(
                             FlowKeyEtherType.Type.ETH_P_IPV6.value.toShort))
        flowMatch.addKey(FlowKeys.ethernet(NullMac, NullMac))
        flowMatch.addKey(FlowKeys.ipv6(NullIpv6, NullIpv6, NoProtocol))
        flowMatch
    }

    def createDpPort(portName: String): DpPort = {
        buf.clear()
        val port = new NetDevPort(portName)
        proto.prepareDpPortCreate(dp.getIndex, port, buf)
        writeRead[DpPort](buf, DpPort.deserializer.deserializeFrom)
    }

    def deleteDpPort(port: DpPort): Unit = {
        buf.clear()
        proto.prepareDpPortDelete(dp.getIndex, port, buf)
        writeRead(buf, identity)
    }

    def addIpv6Flow(inputPort: Int, outputPort: Int): Unit = {
        buf.clear()
        fmask.clear()
        actions.clear()

        val fmatch = buildFlowMatch(inputPort)
        actions.add(FlowActions.output(outputPort))

        fmask.calculateFor(fmatch, actions)
        createFlow(dp, fmatch, fmask, actions)
    }

    def clearIpv6Flow(inputPort: Int, outputPort: Int): Unit = {
        buf.clear()

        val fmatch = buildFlowMatch(inputPort)
        deleteFlow(dp, fmatch)
    }
}


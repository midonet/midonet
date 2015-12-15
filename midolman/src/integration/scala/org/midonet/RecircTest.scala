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

package org.midonet

import java.nio.channels.spi.SelectorProvider
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

import scala.util.control.NonFatal

import com.codahale.metrics.MetricRegistry
import com.typesafe.config.ConfigFactory
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.{FlowTranslator, DatapathState}
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.datapath.DisruptorDatapathChannel.PacketContextHolder
import org.midonet.midolman.datapath.{PacketExecutor, FlowProcessor}
import org.midonet.midolman.flows.ManagedFlow
import org.midonet.midolman.monitoring.metrics.PacketPipelineMetrics
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.midolman.UnderlayResolver.Route
import org.midonet.midolman.simulation.PacketContext
import org.midonet.midolman.simulation.Simulator.ToPortAction
import org.midonet.netlink._
import org.midonet.netlink.rtnetlink.{NeighOps, Link, LinkOps}
import org.midonet.odp._
import org.midonet.odp.FlowMatch.Field
import org.midonet.odp.flows.{FlowKeys, FlowActionOutput}
import org.midonet.odp.ports.{VxLanTunnelPort, NetDevPort}
import org.midonet.odp.util.TapWrapper
import org.midonet.packets._
import org.midonet.packets.util.PacketBuilder._
import org.midonet.util.concurrent.NanoClock

@RunWith(classOf[JUnitRunner])
class RecircTest extends FeatureSpec
                 with BeforeAndAfterAll
                 with Matchers {
    val dpName = "recirc-test"
    val tapName = "output"

    val recircHostAddr = IPv4Subnet.fromString("80.254.123.129/30", "/")
    val recircMnAddr = IPv4Addr("80.254.123.130")

    val config = new MidolmanConfig(ConfigFactory.parseString(
        s"""
           |agent.datapath.addr : "${recircHostAddr.toString}"
           |agent.datapath.peerAddr : "${recircMnAddr.toString}"
           |agent.datapath.vxlan_recirculate_udp_port : "8899"
            """.stripMargin))

    val buf = BytesUtil.instance.allocateDirect(512)

    val channelFactory = new NetlinkChannelFactory()

    var tapWrapper: TapWrapper = _
    var channel: NetlinkChannel = _
    var families: OvsNetlinkFamilies = _
    var protocol: OvsProtocol = _
    var writer: NetlinkWriter = _
    var reader: NetlinkReader = _
    var datapath: Datapath = _
    var dpState: DatapathState = _
    var translator: FlowTranslator = _
    var flowProcessor: FlowProcessor = _
    var packetExecutor : PacketExecutor  = _

    var tapDpPort: DpPort = _
    var vxlanRecirdPort: DpPort = _
    var recircPort: DpPort = _

    var veth: Link = _

    val tapVport = UUID.randomUUID()

    override def beforeAll(): Unit = {
        channel = channelFactory.create(blocking = true)
        families = OvsNetlinkFamilies.discover(channel)
        protocol = new OvsProtocol(channel.getLocalAddress.getPid,
                                   families)
        writer = new NetlinkWriter(channel)
        reader = new NetlinkReader(channel)

        protocol.prepareDatapathCreate(dpName, buf)
        datapath = NetlinkUtil.rpc(buf, writer, reader, Datapath.buildFrom)

        tapWrapper = try {
            new TapWrapper(tapName)
        } catch { case NonFatal(e) =>
            new TapWrapper(tapName, false)
        }

        protocol.prepareDpPortCreate(
            datapath.getIndex,
            new NetDevPort(tapWrapper.getName),
            buf)
        tapDpPort = NetlinkUtil.rpc(buf, writer, reader, DpPort.buildFrom)

        val LinkOps.Veth(hostSide, _) = LinkOps.createVethPair(
            config.datapath.recircHostName,
            config.datapath.recircMnName,
            up = true,
            config.datapath.recircHostMac,
            config.datapath.recircMnMac)
        LinkOps.setAddress(hostSide, config.datapath.recircHostCidr)
        NeighOps.addNeighEntry(
            hostSide,
            config.datapath.recircMnAddr,
            config.datapath.recircMnMac)
        veth = hostSide

        protocol.prepareDpPortCreate(
            datapath.getIndex,
            VxLanTunnelPort.make(
                "tnvxlan-recirc", config.datapath.vxlanRecirculateUdpPort),
            buf)
        vxlanRecirdPort = NetlinkUtil.rpc(buf, writer, reader, DpPort.buildFrom)

        protocol.prepareDpPortCreate(
            datapath.getIndex,
            new NetDevPort(config.datapath.recircMnName),
            buf)
        recircPort = NetlinkUtil.rpc(buf, writer, reader, DpPort.buildFrom)

        dpState = new DatapathState {
            override def getVportForDpPortNumber(portNum: Integer): UUID = null
            override def dpPortForTunnelKey(tunnelKey: Long): DpPort = null
            override def peerTunnelInfo(peer: UUID): Option[Route] = null
            override def isVtepTunnellingPort(portNumber: Integer): Boolean = false
            override def isOverlayTunnellingPort(portNumber: Integer): Boolean = false
            override def vtepTunnellingOutputAction: FlowActionOutput = null

            override def getDpPortNumberForVport(vportId: UUID): Integer =
                Map(tapVport -> tapDpPort.getPortNo).get(vportId).orNull

            override def datapath: Datapath = RecircTest.this.datapath
            override def tunnelRecircVxLan = vxlanRecirdPort.asInstanceOf[VxLanTunnelPort]
            override def hostRecircPort = recircPort.asInstanceOf[NetDevPort]
            override def recircTunnelOutputAction = vxlanRecirdPort.toOutputAction
            override def hostRecircOutputAction = recircPort.toOutputAction
        }

        translator = new FlowTranslator {
            override protected val hostId = UUID.randomUUID()

            override protected val numWorkers: Int = 1
            override protected val workerId: Int = 0

            override protected val dpState = RecircTest.this.dpState
            override protected val config: MidolmanConfig = RecircTest.this.config

            override implicit protected def vt: VirtualTopology = null
        }

        flowProcessor = new FlowProcessor(
            dpState,
            families,
            maxPendingRequests = 1,
            maxRequestSize = 1024,
            channelFactory,
            SelectorProvider.provider(),
            NanoClock.DEFAULT)
        packetExecutor = new PacketExecutor(
            dpState,
            families,
            numHandlers = 1,
            index = 0,
            channelFactory,
            new PacketPipelineMetrics(new MetricRegistry, 1),
            executesRecircPackets = true)
    }

    override def afterAll(): Unit = {
        buf.clear()
        protocol.prepareDatapathDel(datapath.getIndex, null, buf)
        writer.write(buf)
        tapWrapper.remove()
        LinkOps.deleteLink(veth)
    }

    feature("Packets are recirculated") {
        scenario ("For encapsulation") {
            val innerSrcMac = MAC.random()
            val innerDstMac = MAC.random()
            val innerSrcIp = IPv4Addr.random
            val innerDstIp = IPv4Addr.random
            val packet =
                { eth src innerSrcMac dst innerDstMac } <<
                    { ip4 src innerSrcIp dst innerDstIp } <<
                        { tcp src 123 dst 456 }
            val fmatch = FlowMatches.fromEthernetPacket(packet)
            fmatch.addKey(FlowKeys.inPort(tapDpPort.getPortNo))
            fmatch.fieldSeen(Field.SrcPort)
            fmatch.fieldSeen(Field.DstPort)
            val context = new PacketContext(1L, new Packet(packet, fmatch), fmatch)
            context.wcmatch.setSrcPort(9876)
            context.wcmatch.setDstPort(80)

            val vni = 4624
            val encapSrcMac = MAC.random()
            val encapDstMac = MAC.random()
            val encapSrcIp = IPv4Addr.random
            val encapDstIp = IPv4Addr.random
            val encapSrcUdp = ThreadLocalRandom.current().nextInt() >>> 16
            context.encap(
                vni,
                encapSrcMac,
                encapDstMac,
                encapSrcIp,
                encapDstIp,
                0,
                24,
                encapSrcUdp,
                UDP.VXLAN)

            context.wcmatch.setNetworkTTL(12)
            context.calculateActionsFromMatchDiff()
            context.addVirtualAction(ToPortAction(tapVport))

            translator.translateActions(context)

            context.flow = new ManagedFlow(null)
            val holder = new PacketContextHolder(context, context)

            flowProcessor.onEvent(holder, 0, endOfBatch = true)
            packetExecutor.onEvent(holder, 0, endOfBatch = true)

            val outerEth = Ethernet.deserialize(tapWrapper.recv())
            outerEth.getSourceMACAddress should be (encapSrcMac)
            outerEth.getDestinationMACAddress should be (encapDstMac)
            val outerIp = outerEth.getPayload.asInstanceOf[IPv4]
            outerIp.getSourceIPAddress should be (encapSrcIp)
            outerIp.getDestinationIPAddress should be (encapDstIp)
            outerIp.getTtl should be (12)
            val outerUdp = outerIp.getPayload.asInstanceOf[UDP]
            outerUdp.getSourcePort should be (encapSrcUdp)
            outerUdp.getDestinationPort should be (UDP.VXLAN)
            val vxlan = outerUdp.getPayload.asInstanceOf[VXLAN]
            vxlan.getVni should be (vni)
            val innerEth = vxlan.getPayload.asInstanceOf[Ethernet]
            innerEth.getSourceMACAddress should be (innerSrcMac)
            innerEth.getDestinationMACAddress should be (innerDstMac)
            val innerIp = innerEth.getPayload.asInstanceOf[IPv4]
            innerIp.getSourceIPAddress should be (innerSrcIp)
            innerIp.getDestinationIPAddress should be (innerDstIp)
            val innerTcp = innerIp.getPayload.asInstanceOf[TCP]
            innerTcp.getSourcePort should be (9876)
            innerTcp.getDestinationPort should be (80)
        }

        scenario ("For deencapsulation") {
            val vni = 4624
            val encapSrcMac = MAC.random()
            val encapDstMac = MAC.random()
            val encapSrcIp = IPv4Addr.random
            val encapDstIp = IPv4Addr.random
            val encapSrcUdp = ThreadLocalRandom.current().nextInt() >>> 16
            val encapDstUdp = UDP.VXLAN
            val innerSrcMac = MAC.random()
            val innerDstMac = MAC.random()
            val innerSrcIp = IPv4Addr.random
            val innerDstIp = IPv4Addr.random

            val packet =
                { eth src encapSrcMac dst encapDstMac } <<
                    { ip4 src encapSrcIp dst encapDstIp } <<
                        { udp src encapSrcUdp.toShort dst encapDstUdp.toShort } <<
                             { vxlan vni vni } <<
                                { eth src innerSrcMac dst innerDstMac } <<
                                    { ip4 src innerSrcIp dst innerDstIp } <<
                                        { tcp src 123 dst 456 }
            val inner = packet.packet
                .getPayload.asInstanceOf[IPv4]
                .getPayload.asInstanceOf[UDP]
                .getPayload.asInstanceOf[VXLAN]
                .getPayload.asInstanceOf[Ethernet]

            val fmatch = FlowMatches.fromEthernetPacket(packet)
            fmatch.addKey(FlowKeys.inPort(tapDpPort.getPortNo))
            val context = new PacketContext(1L, new Packet(packet, fmatch), fmatch)

            context.decap(inner, vni)

            context.wcmatch.fieldSeen(Field.SrcPort)
            context.wcmatch.setSrcPort(9876)
            context.wcmatch.setDstPort(80)
            context.calculateActionsFromMatchDiff()
            context.addVirtualAction(ToPortAction(tapVport))
            context.origMatch.fieldSeen(Field.EtherType)
            context.origMatch.fieldSeen(Field.NetworkProto)

            translator.translateActions(context)

            context.flow = new ManagedFlow(null)
            val holder = new PacketContextHolder(context, context)

            flowProcessor.onEvent(holder, 0, endOfBatch = true)
            packetExecutor.onEvent(holder, 0, endOfBatch = true)

            val innerEth = Ethernet.deserialize(tapWrapper.recv())
            innerEth.getSourceMACAddress should be (innerSrcMac)
            innerEth.getDestinationMACAddress should be (innerDstMac)
            val innerIp = innerEth.getPayload.asInstanceOf[IPv4]
            innerIp.getSourceIPAddress should be (innerSrcIp)
            innerIp.getDestinationIPAddress should be (innerDstIp)
            val innerTcp = innerIp.getPayload.asInstanceOf[TCP]
            innerTcp.getSourcePort should be (9876)
            innerTcp.getDestinationPort should be (80)
        }
    }
}

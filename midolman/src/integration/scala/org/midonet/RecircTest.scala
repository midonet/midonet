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
import java.util.{Arrays, UUID}
import java.util.concurrent.ThreadLocalRandom

import scala.util.control.NonFatal
import scala.util.control.Exception._

import com.codahale.metrics.MetricRegistry
import com.typesafe.config.ConfigFactory
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.{ShardedSimulationBackChannel, FlowTranslator, DatapathState}
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.datapath.DisruptorDatapathChannel.PacketContextHolder
import org.midonet.midolman.datapath.{PacketExecutor, FlowProcessor}
import org.midonet.midolman.flows.ManagedFlow
import org.midonet.midolman.monitoring.metrics.DatapathMetrics
import org.midonet.midolman.monitoring.metrics.PacketExecutorMetrics
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.midolman.UnderlayResolver.Route
import org.midonet.midolman.simulation.PacketContext
import org.midonet.midolman.simulation.Simulator.ToPortAction
import org.midonet.netlink._
import org.midonet.netlink.rtnetlink.{NeighOps, Link, LinkOps}
import org.midonet.odp._
import org.midonet.odp.FlowMatch.Field
import org.midonet.odp.flows.{FlowActions, FlowKeys, FlowActionOutput}
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

    val config = new MidolmanConfig(ConfigFactory.parseString(
        s"""
           |agent.datapath.recirc_cidr : "80.254.123.0/24"
           |agent.datapath.vxlan_recirculate_udp_port : "8899"
            """.stripMargin))

    val recircConfig = config.datapath.recircConfig

    val buf = BytesUtil.instance.allocateDirect(512)

    val channelFactory = new NetlinkChannelFactory()

    var tapWrapper: TapWrapper = _
    var families: OvsNetlinkFamilies = _
    var channel: NetlinkChannel = _
    var protocol: OvsProtocol = _
    var writer: NetlinkWriter = _
    var reader: NetlinkReader = _
    var datapath: Datapath = _
    var dpState: DatapathState = _
    var translator: FlowTranslator = _
    var flowProcessor: FlowProcessor = _
    var packetExecutor : PacketExecutor  = _

    var tapDpPort: DpPort = _
    var vxlanRecircPort: DpPort = _
    var recircPort: DpPort = _

    var veth: Link = _

    var injectorInput: DpPort = _
    var injectorOutput: DpPort = _
    var injector: Link = _

    val tapVport = UUID.randomUUID()

    override def beforeAll(): Unit = {
        channel = channelFactory.create(blocking = true)
        writer = new NetlinkWriter(channel)
        reader = new NetlinkReader(channel)
        families = OvsNetlinkFamilies.discover(channel)
        protocol = new OvsProtocol(channel.getLocalAddress.getPid,
                                   families)

        cleanup()

        protocol.prepareDatapathCreate(dpName, buf)
        datapath = NetlinkUtil.rpc(buf, writer, reader, Datapath.buildFrom)

        tapWrapper = try {
            new TapWrapper(tapName)
        } catch { case NonFatal(e) =>
            new TapWrapper(tapName, false)
        }

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
        veth = hostSide

        val LinkOps.Veth(input, _) = LinkOps.createVethPair(
            "injector-input",
            "injector-output",
            up = true)
        injector = input

        tapDpPort = createPort(new NetDevPort(tapWrapper.getName))
        injectorInput = createPort(new NetDevPort("injector-input"))
        injectorOutput = createPort(new NetDevPort("injector-output"))
        vxlanRecircPort = createPort(VxLanTunnelPort.make(
                "tnvxlan-recirc", config.datapath.vxlanRecirculateUdpPort))
        recircPort = createPort(new NetDevPort(recircConfig.recircMnName))

        dpState = new DatapathState {
            override def getVportForDpPortNumber(portNum: Integer): UUID = null
            override def dpPortForTunnelKey(tunnelKey: Long): DpPort = null
            override def peerTunnelInfo(peer: UUID): Option[Route] = null
            override def isVtepTunnellingPort(portNumber: Int): Boolean = false
            override def isOverlayTunnellingPort(portNumber: Int): Boolean = false
            override def vtepTunnellingOutputAction: FlowActionOutput = null
            override def tunnelFip64VxLanPort: VxLanTunnelPort = null
            override def isFip64TunnellingPort(portNumber: Int): Boolean = false

            override def getDpPortNumberForVport(vportId: UUID): Integer =
                Map(tapVport -> tapDpPort.getPortNo).get(vportId).orNull

            override def datapath: Datapath = RecircTest.this.datapath
            override def tunnelRecircVxLanPort = vxlanRecircPort.asInstanceOf[VxLanTunnelPort]
            override def hostRecircPort = recircPort.asInstanceOf[NetDevPort]
            override def tunnelRecircOutputAction = vxlanRecircPort.toOutputAction
            override def hostRecircOutputAction = recircPort.toOutputAction

            override def setFip64PortKey(port: UUID, key: Int): Unit = {}
            override def clearFip64PortKey(port: UUID, key: Int): Unit = {}
            override def getFip64PortForKey(key: Int): UUID = null
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
            new ShardedSimulationBackChannel(),
            new DatapathMetrics(new MetricRegistry),
            NanoClock.DEFAULT)
        packetExecutor = new PacketExecutor(
            dpState,
            families,
            numHandlers = 1,
            index = 0,
            channelFactory,
            new PacketExecutorMetrics(new MetricRegistry, 1))

        flowProcessor.onStart()
    }

    private def cleanup(): Unit = {
        buf.clear()

        val channel = channelFactory.create(blocking = true)
        try {
            val writer = new NetlinkWriter(channel)
            val families = OvsNetlinkFamilies.discover(channel)
            val protocol = new OvsProtocol(channel.getLocalAddress.getPid,
                                           families)
            protocol.prepareDatapathDel(if (datapath != null) {
                                            datapath.getIndex
                                        } else 0, dpName, buf)
            writer.write(buf)
        } finally {
            channel.close()
            buf.clear()
        }
        ignoring(classOf[Throwable]) {
            LinkOps.deleteLink(recircConfig.recircHostName) }
        ignoring(classOf[Throwable]) {
            LinkOps.deleteLink("injector-input") }
        ignoring(classOf[Throwable]) {
            new TapWrapper(tapName).remove() }
    }

    override def afterAll(): Unit = {
        cleanup()
    }

    feature("Packets are recirculated") {
        scenario ("For encapsulation") {
            for (_ <- 1 until 10000)
                verifyEncap()
         }

        scenario ("For deencapsulation") {
            for (_ <- 1 until 10000)
                verifyDecap()
        }
    }

    private def verifyDecap(): Unit = {
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

        val packetf = () =>
            { eth src encapSrcMac dst encapDstMac } <<
                { ip4 src encapSrcIp dst encapDstIp } <<
                    { udp src encapSrcUdp.toShort dst encapDstUdp.toShort } <<
                         { vxlan vni vni } <<
                            { eth src innerSrcMac dst innerDstMac } <<
                                { ip4 src innerSrcIp dst innerDstIp } <<
                                    { tcp src 123 dst 456 }
        val inner = packetf().packet
            .getPayload.asInstanceOf[IPv4]
            .getPayload.asInstanceOf[UDP]
            .getPayload.asInstanceOf[VXLAN]
            .getPayload.asInstanceOf[Ethernet]

        val fmatch = FlowMatches.fromEthernetPacket(packetf())
        fmatch.addKey(FlowKeys.inPort(injectorInput.getPortNo))
        val context = PacketContext.generated(1L, new Packet(packetf(), fmatch),
                                              fmatch)

        context.decap(inner, vni)

        context.wcmatch.fieldSeen(Field.SrcPort)
        context.wcmatch.setSrcPort(9876)
        context.wcmatch.setDstPort(80)
        context.calculateActionsFromMatchDiff()
        context.addVirtualAction(ToPortAction(tapVport))
        context.origMatch.propagateSeenFieldsFrom(context.wcmatch)

        translator.translateActions(context)

        context.flow = new ManagedFlow(null)
        val holder = new PacketContextHolder(context, context)

        flowProcessor.onEvent(holder, 0, endOfBatch = true)
        packetExecutor.onEvent(holder, 0, endOfBatch = true)

        // From packet execution
        expectDecapPacket(innerSrcMac, innerDstMac, innerSrcIp, innerDstIp)
        // From flow matching
        protocol.preparePacketExecute(
            datapath.getIndex,
            new Packet(packetf(), FlowMatches.fromEthernetPacket(packetf())),
            Arrays.asList(FlowActions.output(injectorOutput.getPortNo)),
            buf)
        writer.write(buf)
        buf.clear()
        expectDecapPacket(innerSrcMac, innerDstMac, innerSrcIp, innerDstIp)
    }

    private def expectDecapPacket(
            innerSrcMac: MAC,
            innerDstMac: MAC,
            innerSrcIp: IPv4Addr,
            innerDstIp: IPv4Addr): Unit = {
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

    private def verifyEncap(): Unit = {
        val innerSrcMac = MAC.random()
        val innerDstMac = MAC.random()
        val innerSrcIp = IPv4Addr.random
        val innerDstIp = IPv4Addr.random
        val packetf = () =>
            { eth src innerSrcMac dst innerDstMac } <<
                { ip4 src innerSrcIp dst innerDstIp } <<
                    { tcp src 123 dst 456 }

        val fmatch = FlowMatches.fromEthernetPacket(packetf())
        fmatch.addKey(FlowKeys.inPort(injectorInput.getPortNo))
        fmatch.fieldSeen(Field.SrcPort)
        fmatch.fieldSeen(Field.DstPort)
        val context = PacketContext.generated(1L, new Packet(packetf(), fmatch),
                                              fmatch)
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

        // From packet execution
        expectEncapPacket(encapSrcMac, encapDstMac, encapSrcIp, encapDstIp,
                          encapSrcUdp, vni, innerSrcMac, innerDstMac,
                          innerSrcIp, innerDstIp)
        // From flow matching
        fmatch.addKey(FlowKeys.inPort(injectorInput.getPortNo))
        protocol.preparePacketExecute(
            datapath.getIndex,
            new Packet(packetf(), FlowMatches.fromEthernetPacket(packetf())),
            Arrays.asList(FlowActions.output(injectorOutput.getPortNo)),
            buf)
        writer.write(buf)
        buf.clear()
        expectEncapPacket(encapSrcMac, encapDstMac, encapSrcIp, encapDstIp,
                          encapSrcUdp, vni, innerSrcMac, innerDstMac,
                          innerSrcIp, innerDstIp)
    }

    private def expectEncapPacket(
            encapSrcMac: MAC,
            encapDstMac: MAC,
            encapSrcIp: IPv4Addr,
            encapDstIp: IPv4Addr,
            encapSrcUdp: Int,
            vni: Int,
            innerSrcMac: MAC,
            innerDstMac: MAC,
            innerSrcIp: IPv4Addr,
            innerDstIp: IPv4Addr): Unit = {
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

    private def createPort(port: DpPort): DpPort = {
        val channel = channelFactory.create(blocking = true)
        val writer = new NetlinkWriter(channel)
        val reader = new NetlinkReader(channel)
        protocol.prepareDpPortCreate(
            datapath.getIndex,
            port,
            buf)
        NetlinkUtil.rpc(buf, writer, reader, DpPort.buildFrom)
    }
}

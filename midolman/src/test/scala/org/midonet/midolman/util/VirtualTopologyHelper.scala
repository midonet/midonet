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
package org.midonet.midolman.util

import java.util.{LinkedList => JLinkedList, List => JList, Queue => JQueue, UUID}

import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.reflect._

import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import akka.testkit.TestActorRef
import akka.util.Timeout
import akka.util.Timeout.durationToTimeout

import com.google.inject.Injector

import org.midonet.midolman.PacketWorkflow.SimulationResult
import org.midonet.midolman.UnderlayResolver.{Route => UnderlayRoute}
import org.midonet.midolman._
import org.midonet.midolman.datapath.DatapathChannel
import org.midonet.midolman.monitoring.{FlowRecorder, FlowRecorderFactory}
import org.midonet.midolman.simulation.{Bridge => SimBridge, Chain => SimChain, DhcpConfigFromNsdb, PacketContext, Port => SimPort, Router => SimRouter, _}
import org.midonet.midolman.state.ConnTrackState._
import org.midonet.midolman.state.NatState.{NatBinding, NatKey}
import org.midonet.midolman.state.TraceState.{TraceContext, TraceKey}
import org.midonet.midolman.state.{ArpRequestBroker, HappyGoLuckyLeaser, MockStateStorage}
import org.midonet.midolman.topology.VirtualToPhysicalMapper.HostRequest
import org.midonet.midolman.topology.VirtualTopology.Device
import org.midonet.midolman.topology.devices.Host
import org.midonet.midolman.topology.{VirtualToPhysicalMapper, VirtualTopology}
import org.midonet.odp._
import org.midonet.odp.flows.{FlowAction, FlowActionOutput, FlowKeys, _}
import org.midonet.odp.ports.InternalPort
import org.midonet.packets.util.AddressConversions._
import org.midonet.packets.{Ethernet, ICMP, IPv4, IPv4Addr, MAC, TCP, UDP}
import org.midonet.sdn.state.{FlowStateTable, FlowStateTransaction, ShardedFlowStateTable}
import org.midonet.util.UnixClock

trait VirtualTopologyHelper { this: MidolmanServices =>

    implicit def actorSystem: ActorSystem
    implicit def executionContext: ExecutionContext
    implicit def injector: Injector

    private implicit val timeout: Timeout = 3 seconds

    def force[T](block: => T)(implicit tag: ClassTag[T]): T =
        try {
            block
        } catch { case NotYetException(f, _) =>
            Await.result(f, 3 seconds)
            force(block)
        }

    def fetchDevice[T <: Device](id: UUID)(implicit tag: ClassTag[T]): T = {
        Await.result(VirtualTopology.get[T](id), timeout.duration)
    }

    def fetchBridges(bridges: UUID*): Seq[SimBridge] =
        bridges map fetchDevice[SimBridge]

    def fetchRouters(routers: UUID*): Seq[SimRouter] =
        routers map fetchDevice[SimRouter]

    def fetchPorts(ports: UUID*): Seq[SimPort] =
        ports map fetchDevice[SimPort]

    def fetchChains(chains: UUID*): Seq[SimChain] =
        chains map fetchDevice[SimChain]

    def fetchHosts(hosts: UUID*): Seq[Host] =
        hosts map fetchDevice[Host]

    def fetchHost(hostId: UUID): Host =
        Await.result(
            ask(VirtualToPhysicalMapper, HostRequest(hostId)),
            timeout.duration).asInstanceOf[Host]

    def fetchPortGroups(portGroups: UUID*): Seq[PortGroup] =
        portGroups map fetchDevice[PortGroup]

    def feedArpTable(router: SimRouter, ip: IPv4Addr, mac: MAC): Unit = {
        ArpCacheHelper.feedArpCache(router, ip, mac)
    }

    def clearMacTable(bridge: SimBridge, vlan: Short, mac: MAC, port: UUID): Unit = {
        bridge.vlanMacTableMap.get(vlan) foreach { _.remove(mac, port) }
    }

    def clearMacTable(bridge: SimBridge, mac: MAC, port: UUID): Unit = {
        clearMacTable(bridge, SimBridge.UntaggedVlanId, mac, port)
    }

    def feedMacTable(bridge: SimBridge, vlan: Short, mac: MAC, port: UUID): Unit = {
        bridge.vlanMacTableMap.get(vlan) foreach { _.add(mac, port) }
    }

    def feedMacTable(bridge: SimBridge, mac: MAC, port: UUID): Unit = {
        feedMacTable(bridge, SimBridge.UntaggedVlanId, mac, port)
    }

    def throwAwayArpBroker(): ArpRequestBroker =
        new ArpRequestBroker(config, simBackChannel, () => { }, UnixClock.MOCK)

    val NO_CONNTRACK = new FlowStateTransaction[ConnTrackKey, ConnTrackValue](new ShardedFlowStateTable[ConnTrackKey, ConnTrackValue](clock).addShard())
    val NO_NAT = new FlowStateTransaction[NatKey, NatBinding](new ShardedFlowStateTable[NatKey, NatBinding](clock).addShard())
    val NO_TRACE = new FlowStateTransaction[TraceKey, TraceContext](new ShardedFlowStateTable[TraceKey, TraceContext](clock).addShard())

    def packetContextFor(frame: Ethernet, inPort: UUID = null,
                         inPortNumber: Int = 0)
                        (implicit conntrackTx: FlowStateTransaction[ConnTrackKey, ConnTrackValue] = NO_CONNTRACK,
                                  natTx: FlowStateTransaction[NatKey, NatBinding] = NO_NAT,
                                  traceTx: FlowStateTransaction[TraceKey, TraceContext] = NO_TRACE,
                                  arpBroker: ArpRequestBroker = throwAwayArpBroker())
    : PacketContext = {
        val fmatch = new FlowMatch(FlowKeys.fromEthernetPacket(frame))
        fmatch.setInputPortNumber(inPortNumber)
        val context = new PacketContext(-1, new Packet(frame, fmatch), fmatch)
        context.reset(simBackChannel, arpBroker)
        context.initialize(conntrackTx, natTx, HappyGoLuckyLeaser, traceTx)
        context.prepareForSimulation()
        context.inputPort = inPort
        context.inPortId = inPort
        context
    }

    def egressPacketContextFor(
            frame: Ethernet,
            egressPort: UUID = null)
            (implicit conntrackTx: FlowStateTransaction[ConnTrackKey, ConnTrackValue] = NO_CONNTRACK,
                      natTx: FlowStateTransaction[NatKey, NatBinding] = NO_NAT,
                      traceTx: FlowStateTransaction[TraceKey, TraceContext] = NO_TRACE,
                      arpBroker: ArpRequestBroker = throwAwayArpBroker())
    : PacketContext = {
        val fmatch = new FlowMatch(FlowKeys.fromEthernetPacket(frame))
        val context = new PacketContext(-1, new Packet(frame, fmatch), fmatch, egressPort)
        context.reset(simBackChannel, arpBroker)
        context.initialize(conntrackTx, natTx, HappyGoLuckyLeaser, traceTx)
        context.prepareForSimulation()
        context
    }

    def simulateDevice(device: ForwardingDevice, frame: Ethernet, inPort: UUID)
                      (implicit conntrackTx: FlowStateTransaction[ConnTrackKey, ConnTrackValue] = NO_CONNTRACK,
                                natTx: FlowStateTransaction[NatKey, NatBinding] = NO_NAT,
                                arpBroker: ArpRequestBroker = throwAwayArpBroker())
    : (PacketContext, SimulationResult) = {
        val context = packetContextFor(frame, inPort)
        force {
            flushTransactions(conntrackTx, natTx)
            context.clear()
            (context, device.process(context))
        }
    }

    def sendPacket(t: (UUID, Ethernet))
                  (implicit conntrackTx: FlowStateTransaction[ConnTrackKey, ConnTrackValue] = NO_CONNTRACK,
                            natTx: FlowStateTransaction[NatKey, NatBinding] = NO_NAT)
    : (SimulationResult, PacketContext) =
        simulate(packetContextFor(t._2, t._1))(conntrackTx, natTx)

    def sendPacket(port: UUID, pkt: Ethernet)
    : (SimulationResult, PacketContext) =
        simulate(packetContextFor(pkt, port))(NO_CONNTRACK, NO_NAT)

    def simulate(pktCtx: PacketContext)
                (implicit conntrackTx: FlowStateTransaction[ConnTrackKey, ConnTrackValue] = NO_CONNTRACK,
                          natTx: FlowStateTransaction[NatKey, NatBinding] = NO_NAT,
                          traceTx: FlowStateTransaction[TraceKey, TraceContext] = NO_TRACE)
    : (SimulationResult, PacketContext) = {
        pktCtx.initialize(conntrackTx, natTx, HappyGoLuckyLeaser, traceTx)
        val r = force {
            flushTransactions(conntrackTx, natTx)
            pktCtx.clear()
            pktCtx.wcmatch.reset(pktCtx.origMatch)
            Simulator.simulate(pktCtx)
        }
        commitTransactions(conntrackTx, natTx)
        flushTransactions(conntrackTx, natTx)
        (r, pktCtx)
    }

    def commitTransactions(conntrackTx: FlowStateTransaction[ConnTrackKey, ConnTrackValue],
                           natTx: FlowStateTransaction[NatKey, NatBinding]): Unit = {
        if (conntrackTx ne NO_CONNTRACK)
            conntrackTx.commit()
        if (natTx ne NO_NAT)
            natTx.commit()
    }

    def flushTransactions(conntrackTx: FlowStateTransaction[ConnTrackKey, ConnTrackValue],
                          natTx: FlowStateTransaction[NatKey, NatBinding]): Unit = {
        if (conntrackTx ne NO_CONNTRACK)
            conntrackTx.flush()
        if (natTx ne NO_NAT)
            natTx.flush()
    }

    def applyPacketActions(packet: Ethernet,
                           actions: JList[FlowAction]): Ethernet = {
        val eth = Ethernet.deserialize(packet.serialize())
        var ip: IPv4 = null
        var tcp: TCP = null
        var udp: UDP = null
        var icmp: ICMP = null
        eth.getEtherType match {
            case IPv4.ETHERTYPE =>
                ip = eth.getPayload.asInstanceOf[IPv4]
                ip.getProtocol match {
                    case TCP.PROTOCOL_NUMBER =>
                        tcp = ip.getPayload.asInstanceOf[TCP]
                    case UDP.PROTOCOL_NUMBER =>
                        udp = ip.getPayload.asInstanceOf[UDP]
                    case ICMP.PROTOCOL_NUMBER =>
                        icmp = ip.getPayload.asInstanceOf[ICMP]
                }
        }

        // TODO(guillermo) incomplete, but it should cover testing needs
        actions collect { case a: FlowActionSetKey => a } foreach {
            _.getFlowKey match {
                case key: FlowKeyEthernet =>
                    if (key.eth_dst != null)
                        eth.setDestinationMACAddress(key.eth_dst)
                    if (key.eth_src != null)
                        eth.setSourceMACAddress(key.eth_src)
                case key: FlowKeyIPv4 =>
                    if (key.ipv4_dst != 0)
                        ip.setDestinationAddress(key.ipv4_dst)
                    if (key.ipv4_src != 0)
                        ip.setSourceAddress(key.ipv4_src)
                    if (key.ipv4_ttl != 0)
                        ip.setTtl(key.ipv4_ttl)
                case key: FlowKeyTCP =>
                    if (key.tcp_dst != 0) tcp.setDestinationPort(key.tcp_dst)
                    if (key.tcp_src != 0) tcp.setSourcePort(key.tcp_src)
                case key: FlowKeyUDP =>
                    if (key.udp_dst != 0) udp.setDestinationPort(key.udp_dst)
                    if (key.udp_src != 0) udp.setSourcePort(key.udp_src)
                case key: FlowKeyICMPError =>
                    throw new IllegalArgumentException(
                        s"ICMP should be handled in userspace")
                case key: FlowKeyTunnel =>  /* ignore tunnel keys */
                case unmatched =>
                    throw new IllegalArgumentException(s"Won't translate $unmatched")
            }
        }

        eth
    }

    def packetWorkflow(dpPortToVport: Map[Int, UUID] = Map.empty,
                       tunnelPorts: List[Integer] = List.empty,
                       peers: Map[UUID, UnderlayRoute] = Map.empty,
                       dpChannel: DatapathChannel = mockDpChannel,
                       packetCtxTrap: JQueue[PacketContext] = new JLinkedList[PacketContext](),
                       workflowTrap: PacketContext => SimulationResult = null,
                       conntrackTable: FlowStateTable[ConnTrackKey, ConnTrackValue] = new ShardedFlowStateTable[ConnTrackKey, ConnTrackValue](clock).addShard(),
                       natTable: FlowStateTable[NatKey, NatBinding] = new ShardedFlowStateTable[NatKey, NatBinding](clock).addShard(),
                       traceTable: FlowStateTable[TraceKey, TraceContext] = new ShardedFlowStateTable[TraceKey, TraceContext](clock).addShard(),
                       flowRecorder: FlowRecorder = injector.getInstance(classOf[FlowRecorderFactory]).newFlowRecorder())
                      (implicit hostId: UUID) = {
        val dpState = new DatapathState {
            override val datapath = new Datapath(0, "midonet")
            override def peerTunnelInfo(peer: UUID): Option[UnderlayRoute] =
                peers.get(peer)
            override def isVtepTunnellingPort(portNumber: Integer): Boolean = false
            override def isOverlayTunnellingPort(portNumber: Integer): Boolean =
                tunnelPorts.contains(portNumber)
            override def vtepTunnellingOutputAction: FlowActionOutput = null
            override def getVportForDpPortNumber(portNum: Integer): UUID =
                dpPortToVport get portNum orNull
            override def dpPortForTunnelKey(tunnelKey: Long): DpPort =
                DpPort.fakeFrom(new InternalPort("dpPort-" + tunnelKey),
                                tunnelKey.toInt)
            override def getDpPortNumberForVport(vportId: UUID): Integer =
                (dpPortToVport map (_.swap) toMap) get vportId map Integer.valueOf orNull
        }

        val dhcpConfig = new DhcpConfigFromNsdb(
            injector.getInstance(classOf[VirtualTopology]))

        TestActorRef[PacketWorkflow](Props(new PacketWorkflow(
            config,
            hostId,
            dpState,
            new CookieGenerator(0, 1),
            clock,
            dpChannel,
            dhcpConfig,
            simBackChannel,
            flowProcessor,
            conntrackTable,
            natTable,
            traceTable,
            peerResolver,
            Future.successful(new MockStateStorage),
            HappyGoLuckyLeaser,
            metrics,
            flowRecorder,
            injector.getInstance(classOf[VirtualTopology]),
            _ => { }) {

            override def runWorkflow(pktCtx: PacketContext) = {
                packetCtxTrap.offer(pktCtx)
                super.runWorkflow(pktCtx)
            }

            override def start(pktCtx: PacketContext): SimulationResult =
                if (workflowTrap ne null) {
                    pktCtx.prepareForSimulation()
                    workflowTrap(pktCtx)
                } else {
                    super.start(pktCtx)
                }
        }))
    }
}

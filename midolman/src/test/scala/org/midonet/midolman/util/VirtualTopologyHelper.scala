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

import java.util.{UUID, LinkedList => JLinkedList, List => JList, Queue => JQueue}

import scala.collection.mutable
import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.reflect.ClassTag

import akka.actor.ActorSystem
import akka.util.Timeout
import akka.util.Timeout.durationToTimeout

import com.google.inject.Injector

import org.midonet.midolman.PacketWorkflow.SimulationResult
import org.midonet.midolman.UnderlayResolver.{Route => UnderlayRoute}
import org.midonet.midolman._
import org.midonet.midolman.datapath.DatapathChannel
import org.midonet.midolman.monitoring.{FlowRecorder, NullFlowRecorder}
import org.midonet.midolman.simulation.{PacketContext, Bridge => SimBridge, Chain => SimChain, Port => SimPort, Router => SimRouter, _}
import org.midonet.midolman.state.ConnTrackState._
import org.midonet.midolman.state.NatState.NatKey
import org.midonet.midolman.state.TraceState.{TraceContext, TraceKey}
import org.midonet.midolman.state._
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.midolman.topology.VirtualTopology.Device
import org.midonet.midolman.topology.devices.Host
import org.midonet.odp._
import org.midonet.odp.flows.{FlowAction, FlowActionOutput, FlowKeys, _}
import org.midonet.odp.ports.{InternalPort, NetDevPort, VxLanTunnelPort}
import org.midonet.packets.NatState.NatBinding
import org.midonet.packets.{Ethernet, IPv4Addr, MAC}
import org.midonet.sdn.state.{FlowStateTable, FlowStateTransaction, ShardedFlowStateTable}
import org.midonet.util.UnixClock
import org.midonet.util.concurrent._

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
        Await.result(VirtualTopology.get[T](tag.runtimeClass.asInstanceOf[Class[T]],
                                            id), timeout.duration)
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

    def fetchPortGroups(portGroups: UUID*): Seq[PortGroup] =
        portGroups map fetchDevice[PortGroup]

    def feedArpCache(bridge: SimBridge, ip: IPv4Addr, mac: MAC): Unit = {
        val map = virtualTopology.backend.stateTableStore.bridgeArpTable(bridge.id)
        map.addPersistent(ip, mac).await()
    }

    def feedArpTable(router: SimRouter, ip: IPv4Addr, mac: MAC): Unit = {
        ArpCacheHelper.feedArpCache(router.arpCache, ip, mac)
    }

    def feedPeeringTable(port: UUID, mac: MAC, ip: IPv4Addr): Unit = {
        val map = virtualTopology.backend.stateTableStore.portPeeringTable(port)
        map.addPersistent(mac, ip).await()
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
        new ArpRequestBroker(config, simBackChannel, UnixClock.mock())

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
        val context = PacketContext.generated(-1, new Packet(frame, fmatch),
                                              fmatch, null, null,
                                              simBackChannel, arpBroker)
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
        val context = PacketContext.generated(-1, new Packet(frame, fmatch),
                                              fmatch, egressPort, null,
                                              simBackChannel, arpBroker)
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
        actions collect {
            case a: FlowActionSetKey => a.getFlowKey
        } foreach (FlowKeyApplier.apply(_, eth))
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
                       flowRecorder: FlowRecorder = NullFlowRecorder)
                      (implicit hostId: UUID) = {
        val dpState = new DatapathState {
            val fip64KeyToPort = mutable.Map[Int, UUID]()

            override val datapath = new Datapath(0, "midonet")
            override def peerTunnelInfo(peer: UUID): Option[UnderlayRoute] =
                peers.get(peer)
            override def isVtepTunnellingPort(portNumber: Int): Boolean = false
            override def isOverlayTunnellingPort(portNumber: Int): Boolean =
                tunnelPorts.contains(portNumber)
            override def vtepTunnellingOutputAction: FlowActionOutput = null
            override def getVportForDpPortNumber(portNum: Integer): UUID =
                dpPortToVport get portNum orNull
            override def dpPortForTunnelKey(tunnelKey: Long): DpPort =
                DpPort.fakeFrom(new InternalPort("dpPort-" + tunnelKey),
                                tunnelKey.toInt)
            override def getDpPortNumberForVport(vportId: UUID): Integer =
                (dpPortToVport map (_.swap)) get vportId map Integer.valueOf orNull

            override def tunnelRecircVxLanPort: VxLanTunnelPort = null
            override def hostRecircPort: NetDevPort = null
            override def tunnelRecircOutputAction: FlowActionOutput = null
            override def hostRecircOutputAction: FlowActionOutput = null

            override def isFip64TunnellingPort(portNumber: Int): Boolean = false
            override def tunnelFip64VxLanPort: VxLanTunnelPort =
                new VxLanTunnelPort("tnvxlan-fip64", 1234)
            override def setFip64PortKey(port: UUID, key: Int): Unit = {
                fip64KeyToPort += (key -> port)
            }

            override def clearFip64PortKey(port: UUID, key: Int): Unit = {
                fip64KeyToPort -= key
            }

            override def getFip64PortForKey(key: Int): UUID = {
                fip64KeyToPort getOrElse (key, null)
            }
        }

        new MockPacketWorkflow(config, hostId, dpState, clock, dpChannel,
                               virtualTopology, simBackChannel, flowProcessor,
                               conntrackTable, natTable,
                               traceTable, peerResolver, metrics, flowRecorder,
                               packetCtxTrap, workflowTrap)
    }
}

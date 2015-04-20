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

import java.util.{LinkedList, List => JList, Queue, UUID}

import org.midonet.util.UnixClock

import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.reflect.ClassTag

import akka.actor.{Props, ActorSystem}
import akka.pattern.ask
import akka.testkit.TestActorRef
import akka.util.Timeout
import akka.util.Timeout.durationToTimeout

import com.google.inject.Injector

import org.midonet.cluster.DataClient
import org.midonet.cluster.data._
import org.midonet.midolman.datapath.DatapathChannel
import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.midolman.topology.rcu.ResolvedHost
import org.midonet.midolman._
import org.midonet.midolman.PacketWorkflow.SimulationResult
import org.midonet.midolman.UnderlayResolver.{Route => UnderlayRoute}
import org.midonet.midolman.simulation.Coordinator.Device
import org.midonet.midolman.simulation.{Router => SimRouter}
import org.midonet.midolman.simulation.{Coordinator, PacketContext, PacketEmitter}
import org.midonet.midolman.state.ConnTrackState._
import org.midonet.midolman.state.{ArpRequestBroker, MockStateStorage, HappyGoLuckyLeaser}
import org.midonet.midolman.state.NatState.{NatBinding, NatKey}
import org.midonet.midolman.state.TraceState.{TraceKey, TraceContext}
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.topology.VirtualTopologyActor.{BridgeRequest, ChainRequest, IPAddrGroupRequest, PortRequest, RouterRequest}
import org.midonet.odp.flows.{FlowAction, FlowActionOutput, FlowKeys}
import org.midonet.odp._
import org.midonet.odp.flows._
import org.midonet.odp.ports.InternalPort
import org.midonet.packets.{IPv4Addr, MAC, Ethernet, IPv4, ICMP, TCP, UDP}
import org.midonet.packets.util.AddressConversions._
import org.midonet.sdn.state.{FlowStateTable, ShardedFlowStateTable, FlowStateTransaction}

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

    def fetchDevice[T](device: Entity.Base[_,_,_]) =
        Await.result(
            ask(VirtualTopologyActor, buildRequest(device)).asInstanceOf[Future[T]],
            timeout.duration)

    def fetchTopology(entities: Entity.Base[_,_,_]*) =
        fetchTopologyList(entities)

    def fetchTopologyList(entities: Seq[Entity.Base[_,_,_]]) =
        Await.result(Future.sequence(entities map buildRequest map
                                     { VirtualTopologyActor ? _ }),
                     timeout.duration)

    def feedArpTable(router: SimRouter, ip: IPv4Addr, mac: MAC): Unit = {
        ArpCacheHelper.feedArpCache(router, ip, mac)
    }

    def throwAwayArpBroker(emitter: Queue[PacketEmitter.GeneratedPacket] = new LinkedList): ArpRequestBroker =
        new ArpRequestBroker(new PacketEmitter(emitter, actorSystem.deadLetters),
                             config, flowInvalidator, UnixClock.MOCK)

    val NO_CONNTRACK = new FlowStateTransaction[ConnTrackKey, ConnTrackValue](null)
    val NO_NAT = new FlowStateTransaction[NatKey, NatBinding](null)
    val NO_TRACE = new FlowStateTransaction[TraceKey, TraceContext](null)

    def packetContextFor(frame: Ethernet, inPort: UUID = null,
                         emitter: Queue[PacketEmitter.GeneratedPacket] = new LinkedList,
                         inPortNumber: Int = 0)
                        (implicit conntrackTx: FlowStateTransaction[ConnTrackKey, ConnTrackValue] = NO_CONNTRACK,
                                  natTx: FlowStateTransaction[NatKey, NatBinding] = NO_NAT,
                                  traceTx: FlowStateTransaction[TraceKey, TraceContext] = NO_TRACE,
                                  arpBroker: ArpRequestBroker = throwAwayArpBroker(emitter))
    : PacketContext = {
        val fmatch = new FlowMatch(FlowKeys.fromEthernetPacket(frame))
        fmatch.setInputPortNumber(inPortNumber)
        val context = new PacketContext(-1, new Packet(frame, fmatch), fmatch)
        context.packetEmitter = new PacketEmitter(emitter, actorSystem.deadLetters)
        context.arpBroker = arpBroker
        context.initialize(conntrackTx, natTx, HappyGoLuckyLeaser, traceTx)
        context.prepareForSimulation()
        context.inputPort = inPort
        context.inPortId = inPort
        context
    }

    def simulateDevice(device: Device, frame: Ethernet, inPort: UUID)
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

    def sendPacket(t: (Port[_,_], Ethernet))
                  (implicit conntrackTx: FlowStateTransaction[ConnTrackKey, ConnTrackValue] = NO_CONNTRACK,
                            natTx: FlowStateTransaction[NatKey, NatBinding] = NO_NAT)
    : (SimulationResult, PacketContext) =
        simulate(packetContextFor(t._2, t._1.getId))(conntrackTx, natTx)

    def sendPacket(port: Port[_,_], pkt: Ethernet)
    : (SimulationResult, PacketContext) =
        simulate(packetContextFor(pkt, port.getId))(NO_CONNTRACK, NO_NAT)

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
            new Coordinator(pktCtx) simulate()
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
                       packetCtxTrap: Queue[PacketContext] = new LinkedList[PacketContext](),
                       conntrackTable: FlowStateTable[ConnTrackKey, ConnTrackValue] = new ShardedFlowStateTable[ConnTrackKey, ConnTrackValue](clock).addShard(),
                       natTable: FlowStateTable[NatKey, NatBinding] = new ShardedFlowStateTable[NatKey, NatBinding](clock).addShard(),
                       traceTable: FlowStateTable[TraceKey, TraceContext] = new ShardedFlowStateTable[TraceKey, TraceContext](clock).addShard())
                      (implicit hostId: UUID, client: DataClient) = {
        val pktWkfl = TestActorRef[PacketWorkflow](Props(new PacketWorkflow(
            0,
            config,
            new CookieGenerator(0, 1),
            clock,
            dpChannel,
            client,
            flowInvalidator,
            flowProcessor,
            conntrackTable,
            natTable,
            traceTable,
            new MockStateStorage,
            HappyGoLuckyLeaser,
            metrics,
            _ => { }) {

            override def runWorkflow(pktCtx: PacketContext) = {
                packetCtxTrap.add(pktCtx)
                super.runWorkflow(pktCtx)
            }
        }))
        pktWkfl ! DatapathController.DatapathReady(new Datapath(0, "midonet"), new DatapathState {
            override def host: ResolvedHost = new ResolvedHost(hostId, true, Map(), Map())
            override def peerTunnelInfo(peer: UUID): Option[UnderlayRoute] =
                peers.get(peer)
            override def isVtepTunnellingPort(portNumber: Integer): Boolean = false
            override def isOverlayTunnellingPort(portNumber: Integer): Boolean =
                tunnelPorts.contains(portNumber)
            override def vtepTunnellingOutputAction: FlowActionOutput = null
            override def getDescForInterface(itfName: String): Option[InterfaceDescription] = None
            override def getDpPortForInterface(itfName: String): Option[DpPort] = None
            override def getVportForDpPortNumber(portNum: Integer): Option[UUID] =
                dpPortToVport.get(portNum)
            override def dpPortNumberForTunnelKey(tunnelKey: Long): Option[DpPort] =
                Some(DpPort.fakeFrom(new InternalPort("dpPort-" + tunnelKey),
                                     tunnelKey.toInt))
            override def getDpPortNumberForVport(vportId: UUID): Option[Integer] =
                dpPortToVport.map(_.swap).toMap.get(vportId).map(_.asInstanceOf[Integer])
            override def getDpPortName(num: Integer): Option[String] =  None
        })
        pktWkfl
    }

    @inline
    private[this] def buildRequest(entity: Entity.Base[_,_,_]) = entity match {
        case p: Port[_, _] => PortRequest(p.getId, update = true)
        case b: Bridge => BridgeRequest(b.getId, update = true)
        case r: Router => RouterRequest(r.getId, update = true)
        case c: Chain => ChainRequest(c.getId, update = true)
        case i: IpAddrGroup => IPAddrGroupRequest(i.getId, update = true)
    }
}

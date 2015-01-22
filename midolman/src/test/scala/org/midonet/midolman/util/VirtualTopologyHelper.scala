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

import java.util.{ArrayList, HashSet => JHashSet, LinkedList, List, Queue, UUID}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.reflect.ClassTag

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.util.Timeout
import akka.util.Timeout.durationToTimeout

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
import org.midonet.midolman.state.{FlowStateReplicator, HappyGoLuckyLeaser}
import org.midonet.midolman.state.NatState.{NatBinding, NatKey}
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.topology.VirtualTopologyActor.{BridgeRequest, ChainRequest, IPAddrGroupRequest, PortRequest, RouterRequest}
import org.midonet.odp.flows.{FlowAction, FlowActionOutput, FlowKeys}
import org.midonet.odp._
import org.midonet.packets.{IPv4Addr, MAC, Ethernet}
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.midonet.sdn.state.FlowStateTransaction
import org.midonet.util.functors.Callback0

trait VirtualTopologyHelper {

    implicit def actorSystem: ActorSystem
    implicit def executionContext: ExecutionContext

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
        router.arpTable.set(ip, mac)
    }

    val NO_CONNTRACK = new FlowStateTransaction[ConnTrackKey, ConnTrackValue](null)
    val NO_NAT = new FlowStateTransaction[NatKey, NatBinding](null)

    def packetContextFor(frame: Ethernet, inPort: UUID = null,
                         emitter: Queue[PacketEmitter.GeneratedPacket] = new LinkedList,
                         inPortNumber: Int = 0)
                        (implicit conntrackTx: FlowStateTransaction[ConnTrackKey, ConnTrackValue] = NO_CONNTRACK,
                                  natTx: FlowStateTransaction[NatKey, NatBinding] = NO_NAT)
    : PacketContext = {
        val fmatch = new FlowMatch(FlowKeys.fromEthernetPacket(frame))
        fmatch.setInputPortNumber(inPortNumber)
        val context = new PacketContext(-1, new Packet(frame, fmatch), fmatch)
        context.packetEmitter = new PacketEmitter(emitter, actorSystem.deadLetters)
        context.initialize(conntrackTx, natTx, HappyGoLuckyLeaser)
        context.prepareForSimulation(0)
        context.inputPort = inPort
        context.inPortId = inPort
        context
    }

    def simulateDevice(device: Device, frame: Ethernet, inPort: UUID)
                      (implicit conntrackTx: FlowStateTransaction[ConnTrackKey, ConnTrackValue] = NO_CONNTRACK,
                                natTx: FlowStateTransaction[NatKey, NatBinding] = NO_NAT)
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
                          natTx: FlowStateTransaction[NatKey, NatBinding] = NO_NAT)
    : (SimulationResult, PacketContext) = {
        pktCtx.initialize(conntrackTx, natTx, HappyGoLuckyLeaser)
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

    def packetWorkflow(dpPortToVport: Map[Int, UUID])
                      (implicit hostId: UUID, client: DataClient) = new PacketWorkflow(
        new DatapathState {
            override def host: ResolvedHost = new ResolvedHost(hostId, true, "midonet", Map(), Map())
            override def peerTunnelInfo(peer: UUID): Option[UnderlayRoute] = None
            override def isVtepTunnellingPort(portNumber: Integer): Boolean = false
            override def isOverlayTunnellingPort(portNumber: Integer): Boolean = false
            override def vtepTunnellingOutputAction: FlowActionOutput = null
            override def getDescForInterface(itfName: String): Option[InterfaceDescription] = None
            override def getDpPortForInterface(itfName: String): Option[DpPort] = None
            override def getVportForDpPortNumber(portNum: Integer): Option[UUID] =
                dpPortToVport.get(portNum)
            override def dpPortNumberForTunnelKey(tunnelKey: Long): Option[DpPort] = None
            override def getDpPortNumberForVport(vportId: UUID): Option[Integer] =
                dpPortToVport.map(_.swap).toMap.get(vportId).map(_.asInstanceOf[Integer])
            override def getDpPortName(num: Integer): Option[String] =  None
        }, null, client, new DatapathChannel {
            override def executePacket(packet: Packet,
                                       actions: List[FlowAction]): Unit = { }
            override def createFlow(flow: Flow): Unit = { }
            override def start(datapath: Datapath): Unit = { }
            override def stop(): Unit = { }
        }, new FlowStateReplicator(null, null, null, new UnderlayResolver {
            override def host: ResolvedHost = new ResolvedHost(UUID.randomUUID(), true, "", Map(), Map())
            override def peerTunnelInfo(peer: UUID): Option[UnderlayRoute] = None
            override def vtepTunnellingOutputAction: FlowActionOutput = null
            override def isVtepTunnellingPort(portNumber: Integer): Boolean = false
            override def isOverlayTunnellingPort(portNumber: Integer): Boolean = false
        }, null, 0) {
            override def pushState(dpChannel: DatapathChannel): Unit = { }
            override def accumulateNewKeys(
                          conntrackTx: FlowStateTransaction[ConnTrackKey, ConnTrackValue],
                          natTx: FlowStateTransaction[NatKey, NatBinding],
                          ingressPort: UUID, egressPorts: List[UUID],
                          tags: JHashSet[FlowTag],
                          callbacks: ArrayList[Callback0]): Unit = { }
        })

    @inline
    private[this] def buildRequest(entity: Entity.Base[_,_,_]) = entity match {
        case p: Port[_, _] => PortRequest(p.getId, update = true)
        case b: Bridge => BridgeRequest(b.getId, update = true)
        case r: Router => RouterRequest(r.getId, update = true)
        case c: Chain => ChainRequest(c.getId, update = true)
        case i: IpAddrGroup => IPAddrGroupRequest(i.getId, update = true)
    }
}

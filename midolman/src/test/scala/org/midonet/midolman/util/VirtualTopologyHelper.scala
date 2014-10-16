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

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future, Await}
import scala.concurrent.duration._

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.util.Timeout
import akka.util.Timeout.durationToTimeout

import org.scalatest.Assertions

import org.midonet.cluster.client.{Port => SimPort}
import org.midonet.cluster.data._
import org.midonet.midolman.NotYetException
import org.midonet.midolman.PacketWorkflow.SimulationResult
import org.midonet.midolman.simulation.Coordinator.{Device, Action}
import org.midonet.midolman.simulation.{Coordinator, PacketContext}
import org.midonet.midolman.state.ConnTrackState._
import org.midonet.midolman.state.NatState.NatKey
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.packets.Ethernet
import org.midonet.sdn.flows.WildcardMatch
import org.midonet.odp.Packet
import org.midonet.sdn.state.FlowStateTransaction
import org.midonet.midolman.topology.VirtualTopologyActor.RouterRequest
import org.midonet.midolman.topology.VirtualTopologyActor.PortRequest
import org.midonet.midolman.topology.VirtualTopologyActor.IPAddrGroupRequest
import org.midonet.midolman.state.NatState.NatBinding
import org.midonet.midolman.topology.VirtualTopologyActor.BridgeRequest
import org.midonet.midolman.topology.VirtualTopologyActor.ChainRequest
import scala.util.{Failure, Success, Try}
import org.midonet.midolman.state.HappyGoLuckyLeaser
import scala.reflect.ClassTag

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

    val NO_CONNTRACK = new FlowStateTransaction[ConnTrackKey, ConnTrackValue](null)
    val NO_NAT = new FlowStateTransaction[NatKey, NatBinding](null)

    def packetContextFor(frame: Ethernet, inPort: UUID)
                        (implicit conntrackTx: FlowStateTransaction[ConnTrackKey, ConnTrackValue] = NO_CONNTRACK,
                                  natTx: FlowStateTransaction[NatKey, NatBinding] = NO_NAT)
    : PacketContext = {
        val context = new PacketContext(Left(1), Packet.fromEthernet(frame),
                                        None, WildcardMatch.fromEthernetPacket(frame))
        context.state.initialize(conntrackTx, natTx, HappyGoLuckyLeaser)
        context.prepareForSimulation(0)
        context.inputPort = inPort
        context.inPortId = Await.result(
            ask(VirtualTopologyActor, PortRequest(inPort)).mapTo[SimPort],
            timeout.duration)
        context
    }

    def simulateDevice(device: Device, frame: Ethernet, inPort: UUID)
                      (implicit conntrackTx: FlowStateTransaction[ConnTrackKey, ConnTrackValue] = NO_CONNTRACK,
                                natTx: FlowStateTransaction[NatKey, NatBinding] = NO_NAT)
    : (PacketContext, Action) = {
        val ctx = packetContextFor(frame, inPort)
        force {
            flushTransactions(conntrackTx, natTx)
            ctx.state.clear()
            (ctx, device.process(ctx))
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
        pktCtx.state.initialize(conntrackTx, natTx, HappyGoLuckyLeaser)
        val r = force {
            flushTransactions(conntrackTx, natTx)
            pktCtx.state.clear()
            new Coordinator(pktCtx) simulate()
        }
        commitTransactions(conntrackTx, natTx)
        flushTransactions(conntrackTx, natTx)
        pktCtx.state.clear()
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

    @inline
    private[this] def buildRequest(entity: Entity.Base[_,_,_]) = entity match {
        case p: Port[_, _] => PortRequest(p.getId, update = true)
        case b: Bridge => BridgeRequest(b.getId, update = true)
        case r: Router => RouterRequest(r.getId, update = true)
        case c: Chain => ChainRequest(c.getId, update = true)
        case i: IpAddrGroup => IPAddrGroupRequest(i.getId, update = true)
    }
}

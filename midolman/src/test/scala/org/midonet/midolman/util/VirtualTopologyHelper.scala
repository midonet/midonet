/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.util

import java.util.UUID
import scala.compat.Platform
import scala.concurrent.{ExecutionContext, Future, Await}
import scala.concurrent.duration._

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.util.Timeout
import akka.util.Timeout.durationToTimeout

import org.scalatest.Assertions

import org.midonet.cache.MockCache
import org.midonet.cluster.client.{Port => SimPort}
import org.midonet.cluster.data._
import org.midonet.midolman.{NotYet, Ready}
import org.midonet.midolman.PacketWorkflow.SimulationResult
import org.midonet.midolman.simulation.Coordinator.{Device, Action}
import org.midonet.midolman.simulation.{Coordinator, PacketContext}
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.topology.VirtualTopologyActor._
import org.midonet.packets.Ethernet
import org.midonet.sdn.flows.WildcardMatch
import org.midonet.odp.Packet

trait VirtualTopologyHelper {

    implicit def actorSystem: ActorSystem
    implicit def executionContext: ExecutionContext

    private implicit val timeout: Timeout = 3 seconds
    private val natCache = new MockCache()

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

    def packetContextFor(frame: Ethernet, inPort: UUID): PacketContext = {
        val context = new PacketContext(Left(1), Packet.fromEthernet(frame),
                                        Platform.currentTime + 3000, natCache,
                                        null, null, None,
                                        WildcardMatch.fromEthernetPacket(frame))
        context.prepareForSimulation(0)
        context.inputPort = inPort
        context.inPortId = Await.result(
            ask(VirtualTopologyActor, PortRequest(inPort)).mapTo[SimPort],
            timeout.duration)
        context
    }

    def simulateDevice(device: Device, frame: Ethernet, inPort: UUID):
            (PacketContext, Action) = {
        var triesLeft = 64
        do {
            triesLeft -= 1
            val ctx = packetContextFor(frame, inPort)
            device.process(ctx) match {
                case Ready(action) =>
                    return (ctx, action)
                case NotYet(f) =>
                    Await.result(f, 1 second)
            }
        } while (triesLeft > 0)

        Assertions.fail("Failed to complete simulation")
    }

    def sendPacket(t: (Port[_,_], Ethernet)): (SimulationResult, PacketContext) =
        sendPacket(t._1, t._2)

    def sendPacket(port: Port[_,_], pkt: Ethernet): (SimulationResult, PacketContext) =
        simulate(packetContextFor(pkt, port.getId))

    def simulate(pktCtx: PacketContext): (SimulationResult, PacketContext) =
        new Coordinator(pktCtx) simulate() match {
            case Ready(r) => (r, pktCtx)
            case NotYet(f) =>
                Await.result(f, 3 seconds)
                simulate(pktCtx)
        }

    def makeWildcardMatch(port: Port[_,_], pkt: Ethernet) =
        WildcardMatch.fromEthernetPacket(pkt)
                .setInputPortUUID(port.getId)

    @inline
    private[this] def buildRequest(entity: Entity.Base[_,_,_]) = entity match {
        case p: Port[_, _] => PortRequest(p.getId, update = true)
        case b: Bridge => BridgeRequest(b.getId, update = true)
        case r: Router => RouterRequest(r.getId, update = true)
        case c: Chain => ChainRequest(c.getId, update = true)
        case i: IpAddrGroup => IPAddrGroupRequest(i.getId, update = true)
    }
}

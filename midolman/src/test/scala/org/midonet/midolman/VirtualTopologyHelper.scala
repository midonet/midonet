/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman

import scala.compat.Platform
import scala.concurrent.{Future, Await}
import scala.concurrent.duration._
import akka.pattern.ask
import akka.util.Timeout
import java.util.UUID

import org.midonet.cluster.client.{Port => SimPort}
import org.midonet.cluster.data._
import org.midonet.midolman.simulation.Coordinator.{Device, Action}
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.midolman.topology.VirtualTopologyActor._
import org.midonet.packets.Ethernet
import org.midonet.midolman.simulation.{Coordinator, PacketContext}
import org.midonet.sdn.flows.WildcardMatch
import org.midonet.midolman.util.MockCache

trait VirtualTopologyHelper {
    this: MockMidolmanActors =>

    private implicit val timeout: Timeout = 3 seconds
    private val natCache = new MockCache()

    def fetchDevice[T](device: Entity.Base[_,_,_]) =
        Await.result(
            ask(VirtualTopologyActor, buildRequest(device)).asInstanceOf[Future[T]],
            timeout.duration)

    def preloadTopology(entities: Entity.Base[_,_,_]*) =
        Await.result(Future.sequence(entities map buildRequest map
                                     { ask(VirtualTopologyActor, _) }),
                     timeout.duration)

    def packetContextFor(frame: Ethernet, inPort: UUID): PacketContext = {
        val context = new PacketContext(Some(1), frame, Platform.currentTime + 3000,
            natCache, null, null, false, None,
            WildcardMatch.fromEthernetPacket(frame))
        context.inPortId = Await.result(
            ask(VirtualTopologyActor, PortRequest(inPort, false)).mapTo[SimPort[_]],
            timeout.duration)
        context
    }

    def simulateDevice(device: Device, frame: Ethernet, inPort: UUID):
            (PacketContext, Action) = {
        val ctx = packetContextFor(frame, inPort)
        val action: Action = Await.result(device.process(ctx), timeout.duration)
        ctx.freeze()
        (ctx, action)
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

// Copyright 2012 Midokura Inc.

package org.midonet.midolman

import compat.Platform
import scala.Left
import scala.Right
import scala.Some

import akka.actor.Actor
import akka.dispatch.{Future, Promise}
import com.google.inject.Inject
import java.util.UUID
import javax.annotation.Nullable

import org.midonet.cache.Cache
import org.midonet.cluster.DataClient
import org.midonet.midolman.simulation.{Coordinator, DhcpImpl}
import org.midonet.packets._
import org.midonet.sdn.flows.{WildcardFlow, WildcardMatch, WildcardMatches}
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.DatapathController.PacketIn
import org.midonet.midolman.FlowController.AddWildcardFlow

object SimulationController extends Referenceable {
    override val Name = "SimulationController"

    case class EmitGeneratedPacket(egressPort: UUID, ethPkt: Ethernet,
                                   parentCookie: Option[Int])
}

class SimulationController() extends Actor with ActorLogWithoutPath {
    import SimulationController._
    import context._

    @Inject
    val config: MidolmanConfig = null

    def timeout = config.getArpTimeoutSeconds * 1000
    @Inject @Nullable var connectionCache: Cache = null
    @Inject val clusterDataClient: DataClient = null

    val datapathController = DatapathController.getRef()

    def receive = {
        case PacketIn(wMatch, pktBytes, _, _, cookie) =>
            val ethPkt = Ethernet.deserialize(pktBytes)
            handleDHCP(wMatch, cookie, ethPkt) onComplete {
                case Left(err) =>
                    // On error drop the flow temporarily (empty action list).
                    datapathController.tell(
                        AddWildcardFlow(
                            new WildcardFlow()
                                .setHardExpirationMillis(5 * 1000)
                                .setMatch(wMatch),
                            cookie, null, null, null))

                case Right(true) =>
                    //Nothing to do
                case Right(false) =>
                    new Coordinator(
                        wMatch, ethPkt, cookie, None,
                        Platform.currentTime + timeout,
                        connectionCache, None).simulate()
            }

        case EmitGeneratedPacket(egressPort, ethPkt, parentCookie) =>
            new Coordinator(
                WildcardMatches.fromEthernetPacket(ethPkt), ethPkt, None,
                Some(egressPort), Platform.currentTime + timeout,
                connectionCache, parentCookie).simulate()
    }

    private def handleDHCP(wMatch: WildcardMatch,
            cookie: Option[Int], ethPkt: Ethernet): Future[Boolean] = {
        // check if the packet is a DHCP request
        if (ethPkt.getEtherType() == IPv4.ETHERTYPE) {
            val ipv4 = ethPkt.getPayload.asInstanceOf[IPv4]
            if (ipv4.getProtocol() == UDP.PROTOCOL_NUMBER) {
                val udp = ipv4.getPayload.asInstanceOf[UDP];
                if (udp.getSourcePort() == 68
                    && udp.getDestinationPort() == 67) {
                    val dhcp = udp.getPayload().asInstanceOf[DHCP];
                    if (dhcp.getOpCode() == DHCP.OPCODE_REQUEST) {
                        log.debug("Got a DHCP bootrequest");
                        return new DhcpImpl(
                            clusterDataClient, wMatch.getInputPortUUID, dhcp,
                            ethPkt.getSourceMACAddress, cookie).handleDHCP
                    }
                }
            }
        }
        return Promise.successful(false)
    }
}

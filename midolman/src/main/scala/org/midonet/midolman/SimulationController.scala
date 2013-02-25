// Copyright 2012 Midokura Inc.

package org.midonet.midolman

import compat.Platform
import scala.Left
import scala.Right

import akka.dispatch.{Future, Promise}
import akka.dispatch.ExecutionContext
import com.google.inject.Inject
import java.util.UUID
import javax.annotation.Nullable

import org.midonet.cache.Cache
import org.midonet.midolman.simulation.{Coordinator, DhcpImpl}
import org.midonet.packets._
import org.midonet.sdn.flows.{WildcardFlow, WildcardMatch}
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.cluster.DataClient
import org.midonet.midolman.FlowController.AddWildcardFlow
import akka.actor.ActorSystem

class SimulationController {

    @Inject
    val config: MidolmanConfig = null

    def timeout = config.getArpTimeoutSeconds * 1000
    @Inject @Nullable var connectionCache: Cache = null
    @Inject val clusterDataClient: DataClient = null

    def packetIn(wMatch: WildcardMatch, pktBytes: Array[Byte],
                 cookie: Option[Int])
                (implicit ec: ExecutionContext,
                 system: ActorSystem): Unit = {
        val ethPkt = Ethernet.deserialize(pktBytes)
        handleDHCP(wMatch, cookie, ethPkt) onComplete {
            case Left(err) =>
                // On error drop the flow temporarily (empty action list).
                DatapathController.getRef(system).tell(
                    AddWildcardFlow(
                        new WildcardFlow()
                            .setHardExpirationMillis(5 * 1000)
                            .setMatch(wMatch),
                        cookie, null, null, null))

            case Right(true) =>
            //Nothing to do
            case Right(false) =>
                ec.execute(new Coordinator(
                    wMatch, ethPkt, cookie, None,
                    Platform.currentTime + timeout,
                    connectionCache, None))
        }
    }

    def emitGeneratedPacket(egressPort: UUID, ethPkt: Ethernet,
                            parentCookie: Option[Int])
                           (implicit ec: ExecutionContext,
                            actorSystem: ActorSystem): Unit = {

        ec.execute(new Coordinator(
            WildcardMatch.fromEthernetPacket(ethPkt), ethPkt, None,
            Some(egressPort), Platform.currentTime + timeout,
            connectionCache, parentCookie))
    }

    private def handleDHCP(wMatch: WildcardMatch,
                           cookie: Option[Int], ethPkt: Ethernet)
                          (implicit ec: ExecutionContext,
                           actorSystem: ActorSystem): Future[Boolean] = {
        // check if the packet is a DHCP request
        if (ethPkt.getEtherType() == IPv4.ETHERTYPE) {
            val ipv4 = ethPkt.getPayload.asInstanceOf[IPv4]
            if (ipv4.getProtocol() == UDP.PROTOCOL_NUMBER) {
                val udp = ipv4.getPayload.asInstanceOf[UDP];
                if (udp.getSourcePort() == 68
                    && udp.getDestinationPort() == 67) {
                    val dhcp = udp.getPayload().asInstanceOf[DHCP];
                    if (dhcp.getOpCode() == DHCP.OPCODE_REQUEST) {
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

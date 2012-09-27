// Copyright 2012 Midokura Inc.

package com.midokura.midolman

import compat.Platform
import akka.actor.{Actor, ActorLogging}
import akka.util.duration._
import config.MidolmanConfig
import java.util.UUID
import javax.annotation.Nullable


import com.midokura.cache.Cache
import com.midokura.midolman.simulation.{Coordinator, DhcpImpl}
import com.midokura.packets._
import com.midokura.sdn.flows.{WildcardFlow, WildcardMatch, WildcardMatches}
import akka.dispatch.{Future, Promise}
import com.midokura.midonet.cluster.DataClient
import scala.Left
import com.midokura.midolman.DatapathController.PacketIn
import scala.Right
import scala.Some
import com.midokura.midolman.FlowController.AddWildcardFlow
import com.google.inject.Inject

object SimulationController extends Referenceable {
    val Name = "SimulationController"

    case class EmitGeneratedPacket(egressPort: UUID, ethPkt: Ethernet)
}

class SimulationController() extends Actor with ActorLogging {
    import SimulationController._
    import context._

    val timeout = (5 minutes).toMillis
    @Inject @Nullable var connectionCache: Cache = null
    @Inject val clusterDataClient: DataClient = null
    @Inject val midolmanConfig: MidolmanConfig = null

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
                        connectionCache).simulate
            }

        case EmitGeneratedPacket(egressPort, ethPkt) =>
            new Coordinator(
                WildcardMatches.fromEthernetPacket(ethPkt), ethPkt, None,
                Some(egressPort), Platform.currentTime + timeout,
                connectionCache).simulate
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
                            ethPkt.getSourceMACAddress, cookie,
                            midolmanConfig.getMidolmanDhcpMtu).handleDHCP
                    }
                }
            }
        }
        return Promise.successful(false)
    }
}

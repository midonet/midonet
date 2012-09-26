// Copyright 2012 Midokura Inc.

package com.midokura.midolman

import compat.Platform
import akka.actor.{Actor, ActorLogging}
import akka.util.duration._
import java.util.UUID
import javax.annotation.Nullable

import com.google.inject.Inject

import com.midokura.cache.Cache
import com.midokura.midolman.DatapathController.PacketIn
import com.midokura.midolman.simulation.Coordinator
import com.midokura.packets.{DHCP, UDP, IPv4, Ethernet}
import com.midokura.sdn.flows.{WildcardMatch, WildcardMatches}
import akka.dispatch.{Future, Promise}


object SimulationController extends Referenceable {
    val Name = "SimulationController"

    case class EmitGeneratedPacket(egressPort: UUID, ethPkt: Ethernet)
}

class SimulationController() extends Actor with ActorLogging {
    import SimulationController._
    import context._

    val timeout = (5 minutes).toMillis
    @Inject @Nullable var connectionCache: Cache = null
    //val dhcpHandler = new DhcpHandler()

    def receive = {
        case PacketIn(wMatch, pktBytes, _, _, cookie) =>
            val ethPkt = Ethernet.deserialize(pktBytes)
            handleDHCP(wMatch, cookie, ethPkt) onComplete {
                case Left(err) =>
                    //TODO(pino): drop the flow?
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
                        log.debug("got a DHCP bootrequest");
                        if (true)
                        //if (dhcpHandler.handleDhcpRequest(inPortId, dhcp,
                        //        ethPkt.getSourceMACAddress())) {
                        //        freeBuffer(bufferId);
                            return Promise.successful(true);
                    }
                }
            }
        }
        return Promise.successful(false)
    }
}

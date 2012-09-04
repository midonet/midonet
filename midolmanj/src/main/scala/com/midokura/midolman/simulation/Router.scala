/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.simulation

import akka.dispatch.{ExecutionContext, Future, Promise}
import akka.dispatch.Future.flow
import java.util.UUID
import org.slf4j.LoggerFactory

import com.midokura.midolman.layer3.Route
import com.midokura.midolman.simulation.Coordinator._
import com.midokura.midolman.state.PortDirectory.{LogicalRouterPortConfig,
                                                  MaterializedRouterPortConfig,
                                                  RouterPortConfig}
import com.midokura.midolman.topology.{ArpTable, RouterConfig,
                                       RoutingTableWrapper}
import com.midokura.packets.{ARP, Ethernet, ICMP, IntIPv4, IPv4, MAC}
import com.midokura.packets.ICMP.UNREACH_CODE
import com.midokura.sdn.flows.WildcardMatch
import akka.actor.ActorSystem


class Router(val id: UUID, val cfg: RouterConfig,
             val rTable: RoutingTableWrapper, val arpTable: ArpTable,
             val inFilter: Chain, val outFilter: Chain) extends Device {

    private val log = LoggerFactory.getLogger(classOf[Router])
    private val loadBalancer = new LoadBalancer(rTable)

    override def process(ingressMatch: WildcardMatch, packet: Ethernet,
                         pktContext: PacketContext, expiry: Long)
                        (implicit ec: ExecutionContext,
                         actorSystem: ActorSystem): Future[Action] = {
        val hwDst = ingressMatch.getEthernetDestination
        val rtrPortCfg: RouterPortConfig = getRouterPortConfig(
                            ingressMatch.getInputPortUUID)
        if (rtrPortCfg == null) {
            log.error("Could not get configuration for port {}",
                      ingressMatch.getInputPortUUID)
            return Promise.successful(new DropAction)(ec)
        }
        if (ingressMatch.getEtherType != IPv4.ETHERTYPE &&
                ingressMatch.getEtherType != ARP.ETHERTYPE)
            return Promise.successful(new NotIPv4Action)(ec)

        if (Ethernet.isBroadcast(hwDst)) {
            // Broadcast packet:  Handle if ARP, drop otherwise.
            if (ingressMatch.getEtherType == ARP.ETHERTYPE &&
                    ingressMatch.getNetworkProtocol == ARP.OP_REQUEST) {
                processArpRequest(packet.getPayload.asInstanceOf[ARP],
                                  ingressMatch.getInputPortUUID, rtrPortCfg)
                return Promise.successful(new ConsumedAction)(ec)
            } else
                return Promise.successful(new DropAction)(ec)
        }

        if (hwDst != rtrPortCfg.getHwAddr) {
            // Not addressed to us, log.warn and drop.
            log.warn("{} neither broadcast nor inPort's MAC ({})", hwDst,
                     rtrPortCfg.getHwAddr)
            return Promise.successful(new DropAction)(ec)
        }

        if (ingressMatch.getEtherType == ARP.ETHERTYPE) {
            // Non-broadcast ARP.  Handle reply, drop rest.
            if (ingressMatch.getNetworkProtocol == ARP.OP_REPLY) {
                processArpReply(packet.getPayload.asInstanceOf[ARP],
                                ingressMatch.getInputPortUUID, rtrPortCfg)
                return Promise.successful(new ConsumedAction)(ec)
            } else
                return Promise.successful(new DropAction)(ec)
        }

        val nwDst = ingressMatch.getNetworkDestination
        val inPortIP = new IntIPv4(rtrPortCfg.portAddr)
        if (nwDst == inPortIP) {
            // We're the L3 destination.  Reply to ICMP echos, drop the rest.
            if (isIcmpEchoRequest(ingressMatch)) {
                // XXX TODO(pino): sendIcmpEchoReply(ingress, rtrPortCfg)
                return Promise.successful(new ConsumedAction)(ec)
            } else
                return Promise.successful(new DropAction)(ec)
        }

        // XXX: Apply the pre-routing (ingress) chain

        val rt: Route = loadBalancer.lookup(ingressMatch)
        if (rt == null) {
            // No route to network
            sendIcmp(ingressMatch, UNREACH_CODE.UNREACH_NET)
            return Promise.successful(new DropAction)(ec)
        }
        if (rt.nextHop == Route.NextHop.BLACKHOLE) {
            return Promise.successful(new DropAction)(ec)
        }
        if (rt.nextHop == Route.NextHop.REJECT) {
            sendIcmp(ingressMatch, UNREACH_CODE.UNREACH_FILTER_PROHIB)
            return Promise.successful(new DropAction)(ec)
        }
        if (rt.nextHop != Route.NextHop.PORT) {
            log.error("Routing table lookup for {} returned invalid nextHop " +
                "of {}", nwDst, rt.nextHop)
            // TODO(jlm, pino): Should this be an exception?
            return Promise.successful(new DropAction)(ec)
        }
        if (rt.nextHopPort == null) {
            log.error("Routing table lookup for {} forwarded to port null.",
                nwDst)
            // TODO(pino): should we remove this route?
            return Promise.successful(new DropAction)(ec)
        }

        // XXX: Apply post-routing (egress) chain.

        val outPortCfg = getRouterPortConfig(rt.nextHopPort)
        if (outPortCfg == null) {
            log.error("Can't find the configuration for the egress port {}",
                rt.nextHopPort)
            return Promise.successful(new DropAction)(ec)
        }
        val outPortIP = new IntIPv4(outPortCfg.portAddr)
        if (nwDst == outPortIP) {
            // Drop.  TODO(jlm,pino): Should we check for ICMP echos?
            return Promise.successful(new DropAction)(ec)
        }
        var matchOut: WildcardMatch = null // ingressMatch.clone
        // Set HWSrc
        matchOut.setEthernetSource(outPortCfg.getHwAddr)
        // Set HWDst
        outPortCfg match {
            case logCfg: LogicalRouterPortConfig =>
                if (logCfg.peerId == null) {
                    log.warn("Packet forwarded to dangling logical port {}",
                        rt.nextHopPort)
                    sendIcmp(ingressMatch, UNREACH_CODE.UNREACH_NET)
                    return Promise.successful(new DropAction)(ec)
                }
                val peerMac = getPeerMac(logCfg)
                if (peerMac != null) {
                    matchOut.setEthernetDestination(peerMac)
                    return Promise.successful(
                        ForwardAction(rt.nextHopPort, matchOut))(ec)
                }
            // TODO(jlm,pino): Should not having the peerMac be an error?
            case _ => /* Fall through to ARP'ing below. */
        }
        var nextHopIP: Int = rt.nextHopGateway
        if (nextHopIP == 0 || nextHopIP == -1) {  /* Last hop */
            nextHopIP = matchOut.getNetworkDestination.addressAsInt
        }

        getMacForIP(rt.nextHopPort, nextHopIP, expiry, ec) match {
            case None =>
                // Couldn't get the MAC.  getMacForIP will send any ICPM !H,
                // so here we just drop.
                return Promise.successful(new DropAction)(ec)
            case Some(nextHopMacFuture) => return flow {
                val nextHopMac = nextHopMacFuture()
                if (nextHopMac == null)
                    new DropAction: Action
                else {
                    matchOut.setEthernetDestination(nextHopMac)
                    new ForwardAction(rt.nextHopPort, matchOut): Action
                }
            }(ec)
        }
    }

    private def getRouterPortConfig(portID: UUID): RouterPortConfig = {
        null //XXX
    }

    private def processArpRequest(pkt: ARP, portID: UUID,
                                  rtrPortCfg: RouterPortConfig) {
        //XXX
    }

    private def processArpReply(pkt: ARP, portID: UUID,
                                rtrPortCfg: RouterPortConfig) {
        // Verify the reply:  It's addressed to our MAC & IP, and is about
        // the MAC for an IPv4 address.
        if (pkt.getHardwareType != ARP.HW_TYPE_ETHERNET ||
                pkt.getProtocolType != ARP.PROTO_TYPE_IP) {
            log.debug("Router {} ignoring ARP reply on port {} because hwtype "+
                      "wasn't Ethernet or prototype wasn't IPv4.", id, portID)
            return
        }
        val tpa: Int = IPv4.toIPv4Address(pkt.getTargetProtocolAddress)
        val tha: MAC = pkt.getTargetHardwareAddress
        if (tpa != rtrPortCfg.portAddr || tha != rtrPortCfg.getHwAddr) {
            log.debug("Router {} ignoring ARP reply on port {} because tpa or "+
                      "tha doesn't match.", id, portID)
            return
        }
        // Question:  Should we check if the ARP reply disagrees with an
        // existing cache entry and make noise if so?

        val sha: MAC = pkt.getSenderHardwareAddress
        val spa = new IntIPv4(pkt.getSenderProtocolAddress)
        arpTable.set(spa, sha)
    }

    private def isIcmpEchoRequest(mmatch: WildcardMatch): Boolean = {
        mmatch.getNetworkProtocol == ICMP.PROTOCOL_NUMBER &&
            (mmatch.getTransportSource & 0xff) == ICMP.TYPE_ECHO_REQUEST &&
            (mmatch.getTransportDestination & 0xff) == ICMP.CODE_NONE
    }

    private def sendIcmpEchoReply(rtrPortCfg: RouterPortConfig) {
        //XXX
    }

    private def sendIcmp(ingressMatch: WildcardMatch, code: UNREACH_CODE) {
        //XXX
    }

    private def getPeerMac(rtrPortCfg: LogicalRouterPortConfig): MAC = {
        null //XXX
    }

    private def getMacForIP(portID: UUID, nextHopIP: Int, expiry: Long,
                            ec: ExecutionContext): Option[Future[MAC]] = {
        val nwAddr = new IntIPv4(nextHopIP)
        val rtrPortConfig = getRouterPortConfig(portID)
        if (rtrPortConfig == null) {
            log.error("cannot get configuration for port {}", portID)
            return None
        }
        rtrPortConfig match {
            case mPortConfig: MaterializedRouterPortConfig =>
                val shift = 32 - mPortConfig.localNwLength
                // Shifts by 32 in java are no-ops (see
                // http://www.janeg.ca/scjp/oper/shift.html), so special case
                // nwLength=0 <=> shift=32 to always match.
                if ((nextHopIP >>> shift) !=
                        (mPortConfig.localNwAddr >>> shift) &&
                        shift != 32) {
                    log.warn("getMacForIP: cannot get MAC for {} - address " +
                        "not in network segment of port {} ({}/{})",
                        Array[Object](nwAddr, portID,
                            mPortConfig.localNwAddr.toString,
                            mPortConfig.localNwLength.toString))
                    return None
                }
            case _ => /* Fall through */
        }
        return Some(arpTable.get(nwAddr, expiry, ec))
    }
}

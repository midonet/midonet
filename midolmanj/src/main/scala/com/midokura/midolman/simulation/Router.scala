/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.simulation

import akka.dispatch.{Await, Promise, ExecutionContext}
import akka.util.duration._
import java.util.UUID
import org.slf4j.LoggerFactory

import com.midokura.midolman.state.zkManagers.RouterZkManager
import com.midokura.midolman.layer3.{Route, RoutingTable}
import com.midokura.midolman.state.zkManagers.RouterZkManager.RouterConfig
import com.midokura.packets.{ARP, Ethernet, ICMP, IntIPv4, IPv4, MAC}
import com.midokura.packets.ICMP.UNREACH_CODE
import com.midokura.midolman.state.PortDirectory.{LogicalRouterPortConfig,
                                                  MaterializedRouterPortConfig,
                                                  RouterPortConfig}
import com.midokura.midolman.openflow.MidoMatch
import com.midokura.midonet.cluster.client.ArpCache
import com.midokura.util.functors.Callback1


class Router(val id: UUID, val cfg: RouterConfig, val rTable: RoutingTable,
             val arpTable: ArpCache, val inFilter: Chain,
             val outFilter: Chain) extends Device {

    private val log = LoggerFactory.getLogger(classOf[Router])
    private val loadBalancer = new LoadBalancer(rTable)

    override def process(ingress: PacketContext,
                         ec: ExecutionContext): ProcessResult = {
        val hwDst = new MAC(ingress.mmatch.getDataLayerDestination)
        val rtrPortCfg: RouterPortConfig = getRouterPortConfig(ingress.port)
        if (rtrPortCfg == null) {
            log.error("Could not get configuration for port " + ingress.port)
            return new DropResult()
        }
        if (ingress.mmatch.getDataLayerType != IPv4.ETHERTYPE &&
                ingress.mmatch.getDataLayerType != ARP.ETHERTYPE)
            return new NotIPv4Result()

        if (Ethernet.isBroadcast(hwDst)) {
            // Broadcast packet:  Handle if ARP, drop otherwise.
            if (ingress.mmatch.getDataLayerType == ARP.ETHERTYPE &&
                    ingress.mmatch.getNetworkProtocol == ARP.OP_REQUEST) {
                processArpRequest(ingress.packet.getPayload.asInstanceOf[ARP],
                                  ingress.port, rtrPortCfg)
                return new ConsumedResult()
            } else
                return new DropResult()
        }

        if (hwDst != rtrPortCfg.getHwAddr) {
            // Not addressed to us, log.warn and drop.
            log.warn("{} neither broadcast nor inPort's MAC ({})", hwDst,
                     rtrPortCfg.getHwAddr)
            return new DropResult()
        }

        if (ingress.mmatch.getDataLayerType == ARP.ETHERTYPE) {
            // Non-broadcast ARP.  Handle reply, drop rest.
            if (ingress.mmatch.getNetworkProtocol == ARP.OP_REPLY) {
                processArpReply(ingress.packet.getPayload.asInstanceOf[ARP],
                                ingress.port, rtrPortCfg)
                return new ConsumedResult()
            } else
                return new DropResult()
        }

        val nwDst = new IntIPv4(ingress.mmatch.getNetworkDestination)
        val inPortIP = new IntIPv4(rtrPortCfg.portAddr)
        if (nwDst == inPortIP) {
            // We're the L3 destination.  Reply to ICMP echos, drop the rest.
            if (isIcmpEchoRequest(ingress.mmatch)) {
                sendIcmpEchoReply(ingress, rtrPortCfg)
                return new ConsumedResult()
            } else
                return new DropResult()
        }

        // XXX: Apply the pre-routing (ingress) chain

        val rt: Route = loadBalancer.lookup(ingress.mmatch)
        if (rt == null) {
            // No route to network
            sendIcmp(ingress, UNREACH_CODE.UNREACH_NET)
            return new DropResult()
        }
        if (rt.nextHop == Route.NextHop.BLACKHOLE) {
            return new DropResult()
        }
        if (rt.nextHop == Route.NextHop.REJECT) {
            sendIcmp(ingress, UNREACH_CODE.UNREACH_FILTER_PROHIB)
            return new DropResult()
        }
        if (rt.nextHop != Route.NextHop.PORT) {
            log.error("Routing table lookup for {} returned invalid nextHop " +
                "of {}", nwDst, rt.nextHop)
            // TODO(jlm, pino): Should this be an exception?
            return new DropResult()
        }
        if (rt.nextHopPort == null) {
            log.error("Routing table lookup for {} forwarded to port null.",
                nwDst)
            // TODO(pino): should we remove this route?
            return new DropResult()
        }

        // XXX: Apply post-routing (egress) chain.

        val outPortCfg = getRouterPortConfig(rt.nextHopPort)
        if (outPortCfg == null) {
            log.error("Can't find the configuration for the egress port {}",
                rt.nextHopPort)
            return new DropResult()
        }
        val outPortIP = new IntIPv4(outPortCfg.portAddr)
        if (nwDst == outPortIP) {
            // Drop.  TODO(jlm,pino): Should we check for ICMP echos?
            return new DropResult()
        }
        var matchOut = ingress.mmatch.clone
        // Set HWSrc
        matchOut.setDataLayerSource(outPortCfg.getHwAddr)
        // Set HWDst
        outPortCfg match {
            case logCfg: LogicalRouterPortConfig =>
                if (logCfg.peerId == null) {
                    log.warn("Packet forwarded to dangling logical port {}",
                        rt.nextHopPort)
                    sendIcmp(ingress, UNREACH_CODE.UNREACH_NET)
                    return new DropResult()
                }
                val peerMac = getPeerMac(logCfg)
                if (peerMac != null) {
                    matchOut.setDataLayerDestination(peerMac)
                    return new ForwardResult(new PortMatch(rt.nextHopPort,
                        matchOut))
                }
            // TODO(jlm,pino): Should not having the peerMac be an error?
            case _ => /* Fall through to ARP'ing below. */
        }
        var nextHopIP: Int = rt.nextHopGateway
        if (nextHopIP == 0 || nextHopIP == -1)
            nextHopIP = matchOut.getNetworkDestination /* Last hop */
        val nextHopMac = getMacForIP(rt.nextHopPort, nextHopIP, ec)
        if (nextHopMac != null) {
            matchOut.setDataLayerDestination(nextHopMac)
            return new ForwardResult(new PortMatch(rt.nextHopPort, matchOut))
        } else {
            // Couldn't get the MAC.  getMacForIP will send any ICPM !H,
            // so here we just drop.
            return new DropResult()
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
        arpTable.add(spa, null /*XXX: sha*/)
    }

    private def isIcmpEchoRequest(mmatch: MidoMatch): Boolean = {
        mmatch.getNetworkProtocol == ICMP.PROTOCOL_NUMBER &&
            (mmatch.getTransportSource & 0xff) == ICMP.TYPE_ECHO_REQUEST &&
            (mmatch.getTransportDestination & 0xff) == ICMP.CODE_NONE
    }

    private def sendIcmpEchoReply(context: PacketContext,
                                  rtrPortCfg: RouterPortConfig) {
        //XXX
    }

    private def sendIcmp(context: PacketContext, code: UNREACH_CODE) {
        //XXX
    }

    private def getPeerMac(rtrPortCfg: LogicalRouterPortConfig): MAC = {
        null //XXX
    }

    private def getMacForIP(portID: UUID, nextHopIP: Int,
                            ec: ExecutionContext): MAC = {
        val nwAddr = new IntIPv4(nextHopIP)
        val rtrPortConfig = getRouterPortConfig(portID)
        if (rtrPortConfig == null) {
            log.error("cannot get configuration for port {}", portID)
            return null
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
                    return null
                }
            case _ => /* Fall through */
        }
        return getArpTableEntry(nwAddr, ec)
    }

    def getArpTableEntry(ipAddr: IntIPv4, ec: ExecutionContext): MAC = {
        null  //XXX
    }
}

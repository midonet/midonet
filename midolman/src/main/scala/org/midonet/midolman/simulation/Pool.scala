/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.simulation

import java.util.UUID

import akka.event.LoggingBus

import org.midonet.midolman.logging.LoggerFactory
import org.midonet.midolman.state.l4lb.PoolLBMethod
import org.midonet.midolman.state.NatState.NatKey
import org.midonet.packets.{IPAddr, ICMP}
import org.midonet.sdn.flows.FlowTagger
import org.midonet.util.collection.WeightedSelector

object Pool {
    def findPoolMember(ip: IPAddr, port: Int, pmArray: Array[PoolMember])
    : Boolean = {
        var i = 0
        while (i < pmArray.size) {
            val pm = pmArray(i)
            if (pm.address == ip && pm.protocolPort == port)
                return true
            i += 1
        }
        false
    }
}

class Pool(val id: UUID, val adminStateUp: Boolean, val lbMethod: PoolLBMethod,
           val activePoolMembers: Array[PoolMember],
           val disabledPoolMembers: Array[PoolMember],
           val loggingBus: LoggingBus) {
    private val log =
        LoggerFactory.getSimulationAwareLog(this.getClass)(loggingBus)

    val deviceTag = FlowTagger.tagForDevice(id)

    val isUp = adminStateUp && activePoolMembers.nonEmpty

    private val memberSelector = if (!isUp) null
                                 else WeightedSelector(activePoolMembers)

    /**
     * Choose an active pool member and apply DNAT to the packetContext
     * to redirect traffic to that pool member.
     *
     * If an existing NAT mapping is present, we respect that instead of mapping
     * to a new backend, in order to maintain existing connections.
     *
     * Return action based on outcome: ACCEPT if loadbalanced successfully,
     * DROP if no active pool member is available.
     */
    def loadBalance(pktContext: PacketContext,
                    stickySourceIP: Boolean): Boolean = {
        implicit val implicitPacketContext = pktContext

        pktContext.addFlowTag(deviceTag)

        if (isUp) {
            val member = memberSelector.select()
            if (log.isDebugEnabled) {
                log.debug("{} - Selected member {} out of {}", pktContext.cookieStr,
                          member, activePoolMembers.mkString(", "))
            }
            maintainConnectionOrLoadBalanceTo(member, stickySourceIP)
            true
        } else {
            maintainConnectionIfExists(stickySourceIP)
        }
    }

    /**
     * Applies the reverse load balance DNAT. If the source pool member is down
     * and it's a sticky IP, we drop the packet.
     */
    def reverseLoadBalanceValid(pktCtx: PacketContext, ip: IPAddr,
                                port: Int, stickySourceIP: Boolean): Boolean = {
        pktCtx.addFlowTag(deviceTag)
        isValidBackend(ip, port, stickySourceIP)
    }

    /*
     * Apply DNAT to the packetContext to redirect traffic according to the
     * existing NAT mapping if present, or to the specified pool member if not.
     *
     * Returns ACCEPT action when loadbalanced successfully.
     */
    private def maintainConnectionOrLoadBalanceTo(poolMember: PoolMember,
                                                  stickySourceIP: Boolean)
                                         (implicit pktContext: PacketContext)
    : Unit =
        if (!applyExistingIfValidBackend(stickySourceIP))
            poolMember.applyDnat(pktContext, stickySourceIP)

    /*
     * Apply DNAT to the packetContext to redirect traffic according to the
     * existing NAT mapping if present.
     *
     * Returns ACCEPT if loadbalanced successfully, DROP if no existing NAT
     * mapping was available.
     */
    def maintainConnectionIfExists(stickySourceIP: Boolean)
                                  (implicit pktContext: PacketContext): Boolean = {
        // Even if there are no active pool members, we should keep
        // existing connections alive.
        val pktMatch = pktContext.wcmatch
        if (stickySourceIP || pktMatch.getNetworkProtocol == ICMP.PROTOCOL_NUMBER) {
            // If all members are marked down, we stop connections
            log.debug("{} - Stopping potential connection", pktContext.cookieStr)
            false
        } else {
            applyExistingIfValidBackend(stickySourceIP = false)
        }
    }

    // Tries to apply a pre-existing connection if the backend is valid
    private def applyExistingIfValidBackend(stickySourceIP: Boolean)
                                            (implicit pktContext: PacketContext)
    : Boolean = {
        val vipIp = pktContext.wcmatch.getNetworkDestinationIP
        val vipPort = pktContext.wcmatch.getTransportDestination
        val natKey = NatKey(pktContext.wcmatch,
                            if (stickySourceIP) NatKey.FWD_STICKY_DNAT
                            else NatKey.FWD_DNAT)
        if (pktContext.state.applyIfExists(natKey)) {
            val validBackend = isValidBackend(pktContext, stickySourceIP)
            log.debug("{} - Found existing {}; backend is valid = {}",
                      pktContext.cookieStr, natKey, validBackend)
            if (!validBackend) {
                // Reset the destination IP / port to be VIP IP / port
                pktContext.wcmatch.setNetworkDestination(vipIp)
                pktContext.wcmatch.setTransportDestination(vipPort)

                // Delete the current NAT entry we found
                deleteNatEntry(pktContext, stickySourceIP)
            }
            validBackend
        } else false
    }

    private def isValidBackend(pktContext: PacketContext,
                               stickySourceIP: Boolean): Boolean =
        isValidBackend(pktContext.wcmatch.getNetworkDestinationIP,
                       pktContext.wcmatch.getTransportDestination,
                       stickySourceIP)

    /*
      * Decide if we should allow traffic to be loadbalanced to this ip:port
      *
      * If sticky source ip, we only send traffic to backends which are up
      *
      * If not sticky source IP, it's OK to send traffic to a disabled
      * backend until the mapping expires, to allow the connection to finish
      */
    private def isValidBackend(ip: IPAddr,
                               port: Int,
                               stickySourceIP: Boolean): Boolean =
        if (stickySourceIP)
            isActiveBackend(ip, port)
        else
            isActiveBackend(ip, port) || isDisabledBackend(ip, port)

    private def isActiveBackend(ip: IPAddr, port: Int) =
        Pool.findPoolMember(ip, port, activePoolMembers)

    private def isDisabledBackend(ip: IPAddr, port: Int) =
        Pool.findPoolMember(ip, port, disabledPoolMembers)

    private def deleteNatEntry(pktContext: PacketContext,
                               stickySourceIP: Boolean): Unit = {
        val natKey = NatKey(pktContext.wcmatch,
                            if (stickySourceIP) NatKey.FWD_STICKY_DNAT
                            else NatKey.FWD_DNAT)
        pktContext.state.deleteNatBinding(natKey)
    }
}

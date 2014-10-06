/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.simulation

import java.util.UUID

import akka.event.LoggingBus

import org.midonet.midolman.state.l4lb.PoolLBMethod
import org.midonet.midolman.state.NatState
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
           val disabledPoolMembers: Array[PoolMember]) {

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
    def loadBalance(context: PacketContext,
                    loadBalancer: UUID,
                    stickySourceIP: Boolean): Boolean = {
        implicit val implicitPacketContext = context

        context.addFlowTag(deviceTag)

        if (isUp) {
            val member = memberSelector.select()
            if (context.log.underlying.isDebugEnabled) {
                context.log.debug(s"Selected member $member out of {}",
                                  activePoolMembers.mkString(", "))
            }
            maintainConnectionOrLoadBalanceTo(member, loadBalancer, stickySourceIP)
            true
        } else {
            maintainConnectionIfExists(loadBalancer, stickySourceIP)
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
                                                  loadBalancer: UUID,
                                                  stickySourceIP: Boolean)
                                         (implicit context: PacketContext)
    : Unit =
        if (!applyExistingIfValidBackend(loadBalancer, stickySourceIP))
            poolMember.applyDnat(context, loadBalancer, stickySourceIP)

    /*
     * Apply DNAT to the packetContext to redirect traffic according to the
     * existing NAT mapping if present.
     *
     * Returns ACCEPT if loadbalanced successfully, DROP if no existing NAT
     * mapping was available.
     */
    def maintainConnectionIfExists(loadBalancer: UUID, stickySourceIP: Boolean)
                                  (implicit context: PacketContext): Boolean = {
        // Even if there are no active pool members, we should keep
        // existing connections alive.
        val pktMatch = context.wcmatch
        if (stickySourceIP || pktMatch.getNetworkProtocol == ICMP.PROTOCOL_NUMBER) {
            // If all members are marked down, we stop connections
            context.log.debug("Stopping potential connection")
            false
        } else {
            applyExistingIfValidBackend(loadBalancer, stickySourceIP = false)
        }
    }

    // Tries to apply a pre-existing connection if the backend is valid
    private def applyExistingIfValidBackend(loadBalancer: UUID,
                                            stickySourceIP: Boolean)
                                            (implicit context: PacketContext)
    : Boolean = {
        val vipIp = context.wcmatch.getNetworkDestinationIP
        val vipPort = context.wcmatch.getTransportDestination
        val natKey = NatKey(context.wcmatch,
                            loadBalancer,
                            if (stickySourceIP) NatState.FWD_STICKY_DNAT
                            else NatState.FWD_DNAT)
        if (context.state.applyIfExists(natKey)) {
            val backendIsValid = isValidBackend(context, stickySourceIP)
            context.log.debug(s"Found existing $natKey; backend valid: $backendIsValid")
            if (!backendIsValid) {
                // Reset the destination IP / port to be VIP IP / port
                context.wcmatch.setNetworkDestination(vipIp)
                context.wcmatch.setTransportDestination(vipPort)

                // Delete the current NAT entry we found
                deleteNatEntry(context, loadBalancer, stickySourceIP)
            }
            backendIsValid
        } else false
    }

    private def isValidBackend(context: PacketContext,
                               stickySourceIP: Boolean): Boolean =
        isValidBackend(context.wcmatch.getNetworkDestinationIP,
                       context.wcmatch.getTransportDestination,
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

    private def deleteNatEntry(context: PacketContext,
                               loadBalancer: UUID,
                               stickySourceIP: Boolean): Unit = {
        val natKey = NatKey(context.wcmatch,
                            loadBalancer,
                            if (stickySourceIP) NatState.FWD_STICKY_DNAT
                            else NatState.FWD_DNAT)
        context.state.deleteNatBinding(natKey)
    }
}

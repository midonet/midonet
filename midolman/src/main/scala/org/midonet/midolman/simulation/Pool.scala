/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.midolman.simulation

import java.util.UUID

import akka.event.LoggingBus

import org.midonet.midolman.layer4.{NwTpPair, NatMapping}
import org.midonet.midolman.logging.LoggerFactory
import org.midonet.midolman.rules.RuleResult
import org.midonet.midolman.topology.FlowTagger
import org.midonet.packets.{IPv4Addr, IPAddr, ICMP}
import org.midonet.util.collection.WeightedSelector
import org.midonet.midolman.l4lb.{ForwardStickyNatRule, ReverseStickyNatRule}
import org.midonet.midolman.state.l4lb.PoolLBMethod

object Pool {
    def findPoolMember(ip: IPAddr, port: Int, pmArray: Array[PoolMember])
    : Boolean = {
        var i: Int = 0
        var found = false

        while (i < pmArray.size && !found) {
            val pm: PoolMember = pmArray(i)
            if (pm.address == ip && pm.protocolPort == port) {
                found = true
            }
            i = i + 1
        }

        found
    }
}

class Pool(val id: UUID, val adminStateUp: Boolean, val lbMethod: PoolLBMethod,
           val activePoolMembers: Array[PoolMember],
           val disabledPoolMembers: Array[PoolMember],
           val loggingBus: LoggingBus) {

    private val log =
        LoggerFactory.getSimulationAwareLog(this.getClass)(loggingBus)

    val invalidatePoolTag = FlowTagger.invalidateFlowsByDevice(id)

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
    def loadBalance(natMapping: NatMapping, pktContext: PacketContext,
                    stickySourceIP: Boolean, stickyTimeoutSeconds: Int)
    : RuleResult.Action = {
        // For logger.
        implicit val implicitPacketContext = pktContext
        implicit val implicitNatMapping = natMapping

        pktContext.addFlowTag(invalidatePoolTag)

        if (isUp) {
            maintainConnectionOrLoadBalanceTo(memberSelector.select,
                                              stickySourceIP,
                                              stickyTimeoutSeconds)
        } else {
            maintainConnectionIfExists(stickySourceIP, stickyTimeoutSeconds)
        }
    }

    /*
     * Apply DNAT to the packetContext to redirect traffic according to the
     * existing NAT mapping if present, or to the specified pool member if not.
     *
     * Returns ACCEPT action when loadbalanced successfully.
     */
    private def maintainConnectionOrLoadBalanceTo(poolMember: PoolMember,
                                                  stickySourceIP: Boolean,
                                                  stickyTimeoutSeconds: Int)
                                         (implicit natMapping: NatMapping,
                                          pktContext: PacketContext)
    : RuleResult.Action = {
        val ruleResult = new RuleResult(RuleResult.Action.ACCEPT,
                                        null, pktContext.wcmatch)

        val vipIp = pktContext.wcmatch.getNetworkDestinationIP
        val vipPort = pktContext.wcmatch.getTransportDestination

        // Apply the rule, which changes the pktContext and applies DNAT
        poolMember.applyDnat(ruleResult, pktContext, natMapping,
                             stickySourceIP, stickyTimeoutSeconds)

        val validBackend: Boolean = isValidBackend(pktContext, stickySourceIP)

        if (!validBackend) {
            //Delete the NAT entry we found
            deleteNatEntry(pktContext, stickySourceIP, vipIp,
                vipPort, natMapping)

            // Reset the destination IP / port to be VIP IP / port
            pktContext.wcmatch.setNetworkDestination(vipIp)
            pktContext.wcmatch.setTransportDestination(vipPort)

            // Apply the rule again
            poolMember.applyDnat(ruleResult, pktContext, natMapping,
                stickySourceIP, stickyTimeoutSeconds)
        }


        // Load balanced successfully
        RuleResult.Action.ACCEPT
    }

    /*
     * Apply DNAT to the packetContext to redirect traffic according to the
     * existing NAT mapping if present.
     *
     * Returns ACCEPT if loadbalanced successfully, DROP if no existing NAT
     * mapping was available.
     */
    def maintainConnectionIfExists(stickySourceIP: Boolean,
                                   stickyTimeoutSeconds: Int)
                                  (implicit natMapping: NatMapping,
                                   pktContext: PacketContext)
    : RuleResult.Action = {
        // Even if there are no active pool members, we should keep
        // existing connections alive, so check for existing NatMapping
        val pktMatch = pktContext.wcmatch

        val proto = pktMatch.getNetworkProtocol
        val nwSrc = pktMatch.getNetworkSourceIP
        val tpSrc = pktMatch.getTransportSource
        val nwDst = pktMatch.getNetworkDestinationIP
        val tpDst = pktMatch.getTransportDestination

        proto match {
            case icmpProtocol if icmpProtocol == ICMP.PROTOCOL_NUMBER =>
                RuleResult.Action.DROP
            case otherProtocol =>
                val foundMapping = {
                    if (stickySourceIP) {
                        // If all members are marked down, we stop connections
                        null
                    } else {
                        natMapping.lookupDnatFwd(proto, nwSrc,
                            tpSrc, nwDst, tpDst)
                    }
                }
                foundMapping match {
                    case null =>
                        RuleResult.Action.DROP
                    case mapping =>
                        if (isValidBackend(mapping, stickySourceIP)) {
                            pktMatch.setNetworkDestination(mapping.nwAddr)
                            pktMatch.setTransportDestination(mapping.tpPort)
                            RuleResult.Action.ACCEPT
                        } else {
                            RuleResult.Action.DROP
                        }
                }
            }
    }

    private def isValidBackend(pktContext: PacketContext,
                                  stickySourceIP: Boolean): Boolean =
        isValidBackend(pktContext.wcmatch.getNetworkDestinationIP,
                       pktContext.wcmatch.getTransportDestination,
                       stickySourceIP)

    private def isValidBackend(mapping: NwTpPair,
                               stickySourceIP: Boolean): Boolean =
        isValidBackend(mapping.nwAddr, mapping.tpPort, stickySourceIP)

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
        if (stickySourceIP) {
            isActiveBackend(ip, port)
        } else {
            isActiveBackend(ip, port) || isDisabledBackend(ip, port)
        }

    private def isActiveBackend(ip: IPAddr, port: Int) =
        Pool.findPoolMember(ip, port, activePoolMembers)

    private def isDisabledBackend(ip: IPAddr, port: Int) =
        Pool.findPoolMember(ip, port, disabledPoolMembers)

    private def deleteNatEntry(pktContext: PacketContext,
                               stickySourceIP: Boolean,
                               vipIP: IPAddr,
                               vipPort: Int,
                               natMapping: NatMapping) {
        val pmatch = pktContext.wcmatch
        val proto = pmatch.getNetworkProtocol

        val balancedToPort = pmatch.getTransportDestination
        val balancedToIp = pmatch.getNetworkDestinationIP

        val sourcePort = if (stickySourceIP) {
            ReverseStickyNatRule.WILDCARD_PORT
        } else {
            pmatch.getTransportSource.intValue()
        }

        val sourceIP = pmatch.getNetworkSourceIP

        natMapping.deleteDnatEntry(proto,
                                   sourceIP, sourcePort,
                                   vipIP, vipPort,
                                   balancedToIp, balancedToPort)
    }


}

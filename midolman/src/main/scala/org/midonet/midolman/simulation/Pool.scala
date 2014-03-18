/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.midolman.simulation

import java.util.UUID

import akka.event.LoggingBus

import org.midonet.midolman.layer4.NatMapping
import org.midonet.midolman.logging.LoggerFactory
import org.midonet.midolman.rules.RuleResult
import org.midonet.midolman.topology.FlowTagger
import org.midonet.packets.ICMP
import org.midonet.util.collection.WeightedSelector

class Pool(val id: UUID, val adminStateUp: Boolean, val lbMethod: String,
           val poolMembers: Traversable[PoolMember],
           val loggingBus: LoggingBus) {

    private val log =
        LoggerFactory.getSimulationAwareLog(this.getClass)(loggingBus)

    val invalidatePoolTag = FlowTagger.invalidateFlowsByDevice(id)

    val isUp = adminStateUp && poolMembers.nonEmpty

    private val memberSelector = if (!isUp) null
                                 else WeightedSelector(poolMembers)

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

        // Apply the rule, which changes the pktContext and applies DNAT
        poolMember.applyDnat(ruleResult, pktContext, natMapping,
                             stickySourceIP, stickyTimeoutSeconds)

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
                        natMapping.lookupDnatFwd(proto, nwSrc,
                            tpSrc, nwDst, tpDst, stickyTimeoutSeconds)
                    } else {
                        natMapping.lookupDnatFwd(proto, nwSrc,
                            tpSrc, nwDst, tpDst)
                    }
                }
                foundMapping match {
                    case null =>
                        RuleResult.Action.DROP
                    case mapping =>
                        pktMatch.setNetworkDestination(mapping.nwAddr)
                        pktMatch.setTransportDestination(mapping.tpPort)
                        RuleResult.Action.ACCEPT
                }
            }
    }

}

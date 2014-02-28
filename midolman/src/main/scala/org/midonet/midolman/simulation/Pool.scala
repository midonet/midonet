/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.midolman.simulation

import java.util.{HashSet => JHashSet, UUID}
import scala.util.Random

import akka.event.LoggingBus

import org.midonet.midolman.layer4.{NwTpPair, NatMapping}
import org.midonet.midolman.logging.LoggerFactory
import org.midonet.midolman.rules.{RuleResult, Condition,
                                   ForwardNatRule, NatTarget}
import org.midonet.midolman.topology.FlowTagger
import org.midonet.packets.ICMP

class Pool(val id: UUID, val adminStateUp: Boolean, val lbMethod: String,
           val poolMembers: List[PoolMember], val loggingBus: LoggingBus) {
    val log =
        LoggerFactory.getSimulationAwareLog(this.getClass)(loggingBus)

    val invalidatePoolTag = FlowTagger.invalidateFlowsByDevice(id)

    private val activePoolMembers = poolMembers.filter(_.isUp)

    /*
     * Choose an active pool member and apply DNAT to the packetContext
     * to redirect traffic to that pool member.
     *
     * If an existing NAT mapping is present, we respect that instead of mapping
     * to a new backend, in order to maintain existing connections.
     *
     * Return action based on outcome: ACCEPT if loadbalanced successfully,
     * DROP if no active pool member is available.
     */
    def loadBalance(natMapping: NatMapping, pktContext: PacketContext)
    : RuleResult.Action = {

        implicit val implicitPacketContext = pktContext
        implicit val implicitNatMapping = natMapping

        pktContext.addFlowTag(invalidatePoolTag)

        val poolMember: Option[PoolMember] = activePoolMembers match {
            case Nil => None
            case members => {
                    val index = Random.nextInt(members.size)
                    Some(members(index))
                }
        }

        poolMember match {
            case Some(member) => maintainConnectionOrLoadBalanceTo(member)
            case None => maintainConnectionIfExists()
        }
    }

    /*
     * Apply DNAT to the packetContext to redirect traffic according to the
     * existing NAT mapping if present, or to the specified pool member if not.
     *
     * Returns ACCEPT action when loadbalanced successfully.
     */
    private def maintainConnectionOrLoadBalanceTo(poolMember: PoolMember)
                                         (implicit natMapping: NatMapping,
                                          pktContext: PacketContext)
    : RuleResult.Action = {
        // Create a simple NAT rule mapping VIP to this backend
        val target = new NatTarget(poolMember.address, poolMember.address,
            poolMember.protocolPort, poolMember.protocolPort)
        val targets = new JHashSet[NatTarget]()
        targets.add(target)

        val rule = new ForwardNatRule(new Condition(), RuleResult.Action.ACCEPT,
            null, 0, true, targets)
        val ruleResult = new RuleResult(RuleResult.Action.ACCEPT,
            null, pktContext.wcmatch)

        // Apply the rule, which changes the pktContext and applies DNAT
        rule.apply(pktContext, ruleResult, natMapping)

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
    def maintainConnectionIfExists()(implicit natMapping: NatMapping,
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
                val foundMapping = natMapping.lookupDnatFwd(proto, nwSrc,
                    tpSrc, nwDst, tpDst)
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

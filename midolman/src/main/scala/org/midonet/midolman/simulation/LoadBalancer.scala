/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.simulation

import java.util.UUID

import akka.actor.ActorSystem

import org.midonet.midolman.rules.RuleResult
import org.midonet.midolman.topology.VirtualTopologyActor.tryAsk
import org.midonet.sdn.flows.FlowTagger

object LoadBalancer {
    def simpleAcceptRuleResult(context: PacketContext) =
        simpleRuleResult(RuleResult.Action.ACCEPT, context)

    def simpleContinueRuleResult(context: PacketContext) =
        simpleRuleResult(RuleResult.Action.CONTINUE, context)

    def simpleRuleResult(action: RuleResult.Action, context: PacketContext) =
        new RuleResult(action, null, context.wcmatch)
}

class LoadBalancer(val id: UUID, val adminStateUp: Boolean, val routerId: UUID,
                   val vips: Array[VIP]) {

    import LoadBalancer._

    val deviceTag = FlowTagger.tagForDevice(id)

    val hasStickyVips: Boolean = vips.exists(_.isStickySourceIP)
    val hasNonStickyVips: Boolean = vips.exists(!_.isStickySourceIP)

    def processInbound(context: PacketContext)(implicit actorSystem: ActorSystem)
    : RuleResult = {

        implicit val packetContext = context

        context.log.debug("Load balancer {} applying inbound rules", id)

        context.addFlowTag(deviceTag)

        if (adminStateUp) {
            findVip(context) match {
                case null => simpleContinueRuleResult(context)
                case vip => // Packet destined to this VIP, get relevant pool
                    context.log.debug("Traffic matched VIP ID {} in load balancer {}",
                                      vip.id, id)

                    // Choose a pool member and apply DNAT if an
                    // active pool member is found
                    val pool = tryAsk[Pool](vip.poolId)
                    val action =
                        if (pool.loadBalance(context, id, vip.isStickySourceIP))
                            RuleResult.Action.ACCEPT
                        else
                            RuleResult.Action.DROP
                    simpleRuleResult(action, context)
            }
        } else {
            simpleContinueRuleResult(context)
        }
    }

    def processOutbound(context: PacketContext)
                       (implicit actorSystem: ActorSystem): RuleResult = {
        implicit val packetContext = context

        context.log.debug("Load balancer {} applying outbound rules", id)

        // We check if the return flow is coming from an inactive pool member
        // with a sticky source. That means we must drop the flow as the key
        // is no longer applicable.

        val backendIp = packetContext.wcmatch.getNetworkSourceIP
        val backendPort = packetContext.wcmatch.getTransportSource

        // The order we test for the reverse NAT is important. First we
        // check if there's an entry that matches the client's source port,
        // which should be unique for every new connection. If there isn't,
        // we then check for a sticky NAT where we don't care about the source
        // port, that is, if they are different connections.
        if (!(hasNonStickyVips && packetContext.state.reverseDnat(id)) &&
            !(hasStickyVips && packetContext.state.reverseStickyDnat(id))) {
            return simpleContinueRuleResult(context)
        }

        if (!adminStateUp)
            return simpleRuleResult(RuleResult.Action.DROP, packetContext)

        findVipReturn(context) match {
            case null =>
                // The VIP is no longer.
                simpleRuleResult(RuleResult.Action.DROP, packetContext)
            case vip =>
                context.log.debug("Traffic matched VIP ID {} in load balancer {}",
                                  vip.id, id)

                // Choose a pool member and reverse DNAT if a valid pool member
                // is found.
                val pool = tryAsk[Pool](vip.poolId)
                val validMember = pool.reverseLoadBalanceValid(packetContext,
                                                               backendIp,
                                                               backendPort,
                                                               vip.isStickySourceIP)
                if (validMember)
                    simpleRuleResult(RuleResult.Action.ACCEPT, context)
                else
                    simpleRuleResult(RuleResult.Action.DROP, packetContext)
        }
    }

    private def findVip(context: PacketContext): VIP = {
        var i = 0
        while (i < vips.size) {
            if (vips(i).matches(context))
                return vips(i)
            i += 1
        }
        null
    }

    private def findVipReturn(context: PacketContext): VIP = {
        var i = 0
        while (i < vips.size) {
            if (vips(i).matchesReturn(context))
                return vips(i)
            i += 1
        }
        null
    }
}

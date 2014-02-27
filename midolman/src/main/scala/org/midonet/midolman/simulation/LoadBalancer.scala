/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman.simulation

import java.util.UUID

import akka.actor.ActorSystem
import akka.event.LoggingBus
import scala.concurrent.{ExecutionContext, Future}

import org.midonet.midolman.layer4.NatMapping
import org.midonet.midolman.logging.LoggerFactory
import org.midonet.midolman.rules.{Condition, RuleResult, ReverseNatRule}
import org.midonet.midolman.topology.VirtualTopologyActor.{expiringAsk, PoolRequest}
import org.midonet.midolman.topology.FlowTagger

object LoadBalancer {
    val simpleReverseDNatRule = new ReverseNatRule(
        Condition.TRUE, RuleResult.Action.ACCEPT, true)

    def simpleAcceptRuleResult(pktContext: PacketContext) =
        simpleRuleResult(RuleResult.Action.ACCEPT, pktContext)

    def simpleContinueRuleResult(pktContext: PacketContext) =
        simpleRuleResult(RuleResult.Action.CONTINUE, pktContext)

    def simpleRuleResult(action: RuleResult.Action, pktContext: PacketContext) =
        new RuleResult(action, null, pktContext.wcmatch)
}

class LoadBalancer(val id: UUID, val adminStateUp: Boolean,
                   val vips: Array[VIP], val loggingBus: LoggingBus) {

    import LoadBalancer._

    val log =
        LoggerFactory.getSimulationAwareLog(this.getClass)(loggingBus)

    val invalidateLoadBalancerTag = FlowTagger.invalidateFlowsByDevice(id)

    def processInbound(pktContext: PacketContext)(implicit ec: ExecutionContext,
                       actorSystem: ActorSystem)
    : Future[RuleResult] = {

        implicit val packetContext = pktContext

        log.debug(
            "Load balancer with id {} applying inbound rules", id)

        pktContext.addFlowTag(invalidateLoadBalancerTag)

        if (adminStateUp) {
            findVip(pktContext) match {
                case null => Future.successful(simpleContinueRuleResult(pktContext))
                case vip =>
                    // The packet is destined to this VIP - get the relevant pool
                    log.debug(
                        "Traffic matched VIP ID {} in load balancer ID {}",
                        vip.id, id)

                    expiringAsk[Pool](vip.poolId, log, pktContext.expiry) map {
                        pool =>
                            // Choose a pool member and apply DNAT if an
                            // active pool member is found
                            val action = pool.loadBalance(
                                Chain.natMappingFactory.get(id), pktContext)

                            simpleRuleResult(action, pktContext)
                    }

            }
        } else {
            Future.successful(simpleContinueRuleResult(pktContext))
        }
    }

    def processOutbound(pktContext: PacketContext)
    : RuleResult = {
        val natMapping = Chain.natMappingFactory.get(id)
        applyOutbound(pktContext, natMapping)
    }

    def applyOutbound(pktContext: PacketContext,
                      natMapping: NatMapping)
        : RuleResult = {

        implicit val packetContext = pktContext

        log.debug(
            "Load balancer with id {} applying outbound rules", id)

        // On the return flow, all we do is reverseNAT for existing flows.
        // Since setting adminState down doesn't cause us to stop reverse NATting
        // existing flows, we don't check admin state or tag the flow
        // with loadBalancerId in this step.

        val acceptRuleResult = simpleAcceptRuleResult(pktContext)

        val sourceIPBefore = pktContext.wcmatch.getNetworkSourceIP
        simpleReverseDNatRule.apply(pktContext, acceptRuleResult, natMapping)
        val sourceIPAfter = pktContext.wcmatch.getNetworkSourceIP

        // If source IP changed, the loadbalancer had an effect
        if (sourceIPAfter != sourceIPBefore){
            acceptRuleResult
        } else {
            simpleContinueRuleResult(pktContext)
        }
    }

    private def findVip(pktContext: PacketContext)
    : VIP = {
        // Use old-style loops intentionally to avoid closures
        var i = 0

        while (i < vips.size) {
            if (vips(i).matches(pktContext)) return vips(i)
            i = i + 1
        }

        null
    }
}

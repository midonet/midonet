/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.simulation

import java.util.UUID

import akka.actor.ActorSystem
import akka.event.LoggingBus

import scala.concurrent.ExecutionContext

import org.midonet.midolman.{Ready, Urgent}
import org.midonet.midolman.logging.LoggerFactory
import org.midonet.midolman.rules.RuleResult
import org.midonet.midolman.topology.VirtualTopologyActor.tryAsk
import org.midonet.sdn.flows.FlowTagger

object LoadBalancer {
    def simpleAcceptRuleResult(pktContext: PacketContext) =
        simpleRuleResult(RuleResult.Action.ACCEPT, pktContext)

    def simpleContinueRuleResult(pktContext: PacketContext) =
        simpleRuleResult(RuleResult.Action.CONTINUE, pktContext)

    def simpleRuleResult(action: RuleResult.Action, pktContext: PacketContext) =
        new RuleResult(action, null, pktContext.wcmatch)
}

class LoadBalancer(val id: UUID, val adminStateUp: Boolean, val routerId: UUID,
                   val vips: Array[VIP], val loggingBus: LoggingBus) {

    import LoadBalancer._

    val log =
        LoggerFactory.getSimulationAwareLog(this.getClass)(loggingBus)

    val deviceTag = FlowTagger.tagForDevice(id)

    val hasStickyVips: Boolean = vips.exists(_.isStickySourceIP)
    val hasNonStickyVips: Boolean = vips.exists(!_.isStickySourceIP)

    def processInbound(pktContext: PacketContext)(implicit ec: ExecutionContext,
                       actorSystem: ActorSystem)
    : Urgent[RuleResult] = {

        implicit val packetContext = pktContext

        log.debug("Load balancer with id {} applying inbound rules", id)

        pktContext.addFlowTag(deviceTag)

        if (adminStateUp) {
            findVip(pktContext) match {
                case null => Ready(simpleContinueRuleResult(pktContext))
                case vip => // Packet destined to this VIP, get relevant pool
                    log.debug("Traffic matched VIP ID {} in load balancer ID {}",
                              vip.id, id)

                    // Choose a pool member and apply DNAT if an
                    // active pool member is found
                    val pool = tryAsk[Pool](vip.poolId)
                    val action =
                        if (pool.loadBalance(pktContext, vip.isStickySourceIP))
                            RuleResult.Action.ACCEPT
                        else
                            RuleResult.Action.DROP
                    Ready(simpleRuleResult(action, pktContext))
            }
        } else {
            Ready(simpleContinueRuleResult(pktContext))
        }
    }

    def processOutbound(pktContext: PacketContext)
                       (implicit ec: ExecutionContext,
                                 actorSystem: ActorSystem): RuleResult = {
        implicit val packetContext = pktContext

        log.debug("Load balancer with id {} applying outbound rules", id)

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
        if (!(hasNonStickyVips && packetContext.state.reverseDnat()) &&
            !(hasStickyVips && packetContext.state.reverseStickyDnat())) {
            return simpleContinueRuleResult(pktContext)
        }

        if (!adminStateUp)
            return simpleRuleResult(RuleResult.Action.DROP, packetContext)

        findVipReturn(pktContext) match {
            case null =>
                // The VIP is no longer.
                simpleRuleResult(RuleResult.Action.DROP, packetContext)
            case vip =>
                log.debug("Traffic matched VIP ID {} in load balancer ID {}",
                          vip.id, id)

                // Choose a pool member and reverse DNAT if a valid pool member
                // is found.
                val pool = tryAsk[Pool](vip.poolId)
                val validMember = pool.reverseLoadBalanceValid(packetContext,
                                                               backendIp,
                                                               backendPort,
                                                               vip.isStickySourceIP)
                if (validMember)
                    simpleRuleResult(RuleResult.Action.ACCEPT, pktContext)
                else
                    simpleRuleResult(RuleResult.Action.DROP, packetContext)
        }
    }

    private def findVip(pktContext: PacketContext): VIP = {
        var i = 0
        while (i < vips.size) {
            if (vips(i).matches(pktContext))
                return vips(i)
            i += 1
        }
        null
    }

    private def findVipReturn(pktContext: PacketContext): VIP = {
        var i = 0
        while (i < vips.size) {
            if (vips(i).matchesReturn(pktContext))
                return vips(i)
            i += 1
        }
        null
    }
}

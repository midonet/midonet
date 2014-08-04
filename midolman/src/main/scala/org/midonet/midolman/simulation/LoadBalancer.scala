/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.midolman.simulation

import java.util.UUID

import akka.actor.ActorSystem

import org.midonet.midolman.rules.RuleResult
import org.midonet.midolman.topology.VirtualTopologyActor.tryAsk
import org.midonet.sdn.flows.FlowTagger

object LoadBalancer {
    val simpleAcceptRuleResult = new RuleResult(RuleResult.Action.ACCEPT, null)
    val simpleContinueRuleResult = new RuleResult(RuleResult.Action.CONTINUE, null)
    val simpleDropRuleResult = new RuleResult(RuleResult.Action.DROP, null)
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
                case null => simpleContinueRuleResult
                case vip => // Packet destined to this VIP, get relevant pool
                    context.log.debug("Traffic matched VIP ID {} in load balancer {}",
                                      vip.id, id)

                    // Choose a pool member and apply DNAT if an
                    // active pool member is found
                    val pool = tryAsk[Pool](vip.poolId)
                    if (pool.loadBalance(context, id, vip.isStickySourceIP))
                        simpleAcceptRuleResult
                    else
                        simpleDropRuleResult
            }
        } else {
            simpleContinueRuleResult
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
        val backendPort = packetContext.wcmatch.getSrcPort

        // The order we test for the reverse NAT is important. First we
        // check if there's an entry that matches the client's source port,
        // which should be unique for every new connection. If there isn't,
        // we then check for a sticky NAT where we don't care about the source
        // port, that is, if they are different connections.
        if (!(hasNonStickyVips && packetContext.state.reverseDnat(id)) &&
            !(hasStickyVips && packetContext.state.reverseStickyDnat(id))) {
            return simpleContinueRuleResult
        }

        if (!adminStateUp)
            return simpleDropRuleResult

        findVipReturn(context) match {
            case null =>
                // The VIP is no longer.
                simpleDropRuleResult
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
                    simpleAcceptRuleResult
                else
                    simpleDropRuleResult
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

/*
 * Copyright 2015 Midokura SARL
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

import java.util
import java.util.{Objects, UUID}

import akka.actor.ActorSystem

import org.midonet.midolman.rules.RuleResult
import org.midonet.midolman.topology.VirtualTopology.{VirtualDevice, tryGet}
import org.midonet.sdn.flows.FlowTagger

object LoadBalancer {
    val simpleAcceptRuleResult = new RuleResult(RuleResult.Action.ACCEPT)
    val simpleContinueRuleResult = new RuleResult(RuleResult.Action.CONTINUE)
    val simpleDropRuleResult = new RuleResult(RuleResult.Action.DROP)
}

class LoadBalancer(val id: UUID, val adminStateUp: Boolean, val routerId: UUID,
                   val vips: Array[Vip]) extends VirtualDevice {

    import LoadBalancer._

    override val deviceTag = FlowTagger.tagForLoadBalancer(id)

    val hasStickyVips: Boolean = vips.exists(_.isStickySourceIP)
    val hasNonStickyVips: Boolean = vips.exists(!_.isStickySourceIP)

    def processInbound(context: PacketContext)
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
                    val callerDevice = packetContext.currentDevice
                    packetContext.currentDevice = id

                    val pool = tryGet(classOf[Pool], vip.poolId)
                    val result = if (pool.loadBalance(context, vip.isStickySourceIP))
                                     simpleAcceptRuleResult
                                 else
                                     simpleDropRuleResult
                    packetContext.currentDevice = callerDevice
                    result
            }
        } else {
            simpleContinueRuleResult
        }
    }

    def processOutbound(context: PacketContext): RuleResult = {
        implicit val packetContext = context

        context.log.debug("Load balancer {} applying outbound rules", id)

        // We check if the return flow is coming from an inactive pool member
        // with a sticky source. That means we must drop the flow as the key
        // is no longer applicable.

        val backendIp = packetContext.wcmatch.getNetworkSrcIP
        val backendPort = packetContext.wcmatch.getSrcPort

        // The order we test for the reverse NAT is important. First we
        // check if there's an entry that matches the client's source port,
        // which should be unique for every new connection. If there isn't,
        // we then check for a sticky NAT where we don't care about the source
        // port, that is, if they are different connections.
        val callerDevice = packetContext.currentDevice
        packetContext.currentDevice = id
        if (!(hasNonStickyVips && packetContext.reverseDnat()) &&
            !(hasStickyVips && packetContext.reverseStickyDnat())) {
            packetContext.currentDevice = callerDevice
            return simpleContinueRuleResult
        }
        packetContext.currentDevice = callerDevice

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
                val pool = tryGet(classOf[Pool], vip.poolId)
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

    private def findVip(context: PacketContext): Vip = {
        var i = 0
        while (i < vips.length) {
            if (vips(i).matches(context))
                return vips(i)
            i += 1
        }
        null
    }

    private def findVipReturn(context: PacketContext): Vip = {
        var i = 0
        while (i < vips.length) {
            if (vips(i).matchesReturn(context))
                return vips(i)
            i += 1
        }
        null
    }

    override def equals(obj: Any): Boolean = obj match {
        case loadBalancer: LoadBalancer =>
            id == loadBalancer.id &&
            adminStateUp == loadBalancer.adminStateUp &&
            routerId == loadBalancer.routerId &&
            util.Arrays.equals(vips.asInstanceOf[Array[AnyRef]],
                               loadBalancer.vips.asInstanceOf[Array[AnyRef]])

        case _ => false
    }

    override def hashCode: Int =
        Objects.hashCode(id, adminStateUp, routerId, vips)

    override def toString =
        s"LoadBalancer [id=$id adminStateUp=$adminStateUp routerId=$routerId " +
        s"vips=${vips.toSeq}]"
}

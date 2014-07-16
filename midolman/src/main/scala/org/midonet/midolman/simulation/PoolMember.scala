/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.simulation

import java.util.UUID
import java.util.{HashSet => JHashSet}

import org.midonet.midolman.l4lb.ForwardStickyNatRule
import org.midonet.midolman.layer4.NatMapping
import org.midonet.midolman.rules._
import org.midonet.packets.IPv4Addr
import org.midonet.util.collection.HasWeight

/**
 * @param weight
 *        Pool member's weight. Odds of a pool member being selected
 *        are equal to its weight divided by the sum of the weights of
 *        all of its pool's members. A pool member with zero weight is
 *        considered down.
 */
class PoolMember(val id: UUID, val address: IPv4Addr,
                 val protocolPort:Int, val weight: Int) extends HasWeight {

    /**
     * NatRule used to NAT a packet to this pool member.
     */
    private val natTargets = {
        val targets = new JHashSet[NatTarget](1)
        targets.add(
            new NatTarget(address, address, protocolPort, protocolPort))
        targets
    }

    private val forwardNatRule =
        new ForwardNatRule(Condition.TRUE, RuleResult.Action.ACCEPT,
                           null, 0, true, natTargets)

    private def forwardStickyNatRule(stickyIpTimeout: Int) =
        new ForwardStickyNatRule(Condition.TRUE, RuleResult.Action.ACCEPT,
                                 null, 0, true, natTargets, stickyIpTimeout)

    protected[simulation] def applyDnat(res: RuleResult,
                                        pktContext: PacketContext,
                                        natMapping: NatMapping,
                                        stickySourceIP: Boolean,
                                        stickyTimeoutSeconds: Int) {
        val natRule: ForwardNatRule =
            if (stickySourceIP) {
                forwardStickyNatRule(stickyTimeoutSeconds)
            } else {
                forwardNatRule
            }

        natRule.apply(pktContext, res, natMapping)
    }

    override def toString = s"PoolMember[id=$id, address=$address, " +
                            s"protocolPort=$protocolPort, weight=$weight"
}

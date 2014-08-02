/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.simulation

import java.util.UUID

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
                 val protocolPort: Int, val weight: Int) extends HasWeight {

    private val natTargets = Array(new NatTarget(address, address,
                                                 protocolPort, protocolPort))

    protected[simulation] def applyDnat(pktContext: PacketContext,
                                        loadBalancer: UUID,
                                        stickySourceIP: Boolean): Unit =
        if (stickySourceIP)
            pktContext.state.applyStickyDnat(loadBalancer, natTargets)
        else
            pktContext.state.applyDnat(loadBalancer, natTargets)

    override def toString = s"PoolMember[id=$id, address=$address, " +
                            s"protocolPort=$protocolPort, weight=$weight]"
}

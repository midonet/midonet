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

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

import java.util.{Objects, UUID}

import org.midonet.cluster.data.{Zoom, ZoomField, ZoomObject}
import org.midonet.cluster.util.IPAddressUtil.{Converter => IPAddressConverter}
import org.midonet.midolman.rules._
import org.midonet.midolman.state.l4lb.LBStatus
import org.midonet.packets.IPv4Addr
import org.midonet.util.collection.HasWeight

/**
 * @param weight
 *        Pool member's weight. Odds of a pool member being selected
 *        are equal to its weight divided by the sum of the weights of
 *        all of its pool's members. A pool member with zero weight is
 *        considered down.
 */
final class PoolMember @Zoom()(@ZoomField(name = "id")
                               val id: UUID,
                               @ZoomField(name = "admin_state_up")
                               val adminStateUp: Boolean,
                               @ZoomField(name = "status")
                               val status: LBStatus,
                               @ZoomField(name = "address",
                                          converter = classOf[IPAddressConverter])
                               val address: IPv4Addr,
                               @ZoomField(name = "protocol_port")
                               val protocolPort: Int,
                               @ZoomField(name = "weight")
                               val weight: Int)
    extends ZoomObject with HasWeight {

    private val natTargets = Array(new NatTarget(address, address,
                                                 protocolPort, protocolPort))

    protected[simulation] def applyDnat(pktContext: PacketContext,
                                        stickySourceIP: Boolean): Unit =
        if (stickySourceIP)
            pktContext.applyStickyDnat(natTargets)
        else
            pktContext.applyDnat(natTargets)

    def isUp: Boolean = weight > 0 && adminStateUp && status == LBStatus.ACTIVE

    override def toString =
        s"PoolMember [id=$id adminStateUp=$adminStateUp status=$status " +
        s"address=$address protocolPort=$protocolPort weight=$weight]"

    override def equals(obj: Any) = obj match {
        case member: PoolMember =>
            id == member.id && adminStateUp == member.adminStateUp &&
            status == member.status && address == member.address &&
            protocolPort == member.protocolPort && weight == member.weight
        case _ => false
    }

    override def hashCode =
        Objects.hash(id, Boolean.box(adminStateUp), status, address,
                     Int.box(protocolPort), Int.box(weight))

}

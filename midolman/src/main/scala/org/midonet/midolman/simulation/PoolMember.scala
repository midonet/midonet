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

import org.midonet.cluster.data.ZoomConvert.ScalaZoomField
import org.midonet.cluster.data.ZoomObject
import org.midonet.cluster.util.UUIDUtil.{Converter => UUIDConverter}
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
class PoolMember(@ScalaZoomField(name = "id",
                                 converter = classOf[UUIDConverter])
                 val id: UUID,
                 @ScalaZoomField(name = "admin_state_up")
                 val adminStateUp: Boolean,
                 @ScalaZoomField(name = "status")
                 val status: LBStatus,
                 @ScalaZoomField(name = "address",
                                 converter = classOf[IPAddressConverter])
                 val address: IPv4Addr,
                 @ScalaZoomField(name = "port")
                 val protocolPort: Int,
                 @ScalaZoomField(name = "weight")
                 val weight: Int)
    extends ZoomObject with HasWeight {

    def this() = this(null, false, LBStatus.INACTIVE, null, 0, 0)

    private val natTargets = Array(new NatTarget(address, address,
                                                 protocolPort, protocolPort))

    protected[simulation] def applyDnat(pktContext: PacketContext,
                                        loadBalancer: UUID,
                                        stickySourceIP: Boolean): Unit =
        if (stickySourceIP)
            pktContext.applyStickyDnat(loadBalancer, natTargets)
        else
            pktContext.applyDnat(loadBalancer, natTargets)

    def isUp: Boolean = weight > 0 && adminStateUp && status == LBStatus.ACTIVE

    override def toString =
        s"PoolMember [id=$id adminStateUp=$adminStateUp status=$status " +
        s"address=$address protocolPort=$protocolPort weight=$weight]"
}

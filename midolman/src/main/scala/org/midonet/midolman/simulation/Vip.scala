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

import java.util.{Objects, UUID}

import org.midonet.cluster.data.{Zoom, ZoomClass, ZoomField, ZoomObject}
import org.midonet.cluster.models.Topology
import org.midonet.cluster.util.IPAddressUtil.{Converter => IPAddressConverter}
import org.midonet.midolman.state.l4lb.VipSessionPersistence
import org.midonet.packets.{IPAddr, TCP}

@ZoomClass(clazz = classOf[Topology.Vip])
final class Vip @Zoom() (
    @ZoomField(name = "id")
    val id: UUID,
    @ZoomField(name = "admin_state_up")
    val adminStateUp: Boolean,
    @ZoomField(name = "pool_id")
    val poolId: UUID,
    @ZoomField(name = "address", converter = classOf[IPAddressConverter])
    val address: IPAddr,
    @ZoomField(name = "protocol_port")
    val protocolPort: Int,
    @ZoomField(name = "session_persistence")
    val sessionPersistence: VipSessionPersistence) extends ZoomObject {

    override def equals(obj: Any): Boolean = obj match {
        case vip: Vip =>
            vip.id == id && vip.adminStateUp == adminStateUp &&
            vip.poolId == poolId && vip.address == address &&
            vip.protocolPort == protocolPort &&
            vip.sessionPersistence == sessionPersistence
        case _ => false
    }

    override def hashCode: Int =
        Objects.hash(id, poolId, address, sessionPersistence)

    override def toString =
        s"VIP [id=$id adminStateUp=$adminStateUp poolId=$poolId " +
        s"address=$address port=$protocolPort " +
        s"sessionPersistence=$sessionPersistence]"

    def isStickySourceIP = sessionPersistence == VipSessionPersistence.SOURCE_IP

    def matches(pktContext: PacketContext) = {
        val pktMatch = pktContext.wcmatch

        adminStateUp && pktMatch.getNetworkDstIP == address &&
            pktMatch.getDstPort == protocolPort &&
            pktMatch.getNetworkProto == TCP.PROTOCOL_NUMBER
    }

    def matchesReturn(pktContext: PacketContext) = {
        val pktMatch = pktContext.wcmatch

        adminStateUp && pktMatch.getNetworkSrcIP == address &&
                pktMatch.getSrcPort == protocolPort &&
                pktMatch.getNetworkProto == TCP.PROTOCOL_NUMBER
    }
}

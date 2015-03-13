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

import org.midonet.cluster.data.{ZoomClass, ZoomField, ZoomObject}
import org.midonet.cluster.models.Topology
import org.midonet.cluster.util.IPAddressUtil.{Converter => IPAddressConverter}
import org.midonet.cluster.util.UUIDUtil.{Converter => UUIDConverter}
import org.midonet.packets.{IPv4Addr, TCP}

@ZoomClass(clazz = classOf[Topology.VIP])
class VIP extends ZoomObject {
    @ZoomField(name = "id", converter = classOf[UUIDConverter])
    var id: UUID = _
    @ZoomField(name = "admin_state_up")
    var adminStateUp: Boolean = false
    @ZoomField(name = "pool_id", converter = classOf[UUIDConverter])
    var poolId: UUID = _
    @ZoomField(name = "address", converter = classOf[IPAddressConverter])
    var address: IPv4Addr = _
    @ZoomField(name = "protocol_port")
    var protocolPort: Int = _
    @ZoomField(name = "sticky_source_ip")
    var isStickySourceIP: Boolean = false

    def this(id: UUID, adminStateUp: Boolean, poolId: UUID, address: IPv4Addr,
             protocolPort: Int, isStickySourceIP: Boolean) = {
        this()
        this.id = id
        this.adminStateUp = adminStateUp
        this.poolId = poolId
        this.address = address
        this.protocolPort = protocolPort
        this.isStickySourceIP = isStickySourceIP
    }

    override def equals(obj: Any): Boolean = obj match {
        case vip: VIP =>
            vip.id == id && vip.adminStateUp == adminStateUp &&
            vip.poolId == poolId && vip.address == address &&
            vip.protocolPort == protocolPort &&
            vip.isStickySourceIP == isStickySourceIP
        case _ => false
    }

    override def hashCode: Int =
        Objects.hashCode(id, adminStateUp, poolId, address, protocolPort,
                         isStickySourceIP)

    override def toString =
        s"VIP [id=$id adminStateUp=$adminStateUp poolId=$poolId " +
        s"address=$address protocolPort=$protocolPort " +
        s"isStickySourceIp=$isStickySourceIP]"

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

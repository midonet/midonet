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

package org.midonet.midolman.topology.routing

import java.util.{Objects, UUID}

import org.midonet.cluster.data.ZoomConvert.ScalaZoomField
import org.midonet.cluster.data.{BGP => BGPData, ZoomObject}
import org.midonet.cluster.util.IPAddressUtil.{Converter => IPAddressConverter}
import org.midonet.cluster.util.UUIDUtil.{Converter => UUIDConverter}
import org.midonet.packets.IPAddr

object Bgp {
    /**
     * Creates a [[Bgp]] peering instance, corresponding to the Protocol Buffers
     * data model, from the legacy [[BGPData]] using the ZooKeeper managers
     * serialization. This method will be removed when the legacy models are no
     * longer supported.
     */
    @Deprecated
    def from(data: BGPData): Bgp = {
        new Bgp(data.getId, data.getLocalAS, data.getPeerAS, data.getPeerAddr,
                data.getPortId, null)
    }
}

/** A BGP peering. */
final class Bgp(@ScalaZoomField(name = "id", converter = classOf[UUIDConverter])
                val id: UUID,
                @ScalaZoomField(name = "local_as")
                val localAs: Int,
                @ScalaZoomField(name = "peer_as")
                val peerAs: Int,
                @ScalaZoomField(name = "peer_address",
                                converter = classOf[IPAddressConverter])
                val peerAddress: IPAddr,
                @ScalaZoomField(name = "port_id", converter = classOf[UUIDConverter])
                val portId: UUID,
                @ScalaZoomField(name = "bgp_route_ids",
                                converter = classOf[UUIDConverter])
                val bgpRouteIds: Set[UUID]) extends ZoomObject {

    /** Default constructor. */
    def this() = this(id = null, localAs = 0, peerAs = 0, peerAddress = null,
                      portId = null, bgpRouteIds = null)

    private[midolman] var quaggaPortNumber: Int = _
    private[midolman] var uplinkPid: Int = _

    override def equals(obj: Any): Boolean = obj match {
        case bgp: Bgp =>
            id == bgp.id && localAs == bgp.localAs && peerAs == bgp.peerAs &&
            peerAddress == bgp.peerAddress && portId == bgp.portId
        case _ => false
    }

    override def hashCode: Int = Objects.hash(id, Int.box(localAs),
                                              Int.box(peerAs), peerAddress,
                                              portId)

    override def toString =
        s"BGP [id=$id localAs=$localAs peerAs=$peerAs peerAddress= " +
        s"$peerAddress portId=$portId]"
}

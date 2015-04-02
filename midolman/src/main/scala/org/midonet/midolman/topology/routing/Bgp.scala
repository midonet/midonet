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

import org.midonet.cluster.data.{ZoomField, Zoom, ZoomObject}
import org.midonet.cluster.util.IPAddressUtil.{Converter => IPAddressConverter}
import org.midonet.cluster.util.UUIDUtil.{Converter => UUIDConverter}
import org.midonet.packets.IPAddr

/** A BGP peering. */
final class Bgp @Zoom()(@ZoomField(name = "id",
                                   converter = classOf[UUIDConverter])
                        val id: UUID,
                        @ZoomField(name = "local_as")
                        val localAs: Int,
                        @ZoomField(name = "peer_as")
                        val peerAs: Int,
                        @ZoomField(name = "peer_address",
                                   converter = classOf[IPAddressConverter])
                        val peerAddress: IPAddr,
                        @ZoomField(name = "port_id",
                                   converter = classOf[UUIDConverter])
                        val portId: UUID,
                        @ZoomField(name = "bgp_route_ids",
                                   converter = classOf[UUIDConverter])
                        val bgpRouteIds: Set[UUID]) extends ZoomObject {

    override def equals(obj: Any): Boolean = obj match {
        case bgp: Bgp =>
            id == bgp.id && localAs == bgp.localAs && peerAs == bgp.peerAs &&
            peerAddress == bgp.peerAddress && portId == bgp.portId &&
            bgpRouteIds == bgp.bgpRouteIds
        case _ => false
    }

    override def hashCode: Int = Objects.hash(id, Int.box(localAs),
                                              Int.box(peerAs), peerAddress,
                                              portId, bgpRouteIds)

    override def toString =
        s"Bgp [id=$id localAs=$localAs peerAs=$peerAs peerAddress= " +
        s"$peerAddress portId=$portId, routeIds=$bgpRouteIds]"

}

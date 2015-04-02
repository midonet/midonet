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
import org.midonet.cluster.util.IPSubnetUtil.{Converter => IPSubnetConverter}
import org.midonet.cluster.util.UUIDUtil.{Converter => UUIDConverter}
import org.midonet.packets.IPSubnet

/** A BGP route. */
final class BgpRoute @Zoom()(@ZoomField(name = "id",
                                        converter = classOf[UUIDConverter])
                             val id: UUID,
                             @ZoomField(name = "subnet",
                                        converter = classOf[IPSubnetConverter])
                             val subnet: IPSubnet[_],
                             @ZoomField(name = "bgp_id",
                                        converter = classOf[UUIDConverter])
                             val bgpId: UUID) extends ZoomObject{

    override def equals(obj: Any): Boolean = obj match {
        case route: BgpRoute =>
            id == route.id && subnet == route.subnet && bgpId == route.bgpId
        case _ => false
    }

    override def hashCode: Int = Objects.hash(id, subnet, bgpId)

    override def toString = s"BgpRoute [id=$id subnet=$subnet bgpId=$bgpId]"

}

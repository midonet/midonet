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

package org.midonet.cluster.services.topology.common

import com.google.protobuf.Message

import org.midonet.cluster.models.Topology

/**
 * Common utilities for Topology messages
 */
object TopologyMappings {
    /** Mapping Topology entity types to their container class */
    val typeToKlass: Map[Topology.Type, Class[_ <: Message]] = Map(
        Topology.Type.CHAIN  -> classOf[Topology.Chain],
        Topology.Type.HOST -> classOf[Topology.Host],
        Topology.Type.IP_ADDR_GROUP -> classOf[Topology.IpAddrGroup],
        Topology.Type.NETWORK -> classOf[Topology.Network],
        Topology.Type.PORT -> classOf[Topology.Port],
        Topology.Type.PORT_GROUP -> classOf[Topology.PortGroup],
        Topology.Type.ROUTE -> classOf[Topology.Route],
        Topology.Type.ROUTER -> classOf[Topology.Router],
        Topology.Type.LOAD_BALANCER -> classOf[Topology.LoadBalancer],
        Topology.Type.VIRTUAL_IP -> classOf[Topology.VIP],
        Topology.Type.RULE -> classOf[Topology.Rule],
        Topology.Type.TUNNEL_ZONE -> classOf[Topology.TunnelZone],
        Topology.Type.VTEP -> classOf[Topology.Vtep],
        Topology.Type.VTEP_BINDING -> classOf[Topology.VtepBinding],
        Topology.Type.DHCP -> classOf[Topology.Dhcp]
    )
    /** Retrieve the klass for the given Topology entity type */
    def klassOf(t: Topology.Type) = typeToKlass.get(t)

    /** Retrieve the type corresponding to a given class */
    def typeOf(k: Class[_ <: Message]): Option[Topology.Type] =
        typeToKlass.find(_._2 == k).map(_._1)
}

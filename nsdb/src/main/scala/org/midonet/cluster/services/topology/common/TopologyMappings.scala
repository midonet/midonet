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
        Topology.Type.BGP_NETWORK -> classOf[Topology.BgpNetwork],
        Topology.Type.BGP_PEER -> classOf[Topology.BgpPeer],
        Topology.Type.CHAIN  -> classOf[Topology.Chain],
        Topology.Type.DHCP -> classOf[Topology.Dhcp],
        Topology.Type.DHCP_V6 -> classOf[Topology.DhcpV6],
        Topology.Type.HEALTH_MONITOR -> classOf[Topology.HealthMonitor],
        Topology.Type.HOST -> classOf[Topology.Host],
        Topology.Type.IP_ADDR_GROUP -> classOf[Topology.IPAddrGroup],
        Topology.Type.LOAD_BALANCER -> classOf[Topology.LoadBalancer],
        Topology.Type.L2INSERTION -> classOf[Topology.L2Insertion],
        Topology.Type.MIRROR -> classOf[Topology.Mirror],
        Topology.Type.NETWORK -> classOf[Topology.Network],
        Topology.Type.POOL -> classOf[Topology.Pool],
        Topology.Type.POOL_MEMBER -> classOf[Topology.PoolMember],
        Topology.Type.PORT -> classOf[Topology.Port],
        Topology.Type.PORT_GROUP -> classOf[Topology.PortGroup],
        Topology.Type.ROUTE -> classOf[Topology.Route],
        Topology.Type.ROUTER -> classOf[Topology.Router],
        Topology.Type.RULE -> classOf[Topology.Rule],
        Topology.Type.TUNNEL_ZONE -> classOf[Topology.TunnelZone],
        Topology.Type.TRACE_REQUEST -> classOf[Topology.TraceRequest],
        Topology.Type.VIP -> classOf[Topology.Vip],
        Topology.Type.VTEP -> classOf[Topology.Vtep]
    )
    /** Retrieve the klass for the given Topology entity type */
    def klassOf(t: Topology.Type) = typeToKlass.get(t)

    /** Retrieve the type corresponding to a given class */
    def typeOf(k: Class[_ <: Message]): Option[Topology.Type] =
        typeToKlass.find(_._2 == k).map(_._1)
}

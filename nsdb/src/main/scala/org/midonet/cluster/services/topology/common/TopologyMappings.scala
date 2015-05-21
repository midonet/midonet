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
        Topology.Type.CHAIN_TYPE  -> classOf[Topology.Chain],
        Topology.Type.DHCP_TYPE -> classOf[Topology.Dhcp],
        Topology.Type.HEALTH_MONITOR_TYPE -> classOf[Topology.HealthMonitor],
        Topology.Type.HOST_TYPE -> classOf[Topology.Host],
        Topology.Type.IP_ADDR_GROUP_TYPE -> classOf[Topology.IPAddrGroup],
        Topology.Type.NETWORK_TYPE -> classOf[Topology.Network],
        Topology.Type.POOL_TYPE -> classOf[Topology.Pool],
        Topology.Type.POOL_MEMBER_TYPE -> classOf[Topology.PoolMember],
        Topology.Type.PORT_TYPE -> classOf[Topology.Port],
        Topology.Type.PORT_GROUP_TYPE -> classOf[Topology.PortGroup],
        Topology.Type.ROUTE_TYPE -> classOf[Topology.Route],
        Topology.Type.ROUTER_TYPE -> classOf[Topology.Router],
        Topology.Type.LOAD_BALANCER_TYPE -> classOf[Topology.LoadBalancer],
        Topology.Type.VIP_TYPE -> classOf[Topology.VIP],
        Topology.Type.RULE_TYPE -> classOf[Topology.Rule],
        Topology.Type.TUNNEL_ZONE_TYPE -> classOf[Topology.TunnelZone],
        Topology.Type.VTEP_TYPE -> classOf[Topology.VTEP],
        Topology.Type.VTEP_BINDING_TYPE -> classOf[Topology.VTEPBinding]
    )
    /** Retrieve the klass for the given Topology entity type */
    def klassOf(t: Topology.Type) = typeToKlass.get(t)

    /** Retrieve the type corresponding to a given class */
    def typeOf(k: Class[_ <: Message]): Option[Topology.Type] =
        typeToKlass.find(_._2 == k).map(_._1)
}

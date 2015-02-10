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

package org.midonet.brain.services.c3po.translators

import org.midonet.cluster.data.neutron.MetaDataService
import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.{IPAddress, IPSubnet, UUID}
import org.midonet.cluster.models.Topology.Network.Dhcp.Opt121RouteOrBuilder
import org.midonet.cluster.models.Topology.Route
import org.midonet.cluster.models.Topology.RouteOrBuilder
import org.midonet.cluster.models.Topology.Route.NextHop
import org.midonet.cluster.util.{IPSubnetUtil, UUIDUtil}

trait RouteManager {
    val storage: ReadOnlyStorage
    val META_DATA_SRVC = IPSubnetUtil.toProto(MetaDataService.IPv4_ADDRESS)
    val DEFAULT_WEIGHT = 100

    def createLocalRoute(portId: UUID, portAddr: IPSubnet, routerId: UUID)
    : Route = {
        Route.newBuilder()
             .setDstSubnet(portAddr)
             .setNextHop(NextHop.LOCAL)
             .setNextHopPortId(portId)
             .setRouterId(routerId).build()
    }

    def createMetaDataServiceRoute(srcSubnet: IPSubnet, nextHopPortId: UUID,
                                   nextHopGw: IPAddress, routerId: UUID)
    : Route = {
        Route.newBuilder
             .setId(UUIDUtil.randomUuidProto)
             .setSrcSubnet(srcSubnet)
             .setDstSubnet(META_DATA_SRVC)
             .setNextHop(NextHop.PORT)
             .setNextHopPortId(nextHopPortId)
             .setNextHopGateway(nextHopGw)
             .setWeight(DEFAULT_WEIGHT)
             .setRouterId(routerId)
             .build
    }

    /**
     * Tests if the route is to Meta Data Server.
     * @param nextHopGw A next hop gateway to Meta Data Server.
     */
    def isMetaDataSvrRoute(route: RouteOrBuilder, nextHopGw: IPAddress) =
        route.getDstSubnet == META_DATA_SRVC &&
        route.getNextHopGateway == nextHopGw

    /**
     * Tests if the route is an Opt 121 route to Meta Data Server.
     * @param nextHopGw A next hop gateway to Meta Data Server.
     */
    def isMetaDataSvrOpt121Route(route: Opt121RouteOrBuilder,
                                 nextHopGw: IPAddress) =
        route.getDstSubnet == META_DATA_SRVC && route.getGateway == nextHopGw
}

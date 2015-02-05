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

import org.midonet.brain.services.c3po.midonet.Create
import org.midonet.cluster.data.neutron.MetaDataService
import org.midonet.cluster.models.Commons.{IPAddress, IPSubnet, UUID}
import org.midonet.cluster.models.Topology.Dhcp.Opt121RouteOrBuilder
import org.midonet.cluster.models.Topology.Route.NextHop
import org.midonet.cluster.models.Topology.{PortOrBuilder, Route, RouteOrBuilder}
import org.midonet.cluster.util.IPSubnetUtil.univSubnet4
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
import org.midonet.cluster.util.{IPSubnetUtil, UUIDUtil}


trait RouteManager {
    import RouteManager._
    /**
     * Tests if the route is to Meta Data Server.
     * @param nextHopGw A next hop gateway to Meta Data Server.
     */
    protected def isMetaDataSvrRoute(route: RouteOrBuilder,
                                     nextHopGw: IPAddress): Boolean =
        route.getDstSubnet == META_DATA_SRVC &&
        route.getNextHopGateway == nextHopGw

    /**
     * Tests if the route is an Opt 121 route to Meta Data Server.
     * @param nextHopGw A next hop gateway to Meta Data Server.
     */
    protected def isMetaDataSvrOpt121Route(route: Opt121RouteOrBuilder,
                                           nextHopGw: IPAddress): Boolean =
        route.getDstSubnet == META_DATA_SRVC && route.getGateway == nextHopGw

    protected def newRouteBuilder(routerId: UUID): Route.Builder =
        Route.newBuilder()
            .setId(UUIDUtil.randomUuidProto)
            .setRouterId(routerId)

    protected def newLocalRoute(portId: UUID, portAddr: IPAddress)
    : Route = {
        Route.newBuilder
             .setId(RouteManager.localRouteId(portId))
             .setSrcSubnet(IPSubnetUtil.univSubnet4)
             .setDstSubnet(IPSubnetUtil.toProto(portAddr))
             .setNextHop(NextHop.LOCAL)
             .setWeight(DEFAULT_WEIGHT)
             .setNextHopPortId(portId).build()
    }

    def createMetaDataServiceRoute(srcSubnet: IPSubnet, nextHopPortId: UUID,
                                   nextHopGw: IPAddress, routerId: UUID)
    : Route = {
        newRouteBuilder(routerId)
             .setSrcSubnet(srcSubnet)
             .setDstSubnet(META_DATA_SRVC)
             .setNextHop(NextHop.PORT)
             .setNextHopPortId(nextHopPortId)
             .setNextHopGateway(nextHopGw)
             .setWeight(DEFAULT_WEIGHT)
             .build
    }

    protected def newNextHopPortRoute(nextHopPortId: UUID,
                                      id: UUID = null,
                                      nextHopGwIpAddr: IPAddress = null,
                                      srcSubnet: IPSubnet = univSubnet4,
                                      dstSubnet: IPSubnet = univSubnet4,
                                      weight: Int = DEFAULT_WEIGHT): Route = {
        val bldr = Route.newBuilder
        bldr.setId(if (id != null) id else UUIDUtil.randomUuidProto)
        bldr.setNextHop(NextHop.PORT)
        bldr.setNextHopPortId(nextHopPortId)
        bldr.setSrcSubnet(srcSubnet)
        bldr.setDstSubnet(dstSubnet)
        bldr.setWeight(weight)
        if (nextHopGwIpAddr != null) bldr.setNextHopGateway(nextHopGwIpAddr)
        bldr.build()
    }

    protected def newDefaultRoute(nextHopPortId: UUID,
                                  id: UUID = null,
                                  weight: Int = DEFAULT_WEIGHT): Route = {
        newNextHopPortRoute(nextHopPortId, id = id, weight = weight)
    }

    protected def addLocalRouteToRouter(rPort: PortOrBuilder): MidoOpList = {
        List(Create(newLocalRoute(rPort.getId,
                                  rPort.getPortAddress)))
    }
}

object RouteManager {
    val META_DATA_SRVC = IPSubnetUtil.toProto(MetaDataService.IPv4_ADDRESS)
    val DEFAULT_WEIGHT = 100

    /**
     * Tests whether route is to a port, i.e., next hop is PORT or LOCAL
     * rather than REJECT or BLACKHOLE
     */
    protected def isToPort(route: RouteOrBuilder): Boolean =
        route.getNextHop == NextHop.LOCAL || route.getNextHop == NextHop.PORT

    /** ID of local route to port, based on port's ID. */
    def localRouteId(portId: UUID) =
        portId.xorWith(0x13bd079b6c0e43fbL, 0x80fe647e6e718b72L)

    /** ID of next-hop route from tenant router to provider router via
      * gateway port, or vice-versa.
      */
    def gatewayRouteId(gwPortId: UUID) =
        gwPortId.xorWith(0x6ba5df84b8a44ab4L, 0x90adb3f665e7850dL)
}
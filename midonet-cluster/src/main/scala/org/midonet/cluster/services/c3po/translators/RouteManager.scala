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

package org.midonet.cluster.services.c3po.translators

import scala.collection.JavaConverters._

import org.midonet.cluster.models.Commons.{IPAddress, IPSubnet, IPVersion, UUID}
import org.midonet.cluster.models.Neutron.NeutronRoute
import org.midonet.cluster.models.Topology.Dhcp.Opt121RouteOrBuilder
import org.midonet.cluster.models.Topology.Route.NextHop
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.rest_api.neutron.models.MetaDataService
import org.midonet.cluster.util.IPSubnetUtil.AnyIPv4Subnet
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil, UUIDUtil}
import org.midonet.packets.{IPv4Addr, IPv6Addr}

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
             .setSrcSubnet(IPSubnetUtil.AnyIPv4Subnet)
             .setDstSubnet(IPSubnetUtil.fromAddress(portAddr))
             .setNextHop(NextHop.LOCAL)
             .setWeight(DEFAULT_WEIGHT)
             .setNextHopPortId(portId).build()
    }

    def newMetaDataServiceRoute(srcSubnet: IPSubnet, nextHopPortId: UUID,
                                nextHopGw: IPAddress)
    : Route = {
        newNextHopPortRoute(nextHopPortId,
                            id = metadataServiceRouteId(nextHopPortId),
                            nextHopGwIpAddr = nextHopGw,
                            srcSubnet = srcSubnet,
                            dstSubnet = META_DATA_SRVC)
    }

    protected def newNextHopPortRoute(nextHopPortId: UUID,
                                      id: UUID = null,
                                      nextHopGwIpAddr: IPAddress = null,
                                      srcSubnet: IPSubnet = AnyIPv4Subnet,
                                      dstSubnet: IPSubnet = AnyIPv4Subnet,
                                      gatewayDhcpId: UUID = null,
                                      weight: Int = DEFAULT_WEIGHT,
                                      ipSecSiteCnxnId: UUID = null): Route = {
        val bldr = Route.newBuilder
        bldr.setId(if (id != null) id else UUIDUtil.randomUuidProto)
        bldr.setNextHop(NextHop.PORT)
        bldr.setNextHopPortId(nextHopPortId)
        bldr.setSrcSubnet(srcSubnet)
        bldr.setDstSubnet(dstSubnet)
        bldr.setWeight(weight)
        if (gatewayDhcpId != null) bldr.setGatewayDhcpId(gatewayDhcpId)
        if (nextHopGwIpAddr != null) bldr.setNextHopGateway(nextHopGwIpAddr)
        if (ipSecSiteCnxnId != null) bldr.setIpsecSiteConnectionId(ipSecSiteCnxnId)
        bldr.build()
    }

    /**
     * Checks whether the given IP address can be added with the given port as
     * the next hop.
     *
     * The following checks are performed:
     *    * Next hop cannot match IP on the port.
     *    * Next hop must be in the CIDR that the port is connected to.
     */
    protected def isValidRouteOnPort(address: IPAddress,
                                     port: Port): Boolean = {
        if (port.getPortAddress == address)
            return false
        val a = IPAddressUtil.toIPAddr(address)
        for (subnet <- port.getPortSubnetList.asScala) {
            val s = IPSubnetUtil.fromProto(subnet)
            if (s.containsAddress(a) && s.getAddress != a) {
                return true
            } else {
                System.out.println("Red: " + s.toString +
                                   " does no contain address: " + a.toString)
            }
        }
        false
    }
}

/**
 * Provides utility methods for creating and modifying a Topology.Route object.
 * Defines a series of deterministic ID generator methods such as localRouterId,
 * and gatewayRouteId.
 *
 * The deterministic ID generators derive a new ID for a "subordinate" object
 * (e.g. local Route ID for a given port) by XORing the UUID of an "owner"
 * object, in this case the port, with another pre-generated random UUID,
 * hard-coded as two long integers in each method body. This saves us at least 1
 * ZK round trip, say, when you want to look up a local route for a port but you
 * only have a port ID. This helps us in house-keeping all the subordinate
 * objects when modifying an owner object but cannot use field binding, or saves
 * us from searching through the topology graph to find an object in question.
 */
object RouteManager {
    val META_DATA_SRVC = IPSubnetUtil.toProto(MetaDataService.IPv4_ADDRESS)
    val DEFAULT_WEIGHT = 100

    /**
     * Tests whether the route is to a port, i.e., next hop is PORT or LOCAL
     * rather than REJECT or BLACKHOLE */
    protected def isToPort(route: RouteOrBuilder): Boolean =
        route.getNextHop == NextHop.LOCAL || route.getNextHop == NextHop.PORT

    /**
     * Deterministically derives an ID for a local route to the port using the
     * port ID. */
    def localRouteId(portId: UUID): UUID =
        portId.xorWith(0x13bd079b6c0e43fbL, 0x80fe647e6e718b72L)

    /**
     * Deterministically derives an ID for a next-hop route from tenant router
     * to provider router via gateway port, or vice-versa, using the gateway
     * port ID. */
    def gatewayRouteId(gwPortId: UUID): UUID =
        gwPortId.xorWith(0x6ba5df84b8a44ab4L, 0x90adb3f665e7850dL)

    /**
     * Deterministically derives an ID for a network route on a router, or
     * vice-versa, using the next hop port ID. */
    def networkRouteId(portId: UUID): UUID =
        portId.xorWith(0xcabc4841093b4e81L, 0xb794dac757bcd523L)

    /**
     * Deterministically derives an ID for a next-hop route to the subnet of the
     * router interface port using the router interface port ID. */
    def routerInterfaceRouteId(rifPortId: UUID): UUID =
        rifPortId.xorWith(0xb288abe0c5744762L, 0xb3a04b12442bb179L)

    /**
     * Deterministically derives an ID for a Metadata Service route using the
     * DHCP port ID. */
    def metadataServiceRouteId(dhcpPortId: UUID): UUID =
        dhcpPortId.xorWith(0xa0132e5a1583461cL, 0xa752d8609a517a6cL)

    /**
     * Deterministically derives an ID for the SNAT rule for a Floating IP
     * address. */
    def fipSnatRuleId(fipId: UUID): UUID =
        fipId.xorWith(0xf515a8fd119a4b82L, 0x81e9b793d68a3b9eL)
    def fipSnatExactRuleId(fipId: UUID): UUID =
        fipId.xorWith(0xfaba87a8b7a850acL, 0x52c748d4a902f277L)

    /**
     * Deterministically derives an ID for the DNAT rule for a Floating IP
     * address. */
    def fipDnatRuleId(fipId: UUID): UUID =
        fipId.xorWith(0xe40c77c188694ac0L, 0x9a8e6c1863e2232eL)

    def fipReverseDnatRuleId(fipId: UUID): UUID =
        fipId.xorWith(0x9a8e6c1863e2232eL, 0xe40c77c188694ac0L)

    def fipSkipSnatRuleId(fipId: UUID): UUID =
        fipId.xorWith(0x30eddc1fede9b578L, 0x1df21b732ecf45afL)

    // Deterministically generate the extra route IDs based on the router ID
    // and the route attributes.
    def extraRouteId(routerId:UUID, route: NeutronRoute): UUID = {
        val prefixLen = route.getDestination.getPrefixLength
        if (route.getDestination.getVersion == IPVersion.V6) {
            val dst = IPv6Addr(route.getDestination.getAddress)
            val nextHop = IPAddressUtil.toIPv6Addr(route.getNexthop)
            routerId.xorWith(dst.upperWord ^ nextHop.upperWord,
                             dst.lowerWord ^ nextHop.lowerWord ^ prefixLen)
        } else {
            val destIp = IPv4Addr.stringToInt(route.getDestination.getAddress)

            routerId.xorWith(
                destIp.asInstanceOf[Long] << 32 | prefixLen,
                IPv4Addr.stringToInt(route.getNexthop.getAddress))
        }
    }
}

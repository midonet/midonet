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

package org.midonet.cluster.services.c3po.translators

import scala.collection.JavaConverters._
import scala.collection.mutable
import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.{IPAddress, IPSubnet, UUID}
import org.midonet.cluster.models.Neutron.{NeutronNetwork, NeutronPort, NeutronRoute, NeutronSubnet}
import org.midonet.cluster.models.Topology.Dhcp.Opt121Route
import org.midonet.cluster.models.Topology.{Dhcp, Network}
import org.midonet.cluster.rest_api.neutron.models.DeviceOwner
import org.midonet.cluster.services.c3po.midonet.{Create, Delete, Update}
import org.midonet.cluster.services.c3po.neutron
import org.midonet.cluster.util.DhcpUtil.asRichNeutronSubnet
import org.midonet.cluster.util.IPSubnetUtil
import org.midonet.util.concurrent.toFutureOps

// TODO: add code to handle connection to provider router.
class SubnetTranslator(val storage: ReadOnlyStorage)
    extends NeutronTranslator[NeutronSubnet] with RouteManager {

    override protected def translateCreate(ns: NeutronSubnet): MidoOpList = {
        // Uplink networks don't exist in Midonet, nor do their subnets.
        if (isOnUplinkNetwork(ns)) return List()

        if (ns.isIpv6)
            throw new TranslationException(  // Doesn't handle IPv6 yet.
                    neutron.Create(ns), msg = "Cannot handle an IPv6 Subnet.")

        val dhcp = Dhcp.newBuilder
                       .setId(ns.getId)
                       .setNetworkId(ns.getNetworkId)
        if (ns.hasGatewayIp) {
            dhcp.setDefaultGateway(ns.getGatewayIp)
            dhcp.setServerAddress(ns.getGatewayIp)
        }
        if (ns.hasCidr)
            dhcp.setSubnetAddress(IPSubnetUtil.toProto(ns.getCidr))
        if (ns.hasEnableDhcp)
            dhcp.setEnabled(ns.getEnableDhcp)

        for (addr <- ns.getDnsNameserversList.asScala)
            dhcp.addDnsServerAddress(addr)

        addHostRoutes(dhcp, ns.getHostRoutesList.asScala)

        List(Create(dhcp.build))
    }

    override protected def translateDelete(id: UUID): MidoOpList = {
        List(Delete(classOf[Dhcp], id))
    }

    override protected def translateUpdate(ns: NeutronSubnet): MidoOpList = {
        // Uplink networks don't exist in Midonet, nor do their subnets.
        if (isOnUplinkNetwork(ns)) return List()

        if (ns.isIpv6)
            throw new TranslationException(  // Doesn't handle IPv6 yet.
                    neutron.Update(ns), msg = "Cannot handle an IPv6 Subnet.")

        val origDhcp = storage.get(classOf[Dhcp], ns.getId).await()
        val newDhcp = origDhcp.toBuilder
            .setDefaultGateway(ns.getGatewayIp)
            .setEnabled(ns.getEnableDhcp)
            .setSubnetAddress(IPSubnetUtil.toProto(ns.getCidr))
            .clearDnsServerAddress()
            .clearOpt121Routes()

        for (addr <- ns.getDnsNameserversList.asScala)
            newDhcp.addDnsServerAddress(addr)

        addHostRoutes(newDhcp, ns.getHostRoutesList.asScala)

        getDhcpPortIp(ns.getNetworkId).foreach({ip =>
            newDhcp.addOpt121Routes(
                opt121FromHostRoute(RouteManager.META_DATA_SRVC, ip))})

        // TODO: connect to provider router if external
        List(Update(newDhcp.build))
    }

    private def addHostRoutes(dhcp: Dhcp.Builder,
                              hostRoutes: mutable.Buffer[NeutronRoute]) {
        for (hostRoute <- hostRoutes) {
            dhcp.addOpt121RoutesBuilder().setDstSubnet(hostRoute.getDestination)
                                         .setGateway(hostRoute.getNexthop)
        }
    }

    private def opt121FromHostRoute(dest: IPSubnet, nexthop: IPAddress)
        : Opt121Route.Builder = {
        Opt121Route.newBuilder().setDstSubnet(dest).setGateway(nexthop)
    }

    private def getDhcpPortIp(networkId: UUID): Option[IPAddress] = {

        val network = storage.get(classOf[Network], networkId).await()
        val ports = network.getPortIdsList

        // Find the dhcp port associated with this subnet, if it exists.
        for (portId <- ports.asScala) {
            val port = storage.get(classOf[NeutronPort], portId).await()
            if (port.getDeviceOwner.name() == DeviceOwner.DHCP.name) {
                if (port.getFixedIpsCount == 0) {
                    return None
                }
                return Some(port.getFixedIps(0).getIpAddress)
            }
        }

        None
    }

    private def isOnUplinkNetwork(ns: NeutronSubnet): Boolean = {
        val nn = storage.get(classOf[NeutronNetwork], ns.getNetworkId).await()
        NetworkTranslator.isUplinkNetwork(nn)
    }
}

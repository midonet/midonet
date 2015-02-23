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

package org.midonet.brain.services.c3po.translators

import org.midonet.cluster.data.neutron.{MetaDataService, DeviceOwner}
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Topology.Dhcp.Opt121Route

import scala.collection.JavaConverters._

import org.midonet.brain.services.c3po.midonet.{Create, Delete, Update}
import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.{NeutronPort, NeutronSubnet}
import org.midonet.cluster.models.Topology.{Dhcp, Port}
import org.midonet.cluster.util.{UUIDUtil, IPAddressUtil, IPSubnetUtil}
import org.midonet.util.concurrent.toFutureOps

// TODO: add code to handle connection to provider router.
class SubnetTranslator(storage: ReadOnlyStorage)
    extends NeutronTranslator[NeutronSubnet] {

    override protected def translateCreate(ns: NeutronSubnet): MidoOpList = {

        val dhcp = Dhcp.newBuilder
            .setId(ns.getId)
            .setNetworkId(ns.getNetworkId)
            .setDefaultGateway(ns.getGatewayIp)
            .setServerAddress(ns.getGatewayIp)
            .setEnabled(ns.getEnableDhcp)
            .setSubnetAddress(IPSubnetUtil.toProto(ns.getCidr))

        for (addr <- ns.getDnsNameserversList.asScala)
            dhcp.addDnsServerAddress(IPAddressUtil.toProto(addr))

        for (hostRoute <- ns.getHostRoutesList.asScala) {
            val opt121Route = Opt121Route.newBuilder()
            opt121Route.setDstSubnet(IPSubnetUtil.toProto(hostRoute.getDestination))
            opt121Route.setGateway(IPAddressUtil.toProto(hostRoute.getNexthop))
            dhcp.addOpt121Routes(opt121Route)
        }

        // TODO: connect to provider router if external
        List(Create(dhcp.build))
    }

    override protected def translateDelete(id: UUID): MidoOpList = {
        List(Delete(classOf[Dhcp], id))
    }

    override protected def translateUpdate(ns: NeutronSubnet): MidoOpList = {

        val origDhcp = storage.get(classOf[Dhcp], ns.getId).await()
        val newDhcp = origDhcp.toBuilder
            .setDefaultGateway(ns.getGatewayIp)
            .setEnabled(ns.getEnableDhcp)
            .setSubnetAddress(IPSubnetUtil.toProto(ns.getCidr))
            .clearDnsServerAddress()
            .clearOpt121Routes()

        for (addr <- ns.getDnsNameserversList.asScala)
            newDhcp.addDnsServerAddress(IPAddressUtil.toProto(addr))

        for (hostRoute <- ns.getHostRoutesList.asScala) {
            val opt121Route = Opt121Route.newBuilder()
            opt121Route.setDstSubnet(IPSubnetUtil.toProto(hostRoute.getDestination))
            opt121Route.setGateway(IPAddressUtil.toProto(hostRoute.getNexthop))
            newDhcp.addOpt121Routes(opt121Route)
        }

        val dhcpIp = getDhcpPortIp(ns.getNetworkId)
        if (dhcpIp != null) {
            val metaDataRoute = Opt121Route.newBuilder()
            metaDataRoute.setGateway(IPAddressUtil.toProto(dhcpIp.getAddress))
            metaDataRoute.setDstSubnet(IPSubnetUtil.toProto(MetaDataService.IPv4_SUBNET))
            newDhcp.addOpt121Routes(metaDataRoute)
        }

        // TODO: connect to provider router if external
        List(Update(newDhcp.build))
    }

    private def getDhcpPortIp(networkId: UUID): Commons.IPAddress = {

        val ports = storage.getAll(classOf[NeutronPort]).await()
        // Find the dhcp port associated with this subnet, if it exists.
        for (port <- ports) {
            val p = port.await()
            if (p.getNetworkId == networkId &&
                p.getDeviceOwner.name() == DeviceOwner.DHCP.name) {
                if (p.getFixedIpsCount == 0) {
                    return null
                }
                return p.getFixedIps(0).getIpAddress
            }
        }

        null
    }
}

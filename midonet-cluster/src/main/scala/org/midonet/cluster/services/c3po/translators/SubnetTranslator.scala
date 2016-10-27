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
import scala.collection.mutable

import org.midonet.cluster.data.storage.{NotFoundException, Transaction}
import org.midonet.cluster.models.Commons.{IPAddress, IPSubnet, UUID}
import org.midonet.cluster.models.Neutron.NeutronPort.DeviceOwner
import org.midonet.cluster.models.Neutron.{NeutronNetwork, NeutronPort, NeutronRoute, NeutronSubnet}
import org.midonet.cluster.models.Topology.Dhcp.Opt121Route
import org.midonet.cluster.models.Topology.{Dhcp, Network, Route}
import org.midonet.cluster.util.DhcpUtil.asRichNeutronSubnet

// TODO: add code to handle connection to provider router.
class SubnetTranslator extends Translator[NeutronSubnet] with RouteManager {

    override protected def translateCreate(tx: Transaction,
                                           ns: NeutronSubnet): Unit = {
        // Uplink networks don't exist in Midonet, nor do their subnets.
        if (isOnUplinkNetwork(tx, ns)) {
            return
        }

        val dhcp = Dhcp.newBuilder
                       .setId(ns.getId)
                       .setNetworkId(ns.getNetworkId)
        if (ns.hasGatewayIp) {
            dhcp.setDefaultGateway(ns.getGatewayIp)
            dhcp.setServerAddress(ns.getGatewayIp)
        }
        if (ns.hasCidr)
            dhcp.setSubnetAddress(ns.getCidr)
        if (ns.hasEnableDhcp)
            dhcp.setEnabled(ns.getEnableDhcp)

        for (addr <- ns.getDnsNameserversList.asScala)
            dhcp.addDnsServerAddress(addr)

        addHostRoutes(dhcp, ns.getHostRoutesList.asScala)

        val net = tx.get(classOf[NeutronNetwork], ns.getNetworkId)
        val updatedNet = net.toBuilder.addSubnets(ns.getId)

        tx.create(dhcp.build())
        tx.update(updatedNet.build())
    }

    override protected def translateDelete(tx: Transaction,
                                           ns: NeutronSubnet): Unit = {
        val net = tx.get(classOf[NeutronNetwork], ns.getNetworkId)
        val subIdx = net.getSubnetsList.indexOf(ns.getId)
        if (subIdx >= 0)
            tx.update(net.toBuilder.removeSubnets(subIdx).build())
        tx.delete(classOf[Dhcp], ns.getId, ignoresNeo = true)
    }

    override protected def translateUpdate(tx: Transaction,
                                           ns: NeutronSubnet): Unit = {
        // Uplink networks don't exist in Midonet, nor do their subnets.
        if (isOnUplinkNetwork(tx, ns)) {
            return
        }

        if (ns.isIpv6)
            throw new IllegalArgumentException(
                "IPv6 Subnets are not supported in this version of Midonet.")

        val oldDhcp = tx.get(classOf[Dhcp], ns.getId)
        val newDhcp = oldDhcp.toBuilder
            .setEnabled(ns.getEnableDhcp)
            .setSubnetAddress(ns.getCidr)
            .clearDnsServerAddress()
            .clearOpt121Routes()

        if (!ns.hasGatewayIp) newDhcp.clearDefaultGateway()
        else newDhcp.setDefaultGateway(ns.getGatewayIp)

        for (addr <- ns.getDnsNameserversList.asScala)
            newDhcp.addDnsServerAddress(addr)

        addHostRoutes(newDhcp, ns.getHostRoutesList.asScala)

        getDhcpPortIp(tx, ns.getNetworkId).foreach({ ip =>
            newDhcp.addOpt121Routes(
                opt121FromHostRoute(RouteManager.META_DATA_SRVC, ip))
        })

        tx.update(newDhcp.build())
        updateRouteNextHopIps(
            tx,
            if (oldDhcp.hasDefaultGateway) oldDhcp.getDefaultGateway else null,
            if (newDhcp.hasDefaultGateway) newDhcp.getDefaultGateway else null,
            oldDhcp.getGatewayRouteIdsList.asScala)

        // TODO: connect to provider router if external
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

    private def getDhcpPortIp(tx: Transaction,
                              networkId: UUID): Option[IPAddress] = {

        val network = tx.get(classOf[Network], networkId)
        val ports = network.getPortIdsList

        // Find the dhcp port associated with this subnet, if it exists.
        for (portId <- ports.asScala) {
            val port = try {
                tx.get(classOf[NeutronPort], portId)
            } catch {
                case nfe: NotFoundException => null
            }
            if (port != null && port.getDeviceOwner == DeviceOwner.DHCP) {
                if (port.getFixedIpsCount == 0) {
                    return None
                }
                return Some(port.getFixedIps(0).getIpAddress)
            }
        }

        None
    }

    private def isOnUplinkNetwork(tx: Transaction, ns: NeutronSubnet): Boolean = {
        val nn = tx.get(classOf[NeutronNetwork], ns.getNetworkId)
        NetworkTranslator.isUplinkNetwork(nn)
    }

    /**
     * Update the next hop gateway of any routes using this subnet as a gateway.
     */
    private def updateRouteNextHopIps(tx: Transaction, oldGwIp: IPAddress,
                                      newGwIp: IPAddress, routeIds: Seq[UUID])
    : Unit = {
        if (oldGwIp == newGwIp) {
            return
        }
        for (route <- tx.getAll(classOf[Route], routeIds)) {
            val builder = route.toBuilder
            if (newGwIp == null) builder.clearNextHopGateway()
            else builder.setNextHopGateway(newGwIp)
            tx.update(builder.build())
        }
    }
}

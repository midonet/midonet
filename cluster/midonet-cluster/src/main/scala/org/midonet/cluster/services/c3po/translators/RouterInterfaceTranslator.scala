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

import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.{IPSubnet, UUID}
import org.midonet.cluster.models.Neutron.{NeutronPort, NeutronRouterInterface, NeutronSubnet}
import org.midonet.cluster.models.Topology.{Network, Route}
import org.midonet.cluster.services.c3po.midonet.{Create, MidoOp}
import org.midonet.cluster.services.c3po.translators.PortManager.{isDhcpPort, routerInterfacePortPeerId}
import org.midonet.cluster.util.IPSubnetUtil._
import org.midonet.cluster.util.UUIDUtil.fromProto
import org.midonet.cluster.util.{IPSubnetUtil, UUIDUtil}
import org.midonet.util.concurrent.toFutureOps

class RouterInterfaceTranslator(val storage: ReadOnlyStorage)
    extends NeutronTranslator[NeutronRouterInterface] with PortManager {

    override protected def translateCreate(ri : NeutronRouterInterface)
    : MidoOpList = {
        // At this point, we will already have translated the task to create
        // the NeutronPort with id nm.getPortId.
        val nPort = storage.get(classOf[NeutronPort], ri.getPortId).await()

        // A NeutronRouterInterface is a link between a Neutron router and a
        // Neutron network, so we will need to create a Midonet port on the
        // router with ID nPort.getDeviceId. If nPort is on an uplink network,
        // then there is no corresponding Midonet network, and the router is
        // called an edge router.
        val isEdgeRouter = isOnUplinkNetwork(nPort)

        // The router ID is given as nPort's device ID. The deviceId property is
        // a String because it's not always just a UUID (e.g. for DHCP ports
        // it's a composite of host and network IDs. But for router interface
        // ports it should always be a well-formed UUID.
        val routerId = UUIDUtil.toProto(nPort.getDeviceId)

        // ID of the router port.
        val routerPortId = routerInterfacePortPeerId(nPort.getId)

        val routerPortBldr = newRouterPortBldr(routerPortId, routerId)

        // For non-edge routers, we connect the router port to the network port,
        // which has the same ID as nPort.
        if (!isEdgeRouter)
            routerPortBldr.setPeerId(nPort.getId)

        val ns = storage.get(classOf[NeutronSubnet], ri.getSubnetId).await()
        val portSubnet = IPSubnetUtil.toProto(ns.getCidr)
        routerPortBldr.setPortSubnet(portSubnet)

        // Set the router port address. The port should have at most one IP
        // address. If it has none, use the subnet's default gateway.
        if (nPort.getFixedIpsCount > 0) {
            if (nPort.getFixedIpsCount > 1)
                log.error("More than 1 fixed IP assigned to a Neutron Port " +
                          s"${fromProto(nPort.getId)}")
            routerPortBldr.setPortAddress(nPort.getFixedIps(0).getIpAddress)
        } else {
            routerPortBldr.setPortAddress(ns.getGatewayIp)
        }

        // Add a route to the Interface subnet.
        val routerInterfaceRouteId =
            RouteManager.routerInterfaceRouteId(routerPortId)
        val rifRoute = newNextHopPortRoute(nextHopPortId = routerPortId,
                                           id = routerInterfaceRouteId,
                                           nextHopGwIpAddr = null,
                                           srcSubnet = univSubnet4,
                                           dstSubnet = portSubnet)

        val midoOps = new MidoOpListBuffer
        val routerPort = routerPortBldr.build()
        midoOps += Create(routerPort)
        midoOps += Create(rifRoute)

        if (isEdgeRouter) {
            midoOps ++= bindPortOps(routerPort, nPort.getHostId,
                                    nPort.getProfile.getInterfaceName)
        } else {
            midoOps ++= createMetadataServiceRoutes(
                routerPortId, nPort.getNetworkId, portSubnet)
        }

        midoOps.toList
    }

    private def createMetadataServiceRoutes(routerPortId: UUID,
                                            networkId: UUID,
                                            subnetAddr: IPSubnet)
    : Option[MidoOp[Route]] = {
        // If a DHCP port exists, add a Meta Data Service Route. This requires
        // fetching all ports from ZK. We await the futures in parallel to
        // reduce latency, but this may still become a problem when a Network
        // has many ports. Consider giving the Network a specific reference to
        // its DHCP Port.
        val mNetwork = storage.get(classOf[Network], networkId).await()
        val portIds = mNetwork.getPortIdsList.asScala
        val portFutures = portIds.map(storage.get(classOf[NeutronPort], _))
        val ports = portFutures.map(_.await())
        val dhcpPortOpt = ports.find(
            port => isDhcpPort(port) && port.getFixedIpsCount > 0)
        dhcpPortOpt.map(p => Create(newMetaDataServiceRoute(
            subnetAddr, routerPortId, p.getFixedIps(0).getIpAddress)))
    }

    override protected def translateDelete(id: UUID): MidoOpList = {
        // The id field of a router interface is the router ID. Since a router
        // can have multiple interfaces, this doesn't uniquely identify it.
        // We need to handle router interface deletion when we delete the peer
        // port on the network, so there's nothing to do here.
        List()
    }

    override protected def translateUpdate(nm: NeutronRouterInterface)
    : MidoOpList = {
        throw new IllegalArgumentException(
            "NeutronRouterInterface update not supported.")
    }
}

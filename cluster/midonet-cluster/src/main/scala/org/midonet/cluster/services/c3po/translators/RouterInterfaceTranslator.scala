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
import org.midonet.cluster.models.Neutron.NeutronPort.DeviceOwner
import org.midonet.cluster.models.Neutron.{NeutronPort, NeutronRouterInterface, NeutronSubnet}
import org.midonet.cluster.models.Topology.{Network, Route}
import org.midonet.cluster.services.c3po.midonet.{Create, Update, MidoOp}
import org.midonet.cluster.services.c3po.neutron.NeutronOp
import org.midonet.cluster.services.c3po.translators.PortManager.{isDhcpPort, routerInterfacePortPeerId}
import org.midonet.cluster.util.IPSubnetUtil._
import org.midonet.cluster.util.UUIDUtil.fromProto
import org.midonet.util.concurrent.toFutureOps

class RouterInterfaceTranslator(val storage: ReadOnlyStorage)
    extends NeutronTranslator[NeutronRouterInterface] with PortManager {

    /* NeutronRouterInterface is a binding information and has no unique ID.
     * We don't persist it in Storage. */
    override protected def retainNeutronModel(
            op: NeutronOp[NeutronRouterInterface]) = List()

    override protected def translateCreate(ri : NeutronRouterInterface)
    : MidoOpList = {
        // At this point, we will already have translated the task to create
        // the NeutronPort with id nm.getPortId.
        val nPort = storage.get(classOf[NeutronPort], ri.getPortId).await()

        // A NeutronRouterInterface is a link between a Neutron router and a
        // Neutron network, so we will need to create a Midonet port on the
        // router with ID nPort.getDeviceId. If nPort is on an uplink network,
        // then there is no corresponding Midonet network, and the router port
        // is bound to a host interface.
        val isUplink = isOnUplinkNetwork(nPort)

        // The router id is given as the router interface id
        val routerId = ri.getId

        // ID of the router port.
        val routerPortId = routerInterfacePortPeerId(nPort.getId)

        val routerPortBldr = newRouterPortBldr(routerPortId, routerId)

        if (isUplink) {
            // The port will be bound to a host rather than connected to a
            // network port. Add it to the edge router's port group.
            routerPortBldr.addPortGroupIds(PortManager.portGroupId(routerId))
        } else {
            // Connect the router port to the network port, which has the same
            // ID as nPort. Also add a reference to the DHCP. Zoom will add a
            // backreference from the DHCP to the edge router port, allowing us
            // to find it easily when creating a tenant router gateway.
            routerPortBldr.setDhcpId(ri.getSubnetId)
                          .setPeerId(nPort.getId)
        }

        val ns = storage.get(classOf[NeutronSubnet], ri.getSubnetId).await()
        val portSubnet = ns.getCidr
        routerPortBldr.setPortSubnet(portSubnet)

        // Set the router port address. The port should have at most one IP
        // address. If it has none, use the subnet's default gateway.
        val gatewayIp = if (nPort.getFixedIpsCount > 0) {
            if (nPort.getFixedIpsCount > 1)
                log.error("More than 1 fixed IP assigned to a Neutron Port " +
                          s"${fromProto(nPort.getId)}")
            nPort.getFixedIps(0).getIpAddress
        } else ns.getGatewayIp
        routerPortBldr.setPortAddress(gatewayIp)
        routerPortBldr.setPortMac(nPort.getMacAddress)

        // Add a route to the Interface subnet.
        val routerInterfaceRouteId =
            RouteManager.routerInterfaceRouteId(routerPortId)
        val rifRoute = newNextHopPortRoute(nextHopPortId = routerPortId,
                                           id = routerInterfaceRouteId,
                                           srcSubnet = univSubnet4,
                                           dstSubnet = portSubnet)
        val localRoute = newLocalRoute(routerPortId, gatewayIp)

        val midoOps = new MidoOpListBuffer
        val routerPort = routerPortBldr.build()
        midoOps += Create(routerPort)
        midoOps += Create(rifRoute)
        midoOps += Create(localRoute)

        if (isUplink) {
            midoOps ++= bindPortOps(routerPort,
                                    getHostIdByName(nPort.getHostId),
                                    nPort.getProfile.getInterfaceName)
        } else {
            midoOps ++= createMetadataServiceRoute(
                routerPortId, nPort.getNetworkId, portSubnet)
            midoOps ++= updateGatewayRoutesOps(gatewayIp, ns.getId)
        }

        // Convert Neutron Port to router interface port if it isn't already.
        if (nPort.getDeviceOwner != DeviceOwner.ROUTER_INTERFACE) {
            midoOps += Update(nPort.toBuilder
                                  .setDeviceOwner(DeviceOwner.ROUTER_INTERFACE)
                                  .build())
        }

        midoOps.toList
    }

    private def createMetadataServiceRoute(routerPortId: UUID,
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
        val ports = storage.getAll(classOf[NeutronPort], portIds).await()
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

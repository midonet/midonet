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

import org.midonet.cluster.models.Commons.{IPAddress, IPSubnet, IPVersion, UUID}
import org.midonet.cluster.models.Neutron.NeutronPort.DeviceOwner
import org.midonet.cluster.models.Neutron.{NeutronNetwork, NeutronPort, NeutronPortOrBuilder}
import org.midonet.cluster.models.Topology.{Port, PortOrBuilder}
import org.midonet.cluster.services.c3po.midonet.Update
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
import org.midonet.packets.MAC
import org.midonet.util.concurrent.toFutureOps

/**
 * Contains port-related operations shared by multiple translator classes.
 */
trait PortManager extends RouteManager {
    import org.midonet.cluster.services.c3po.translators.PortManager._

    protected def newTenantRouterGWPort(id: UUID,
                                        routerId: UUID,
                                        gwIpAddr: IPAddress,
                                        mac: Option[String] = None,
                                        adminStateUp: Boolean = true): Port = {
        newRouterPortBldr(id, routerId, adminStateUp = adminStateUp)
            .setPortSubnet(LL_CIDR)
            .setPortAddress(gwIpAddr)
            .setPortMac(mac.getOrElse(MAC.random().toString)).build()
    }

    private def checkNoPeerId(port: PortOrBuilder): Unit = {
        if (port.hasPeerId)
            throw new IllegalStateException(
                s"Port ${port.getId} already connected to ${port.getPeerId}.")
    }

    protected def newRouterPortBldr(id: UUID, routerId: UUID,
                                    adminStateUp: Boolean = true)
    : Port.Builder = Port.newBuilder.setId(id)
                                    .setRouterId(routerId)
                                    .setAdminStateUp(adminStateUp)

    /**
     * Modifies port to set its peerId to peer's ID, and returns list of
     * operations for creating appropriate routes on the ports, as well as an
     * operation to set port's peerId.
     *
     * Does not set peer's peerId. Storage engine is assumed to handle this.
     */
    protected def linkPortOps(port: Port, peer: PortOrBuilder)
    : MidoOpList = {
        checkNoPeerId(port)
        checkNoPeerId(peer)

        val ops = new MidoOpListBuffer

        val portBldr = Port.newBuilder(port)
        portBldr.setPeerId(peer.getId)
        ops += Update(portBldr.build(), PortUpdateValidator)

        // For router ports,
        if (port.hasRouterId)
            ops ++= addLocalRouteToRouter(port)

        if (peer.hasRouterId)
            ops ++= addLocalRouteToRouter(peer)

        ops.toList
    }

    /**
     * Returns operations to bind the specified port to the specified host
     * and interface. Port and host must exist in Midonet topology, and neither
     * the port nor the interface may already be bound on the specified host.
     */
    protected def bindPortOps(port: Port, hostId: UUID, ifName: String)
    : MidoOpList = {
        if (port.hasHostId) throw new IllegalStateException(
            s"Port ${port.getId} is already bound.")

        // Temporarily find the host by searching through the list

        val updatedPort = port.toBuilder
            .setHostId(hostId)
            .setInterfaceName(ifName)
        List(Update(updatedPort.build(), PortUpdateValidator))
    }

    protected def isOnUplinkNetwork(np: NeutronPort) = {
        NetworkTranslator.isUplinkNetwork(
            storage.get(classOf[NeutronNetwork], np.getNetworkId).await())
    }
}

object PortManager {
    def isVifPort(nPort: NeutronPortOrBuilder) =
        !nPort.hasDeviceOwner || nPort.getDeviceOwner == DeviceOwner.NOVA
    def isDhcpPort(nPort: NeutronPortOrBuilder) =
        nPort.hasDeviceOwner && nPort.getDeviceOwner == DeviceOwner.DHCP
    def isFloatingIpPort(nPort: NeutronPortOrBuilder) =
        nPort.hasDeviceOwner && nPort.getDeviceOwner == DeviceOwner.FLOATINGIP
    def isRouterInterfacePort(nPort: NeutronPortOrBuilder) =
        nPort.hasDeviceOwner && nPort.getDeviceOwner == DeviceOwner.ROUTER_INTERFACE
    def isRouterGatewayPort(nPort: NeutronPortOrBuilder) =
        nPort.hasDeviceOwner && nPort.getDeviceOwner == DeviceOwner.ROUTER_GATEWAY


    /** Link-local CIDR */
    val LL_CIDR = IPSubnet.newBuilder()
                  .setAddress("169.254.255.0")
                  .setPrefixLength(30)
                  .setVersion(IPVersion.V4).build()

    /** Link-local gateway IP */
    val LL_GW_IP_1 = IPAddress.newBuilder()
                     .setAddress("169.254.255.1")
                     .setVersion(IPVersion.V4).build()

    /** ID of Router Interface port peer. */
    def routerInterfacePortPeerId(portId: UUID): UUID =
        portId.xorWith(0x9c30300ec91f4f19L, 0x88449d37e61b60f0L)

    /** ID of port group for specified device. Currently only edge routers have
      * port groups. */
    def portGroupId(deviceId: UUID): UUID =
        deviceId.xorWith(0x3fb30e769f5041f1L, 0xa50c3c4fb09a6a18L)
}

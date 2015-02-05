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

import org.midonet.brain.services.c3po.midonet.Update
import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.{IPAddress, IPSubnet, IPVersion, UUID}
import org.midonet.cluster.models.Neutron.NeutronPort.DeviceOwner
import org.midonet.cluster.models.Neutron.NeutronPortOrBuilder
import org.midonet.cluster.models.Topology.{Port, PortOrBuilder, Router}
import org.midonet.packets.MAC
import org.midonet.util.concurrent.toFutureOps

/**
 * Contains port-related operations shared by multiple translator classes.
 */
trait PortManager extends RouteManager {
    import org.midonet.brain.services.c3po.translators.PortManager._

    protected val storage: ReadOnlyStorage

    protected def newTenantRouterGWPort(routerId: UUID,
                                        gwIpAddr: IPAddress,
                                        id: UUID,
                                        mac: Option[String] = None,
                                        adminStateUp: Boolean = true)
    : Port.Builder = {
        Port.newBuilder()
            .setId(id)
            .setRouterId(routerId)
            .setPortSubnet(LL_CIDR)
            .setPortAddress(gwIpAddr)
            .setPortMac(mac.getOrElse(MAC.random().toString))
            .setAdminStateUp(adminStateUp)
    }

    protected def newProviderRouterGwPort(id: UUID): Port.Builder = {
        Port.newBuilder()
            .setId(id)
            .setRouterId(RouterTranslator.providerRouterId)
            .setPortSubnet(LL_CIDR)
            .setPortAddress(LL_GW_IP_1)
            .setAdminStateUp(true)
    }

    private def checkNoPeerId(port: PortOrBuilder): Unit = {
        if (port.hasPeerId)
            throw new IllegalStateException(
                s"Port ${port.getId} already connected to ${port.getPeerId}.")
    }

    /**
     * Modifies port to set its peerId to peer's ID, and returns list of
     * operations for creating appropriate routes on the ports and their
     * routers.
     *
     * Does not set peer ID's peer. Storage engine is assumed to handle this.
     *
     * Does not create an operation to update the port.
     */
    protected def linkPorts(port: Port.Builder, portExists: Boolean,
                            peer: PortOrBuilder, peerExists: Boolean)
    : MidoOpList = {
        checkNoPeerId(port)
        checkNoPeerId(peer)

        val ops = new MidoOpListBuffer

        port.setPeerId(peer.getId)

        // For router ports,
        if (port.hasRouterId) {
            // TODO: Restore or delete this.
//            if (portExists) ops ++= addPortRoutesToRouter(port)
//            else ops ++= addLocalRouteToRouter(port)
            if (!portExists) ops ++= addLocalRouteToRouter(port)
        }
        if (peer.hasRouterId) {
            // TODO: Restore or delete this.
//            if (peerExists) ops ++= addPortRoutesToRouter(peer)
//            else ops ++= addLocalRouteToRouter(peer)
            if (!peerExists) ops ++= addLocalRouteToRouter(peer)
        }
        ops.toList
    }

    private def addPortRoutesToRouter(port: PortOrBuilder): MidoOpList = {
        val router = storage.get(classOf[Router], port.getRouterId).await()
        router.toBuilder.addAllRouteIds(port.getRouteIdsList)
        List(Update(router))
    }
}

object PortManager {
    def isVifPort(nPort: NeutronPortOrBuilder) = !nPort.hasDeviceOwner
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
}

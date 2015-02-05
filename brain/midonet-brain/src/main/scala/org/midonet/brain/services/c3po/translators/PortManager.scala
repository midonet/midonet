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

import org.midonet.cluster.models.Commons.{IPAddress, UUID, IPVersion, IPSubnet}
import org.midonet.cluster.models.Neutron.NeutronPort
import org.midonet.cluster.models.Neutron.NeutronPort.DeviceOwner
import org.midonet.cluster.models.Topology.Port
import org.midonet.cluster.util.UUIDUtil
import org.midonet.packets.MAC

/**
 * Contains port-related operations shared by multiple translator classes.
 */
trait PortManager {

    protected def isVifPort(nPort: NeutronPort) = !nPort.hasDeviceOwner
    protected def isDhcpPort(nPort: NeutronPort) =
        nPort.hasDeviceOwner && nPort.getDeviceOwner == DeviceOwner.DHCP
    protected def isFloatingIpPort(nPort: NeutronPort) =
        nPort.hasDeviceOwner && nPort.getDeviceOwner == DeviceOwner.FLOATINGIP
    protected def isRouterInterfacePort(nPort: NeutronPort) =
        nPort.hasDeviceOwner && nPort.getDeviceOwner == DeviceOwner.ROUTER_INTERFACE
    protected def isRouterGatewayPort(nPort: NeutronPort) =
        nPort.hasDeviceOwner && nPort.getDeviceOwner == DeviceOwner.ROUTER_GATEWAY

    /** Link-local CIDR */
    val LL_CIDR = IPSubnet.newBuilder()
                  .setAddress("169.254.255.0")
                  .setPrefixLength(30)
                  .setVersion(IPVersion.V4).build()

    def newRouterGwPort(routerId: UUID,
                        gwIpAddr: IPAddress,
                        id: Option[UUID] = None,
                        mac: Option[String] = None,
                        adminStateUp: Boolean = true): Port = {
        Port.newBuilder()
            .setId(id.getOrElse(UUIDUtil.randomUuidProto))
            .setRouterId(routerId)
            .setPortSubnet(LL_CIDR)
            .setPortAddress(gwIpAddr)
            .setPortMac(mac.getOrElse(MAC.random().toString))
            .setAdminStateUp(adminStateUp)
            .build()
    }
}

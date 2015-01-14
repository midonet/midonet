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

import scala.collection.JavaConverters._

import com.google.protobuf.Message

import org.midonet.brain.services.c3po.midonet.{Create, Delete, MidoOp, Update}
import org.midonet.brain.services.c3po.neutron
import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.{IPSubnet, IPVersion, UUID}
import org.midonet.cluster.models.Neutron.NeutronPort
import org.midonet.cluster.models.Neutron.NeutronPort.DeviceOwner
import org.midonet.cluster.models.Neutron.{NeutronRouter => Router}
import org.midonet.cluster.models.Neutron.NeutronSubnet
import org.midonet.cluster.models.Topology.{Chain, Network, Port}
import org.midonet.cluster.util.{IPSubnetUtil, UUIDUtil}
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
import org.midonet.util.concurrent.toFutureOps

object PortTranslator {
    private def isVifPort(nPort: NeutronPort) = !nPort.hasDeviceOwner
    private def isDhcpPort(nPort: NeutronPort) =
        nPort.hasDeviceOwner && nPort.getDeviceOwner == DeviceOwner.DHCP
    private def isFloatingIpPort(nPort: NeutronPort) =
        nPort.hasDeviceOwner && nPort.getDeviceOwner == DeviceOwner.FLOATINGIP
    private def isRouterInterfacePort(nPort: NeutronPort) =
        nPort.hasDeviceOwner && nPort.getDeviceOwner == DeviceOwner.ROUTER_INTF
    private def isRouterGatewayPort(nPort: NeutronPort) =
        nPort.hasDeviceOwner && nPort.getDeviceOwner == DeviceOwner.ROUTER_GW

    private def isIpv4(nSubnet: NeutronSubnet) = nSubnet.getIpVersion == 4
    private def isIpv6(nSubnet: NeutronSubnet) = nSubnet.getIpVersion == 6
}

class PortTranslator(private val storage: ReadOnlyStorage)
        extends NeutronTranslator[NeutronPort] {
    import org.midonet.brain.services.c3po.translators.PortTranslator._

    override protected def translateCreate(nPort: NeutronPort)
    : List[MidoOp[_ <: Message]] = {
        var midoOps: List[MidoOp[_ <: Message]] = List()
        if (isRouterGatewayPort(nPort)) {
            // TODO Create a router port and set the provider router ID.
        } else if (!isFloatingIpPort(nPort)) {
            // For all other port types except floating IP port, create a normal
            // bridge (network) port.
            val port = Port.newBuilder.setId(nPort.getId)
                           .setNetworkId(nPort.getNetworkId)
                           .setAdminStateUp(nPort.getAdminStateUp)
                           .build
            midoOps = Create(port) :: midoOps
        }

        midoOps
    }

    override protected def translateDelete(id: UUID)
    : List[MidoOp[_ <: Message]] = {
        val nPort = storage.get(classOf[NeutronPort], id).await()
        if (!isFloatingIpPort(nPort))
            List(Delete(classOf[Port], id))
        else
            List()
    }

    override protected def translateUpdate(nPort: NeutronPort)
    : List[MidoOp[_ <: Message]] = {
        if (isVifPort(nPort) || isDhcpPort(nPort)) {
            val port = Port.newBuilder.setId(nPort.getId)
                           .setNetworkId(nPort.getNetworkId)
                           .setAdminStateUp(nPort.getAdminStateUp)
                           .build
            List(Update(port))
        } else {
            List()
        } 
    }
}

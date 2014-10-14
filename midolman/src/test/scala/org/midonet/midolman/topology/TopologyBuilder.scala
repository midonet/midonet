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
package org.midonet.midolman.topology

import java.util.UUID

import scala.concurrent.duration._
import scala.util.Random

import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.models.Topology.Port
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.cluster.util.IPSubnetUtil._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.packets._
import org.midonet.util.concurrent.FutureOps._

trait TopologyBuilder {

    import TopologyBuilder._

    protected def createNetworkPort(id: UUID = UUID.randomUUID,
                                    networkId: UUID = UUID.randomUUID,
                                    inboundFilterId: UUID = UUID.randomUUID,
                                    outboundFilterId: UUID = UUID.randomUUID,
                                    tunnelKey: Long = -1L,
                                    peerId: UUID = UUID.randomUUID,
                                    vifId: UUID = UUID.randomUUID,
                                    hostId: UUID = UUID.randomUUID,
                                    interfaceName: String = "",
                                    adminStateUp: Boolean = true,
                                    portGroupIds: Set[UUID] = Set.empty,
                                    vlanId: Int = 0): Port = {
        createPortBuilder(id, networkId, inboundFilterId,
                                        outboundFilterId, tunnelKey, peerId,
                                        vifId, hostId, interfaceName,
                                        adminStateUp, portGroupIds)
            .setVlanId(vlanId)
            .build
    }

    protected def createRouterPort(id: UUID = UUID.randomUUID,
                                   networkId: UUID = UUID.randomUUID,
                                   inboundFilterId: UUID = UUID.randomUUID,
                                   outboundFilterId: UUID = UUID.randomUUID,
                                   tunnelKey: Long = -1L,
                                   peerId: UUID = UUID.randomUUID,
                                   vifId: UUID = UUID.randomUUID,
                                   hostId: UUID = UUID.randomUUID,
                                   interfaceName: String = "",
                                   adminStateUp: Boolean = true,
                                   portGroupIds: Set[UUID] = Set.empty,
                                   portSubnet: IPSubnet[_] = randomIPv4Subnet,
                                   portAddress: IPAddr = IPv4Addr.random,
                                   portMac: MAC = MAC.random): Port = {
        createPortBuilder(id, networkId, inboundFilterId,
                                        outboundFilterId, tunnelKey, peerId,
                                        vifId, hostId, interfaceName,
                                        adminStateUp, portGroupIds)
            .setPortSubnet(portSubnet.asProto)
            .setPortAddress(portAddress.asProto)
            .setPortMac(portMac.toString)
            .build
    }

    protected def createVxLanPort(id: UUID = UUID.randomUUID,
                                  networkId: UUID = UUID.randomUUID,
                                  inboundFilterId: UUID = UUID.randomUUID,
                                  outboundFilterId: UUID = UUID.randomUUID,
                                  tunnelKey: Long = -1L,
                                  peerId: UUID = UUID.randomUUID,
                                  vifId: UUID = UUID.randomUUID,
                                  hostId: UUID = UUID.randomUUID,
                                  interfaceName: String = "",
                                  adminStateUp: Boolean = true,
                                  portGroupIds: Set[UUID] = Set.empty,
                                  vxLanMgmtIp: IPAddr = IPv4Addr.random,
                                  vxLanMgmtPort: Int = random.nextInt(),
                                  vxLanVni: Int = random.nextInt(),
                                  vxLanTunnelIp: IPAddr = IPv4Addr.random,
                                  vxLanTunnelZoneId: UUID = UUID.randomUUID): Port = {
        createPortBuilder(id, networkId, inboundFilterId,
                                        outboundFilterId, tunnelKey, peerId,
                                        vifId, hostId, interfaceName,
                                        adminStateUp, portGroupIds)
            .setVxlanMgmtIp(vxLanMgmtIp.asProto)
            .setVxlanMgmtPort(vxLanMgmtPort)
            .setVxlanVni(vxLanVni)
            .setVxlanTunnelIp(vxLanTunnelIp.asProto)
            .setVxlanTunnelZoneId(vxLanTunnelZoneId.asProto)
            .build
    }

    private def createPortBuilder(id: UUID,
                                  networkId: UUID,
                                  inboundFilterId: UUID,
                                  outboundFilterId: UUID,
                                  tunnelKey: Long,
                                  peerId: UUID,
                                  vifId: UUID,
                                  hostId: UUID,
                                  interfaceName: String,
                                  adminStateUp: Boolean,
                                  portGroupIds: Set[UUID]): Port.Builder = {
        val builder = Port.newBuilder
            .setId(id.asProto)
            .setNetworkId(networkId.asProto)
            .setInboundFilterId(inboundFilterId.asProto)
            .setOutboundFilterId(outboundFilterId.asProto)
            .setTunnelKey(tunnelKey)
            .setPeerId(peerId.asProto)
            .setVifId(vifId.asProto)
            .setHostId(hostId.asProto)
            .setInterfaceName(interfaceName)
            .setAdminStateUp(adminStateUp)
        portGroupIds foreach { id => builder.addPortGroupIds(id.asProto) }
        builder
    }

}

object TopologyBuilder {

    private val random = new Random()

    def randomIPv4Subnet = new IPv4Subnet(random.nextInt(), random.nextInt(32))
}

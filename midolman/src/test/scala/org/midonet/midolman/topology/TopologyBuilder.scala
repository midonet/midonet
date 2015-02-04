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
package org.midonet.midolman.topology

import java.util.UUID

import scala.collection.JavaConverters._
import scala.util.Random

import org.midonet.cluster.models.Topology.Host.PortToInterface
import org.midonet.cluster.models.Topology.TunnelZone.HostToIp
import org.midonet.cluster.models.Topology.{Network, Host, Port, TunnelZone}
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.cluster.util.IPSubnetUtil._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.packets._

trait TopologyBuilder {

    import org.midonet.midolman.topology.TopologyBuilder._

    protected def createBridgePort(id: UUID = UUID.randomUUID,
                                   bridgeId: UUID = UUID.randomUUID,
                                   inboundFilterId: UUID = UUID.randomUUID,
                                   outboundFilterId: UUID = UUID.randomUUID,
                                   tunnelKey: Long = -1L,
                                   peerId: UUID = UUID.randomUUID,
                                   vifId: UUID = UUID.randomUUID,
                                   hostId: UUID = UUID.randomUUID,
                                   interfaceName: String = "iface",
                                   adminStateUp: Boolean = true,
                                   portGroupIds: Set[UUID] = Set.empty,
                                   vlanId: Int = 0): Port = {
        createPortBuilder(id, inboundFilterId, outboundFilterId, tunnelKey,
                          peerId, vifId, hostId, interfaceName, adminStateUp,
                          portGroupIds)
            .setNetworkId(bridgeId.asProto)
            .setVlanId(vlanId)
            .build
    }

    protected def createRouterPort(id: UUID = UUID.randomUUID,
                                   routerId: UUID = UUID.randomUUID,
                                   inboundFilterId: UUID = UUID.randomUUID,
                                   outboundFilterId: UUID = UUID.randomUUID,
                                   tunnelKey: Long = -1L,
                                   peerId: UUID = UUID.randomUUID,
                                   vifId: UUID = UUID.randomUUID,
                                   hostId: UUID = UUID.randomUUID,
                                   interfaceName: String = "iface",
                                   adminStateUp: Boolean = true,
                                   portGroupIds: Set[UUID] = Set.empty,
                                   portSubnet: IPSubnet[_] = randomIPv4Subnet,
                                   portAddress: IPAddr = IPv4Addr.random,
                                   portMac: MAC = MAC.random): Port = {
        createPortBuilder(id, inboundFilterId, outboundFilterId, tunnelKey,
                          peerId, vifId, hostId, interfaceName, adminStateUp,
                          portGroupIds)
            .setRouterId(routerId.asProto)
            .setPortSubnet(portSubnet.asProto)
            .setPortAddress(portAddress.asProto)
            .setPortMac(portMac.toString)
            .build
    }

    protected def createVxLanPort(id: UUID = UUID.randomUUID,
                                  bridgeId: UUID = UUID.randomUUID,
                                  inboundFilterId: UUID = UUID.randomUUID,
                                  outboundFilterId: UUID = UUID.randomUUID,
                                  tunnelKey: Long = -1L,
                                  peerId: UUID = UUID.randomUUID,
                                  vifId: UUID = UUID.randomUUID,
                                  hostId: UUID = UUID.randomUUID,
                                  interfaceName: String = "iface",
                                  adminStateUp: Boolean = true,
                                  portGroupIds: Set[UUID] = Set.empty,
                                  vtepMgmtIp: IPAddr = IPv4Addr.random,
                                  vtepMgmtPort: Int = random.nextInt(),
                                  vtepVni: Int = random.nextInt(),
                                  vtepTunnelIp: IPAddr = IPv4Addr.random,
                                  vtepTunnelZoneId: UUID = UUID.randomUUID)
    : Port = {
        createPortBuilder(id, inboundFilterId, outboundFilterId, tunnelKey,
                          peerId, vifId, hostId, interfaceName, adminStateUp,
                          portGroupIds)
            .setNetworkId(bridgeId.asProto)
            .setVtepMgmtIp(vtepMgmtIp.asProto)
            .setVtepMgmtPort(vtepMgmtPort)
            .setVtepVni(vtepVni)
            .setVtepTunnelIp(vtepTunnelIp.asProto)
            .setVtepTunnelZoneId(vtepTunnelZoneId.asProto)
            .build
    }


    protected def createTunnelZone(id: UUID = UUID.randomUUID,
                                   name: String = "tunnel-zone",
                                   hosts: Map[UUID, IPAddr] = Map.empty)
    : TunnelZone = {
        TunnelZone.newBuilder
            .setId(id.asProto)
            .setName(name)
            .addAllHosts(hosts.map(e => HostToIp.newBuilder
            .setHostId(e._1.asProto).setIp(e._2.asProto).build()).asJava)
            .build()
    }

    protected def createHost(id: UUID = UUID.randomUUID,
                             portInterfaceMapping: Map[UUID, String] = Map.empty,
                             tunnelZoneIds: Set[UUID] = Set.empty): Host = {
        Host.newBuilder
            .setId(id.asProto)
            .addAllPortInterfaceMapping(
                portInterfaceMapping.map(e => PortToInterface.newBuilder
                    .setPortId(e._1.asProto).setInterfaceName(e._2).build())
                    .asJava)
            .addAllTunnelZoneIds(tunnelZoneIds.map(_.asProto).asJava)
            .build()
    }

    protected def createBridge(id: UUID = UUID.randomUUID,
                               tenantId: String = "tenant",
                               name: String = "bridge",
                               adminStateUp: Boolean = false,
                               tunnelKey: Long = -1L,
                               inboundFilterId: Option[UUID] = None,
                               outboundFilterId: Option[UUID] = None,
                               portIds: Set[UUID] = Set.empty,
                               vxlanPortIds: Set[UUID] = Set.empty): Network = {
        val builder = Network.newBuilder
            .setId(id.asProto)
            .setTenantId(tenantId)
            .setName(name)
            .setAdminStateUp(adminStateUp)
            .setTunnelKey(tunnelKey)
            .addAllPortIds(portIds.map(_.asProto).asJava)
            .addAllVxlanPortIds(vxlanPortIds.map(_.asProto).asJava)
        if (inboundFilterId.isDefined)
            builder.setInboundFilterId(inboundFilterId.get.asProto)
        if (outboundFilterId.isDefined)
            builder.setOutboundFilterId(outboundFilterId.get.asProto)
        builder.build()
    }

    private def createPortBuilder(id: UUID,
                                  inboundFilterId: UUID,
                                  outboundFilterId: UUID,
                                  tunnelKey: Long,
                                  peerId: UUID,
                                  vifId: UUID,
                                  hostId: UUID,
                                  interfaceName: String,
                                  adminStateUp: Boolean,
                                  portGroupIds: Set[UUID]): Port.Builder = {
        Port.newBuilder
            .setId(id.asProto)
            .setInboundFilterId(inboundFilterId.asProto)
            .setOutboundFilterId(outboundFilterId.asProto)
            .setTunnelKey(tunnelKey)
            .setPeerId(peerId.asProto)
            .setVifId(vifId.asProto)
            .setHostId(hostId.asProto)
            .setInterfaceName(interfaceName)
            .setAdminStateUp(adminStateUp)
            .addAllPortGroupIds(portGroupIds.map(_.asProto).asJava)
    }

}

object TopologyBuilder {

    private val random = new Random()

    def randomIPv4Subnet = new IPv4Subnet(random.nextInt(), random.nextInt(32))

}

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
                                   bridgeId: Option[UUID] = None,
                                   inboundFilterId: Option[UUID] = None,
                                   outboundFilterId: Option[UUID] = None,
                                   tunnelKey: Long = -1L,
                                   peerId: Option[UUID] = None,
                                   vifId: Option[UUID] = None,
                                   hostId: Option[UUID] = None,
                                   interfaceName: Option[String] = None,
                                   adminStateUp: Boolean = false,
                                   portGroupIds: Set[UUID] = Set.empty,
                                   vlanId: Option[Int] = None): Port = {
        val builder = createPortBuilder(
            id, inboundFilterId, outboundFilterId, tunnelKey, peerId, vifId,
            hostId, interfaceName, adminStateUp, portGroupIds)
        if (bridgeId.isDefined) builder.setNetworkId(bridgeId.get.asProto)
        if (vlanId.isDefined) builder.setVlanId(vlanId.get)
        builder.build
    }

    protected def createRouterPort(id: UUID = UUID.randomUUID,
                                   routerId: Option[UUID] = None,
                                   inboundFilterId: Option[UUID] = None,
                                   outboundFilterId: Option[UUID] = None,
                                   tunnelKey: Long = -1L,
                                   peerId: Option[UUID] = None,
                                   vifId: Option[UUID] = None,
                                   hostId: Option[UUID] = None,
                                   interfaceName: Option[String] = None,
                                   adminStateUp: Boolean = false,
                                   portGroupIds: Set[UUID] = Set.empty,
                                   portSubnet: IPSubnet[_] = randomIPv4Subnet,
                                   portAddress: IPAddr = IPv4Addr.random,
                                   portMac: MAC = MAC.random): Port = {
        val builder = createPortBuilder(
            id, inboundFilterId, outboundFilterId, tunnelKey, peerId, vifId,
            hostId, interfaceName, adminStateUp, portGroupIds)
            .setPortSubnet(portSubnet.asProto)
            .setPortAddress(portAddress.asProto)
            .setPortMac(portMac.toString)
        if (routerId.isDefined) builder.setRouterId(routerId.get.asProto)
        builder.build
    }

    protected def createVxLanPort(id: UUID = UUID.randomUUID,
                                  bridgeId: Option[UUID] = None,
                                  inboundFilterId: Option[UUID] = None,
                                  outboundFilterId: Option[UUID] = None,
                                  tunnelKey: Long = -1L,
                                  peerId: Option[UUID] = None,
                                  vifId: Option[UUID] = None,
                                  hostId: Option[UUID] = None,
                                  interfaceName: Option[String] = None,
                                  adminStateUp: Boolean = false,
                                  portGroupIds: Set[UUID] = Set.empty,
                                  vtepMgmtIp: IPAddr = IPv4Addr.random,
                                  vtepMgmtPort: Int = random.nextInt(),
                                  vtepVni: Int = random.nextInt(),
                                  vtepTunnelIp: IPAddr = IPv4Addr.random,
                                  vtepTunnelZoneId: UUID = UUID.randomUUID)
    : Port = {
        val builder = createPortBuilder(
            id, inboundFilterId, outboundFilterId, tunnelKey, peerId, vifId,
            hostId, interfaceName, adminStateUp, portGroupIds)
            .setVtepMgmtIp(vtepMgmtIp.asProto)
            .setVtepMgmtPort(vtepMgmtPort)
            .setVtepVni(vtepVni)
            .setVtepTunnelIp(vtepTunnelIp.asProto)
            .setVtepTunnelZoneId(vtepTunnelZoneId.asProto)
        if (bridgeId.isDefined) builder.setNetworkId(bridgeId.get.asProto)
        builder.build
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
                                  inboundFilterId: Option[UUID],
                                  outboundFilterId: Option[UUID],
                                  tunnelKey: Long,
                                  peerId: Option[UUID],
                                  vifId: Option[UUID],
                                  hostId: Option[UUID],
                                  interfaceName: Option[String],
                                  adminStateUp: Boolean,
                                  portGroupIds: Set[UUID]): Port.Builder = {
        val builder = Port.newBuilder
            .setId(id.asProto)
            .setTunnelKey(tunnelKey)
            .setAdminStateUp(adminStateUp)
            .addAllPortGroupIds(portGroupIds.map(_.asProto).asJava)

        if (inboundFilterId.isDefined)
            builder.setInboundFilterId(inboundFilterId.get.asProto)
        if (outboundFilterId.isDefined)
            builder.setOutboundFilterId(outboundFilterId.get.asProto)
        if (peerId.isDefined)
            builder.setPeerId(peerId.get.asProto)
        if (vifId.isDefined)
            builder.setVifId(vifId.get.asProto)
        if (hostId.isDefined)
            builder.setHostId(hostId.get.asProto)
        if (interfaceName.isDefined)
            builder.setInterfaceName(interfaceName.get)
        builder
    }

}

object TopologyBuilder {

    private val random = new Random()

    def randomIPv4Subnet = new IPv4Subnet(random.nextInt(), random.nextInt(32))

}

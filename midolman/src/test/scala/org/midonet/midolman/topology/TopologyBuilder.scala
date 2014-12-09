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

import scala.collection.mutable
import scala.util.Random

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.Topology.Host.PortToInterface
import org.midonet.cluster.models.Topology.TunnelZone.HostToIp
import org.midonet.cluster.models.Topology.{Host, Port, TunnelZone}
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.cluster.util.IPSubnetUtil._
import org.midonet.cluster.util.MapConverter
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.topology.devices.PortInterfaceConverter
import org.midonet.midolman.topology.devices.TunnelZone.HostIpConverter
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
                                   interfaceName: String = "",
                                   adminStateUp: Boolean = true,
                                   portGroupIds: Set[UUID] = Set.empty,
                                   vlanId: Int = 0): Port = {
        createPortBuilder(id, bridgeId, inboundFilterId, outboundFilterId,
                          tunnelKey, peerId, vifId, hostId, interfaceName,
                          adminStateUp, portGroupIds)
            .setVlanId(vlanId)
            .build
    }

    protected def createRouterPort(id: UUID = UUID.randomUUID,
                                   bridgeId: UUID = UUID.randomUUID,
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
        createPortBuilder(id, bridgeId, inboundFilterId, outboundFilterId,
                          tunnelKey, peerId, vifId, hostId, interfaceName,
                          adminStateUp, portGroupIds)
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
                                  interfaceName: String = "",
                                  adminStateUp: Boolean = true,
                                  portGroupIds: Set[UUID] = Set.empty,
                                  vtepMgmtIp: IPAddr = IPv4Addr.random,
                                  vtepMgmtPort: Int = random.nextInt(),
                                  vtepVni: Int = random.nextInt(),
                                  vtepTunnelIp: IPAddr = IPv4Addr.random,
                                  vtepTunnelZoneId: UUID = UUID.randomUUID): Port = {
        createPortBuilder(id, bridgeId, inboundFilterId, outboundFilterId,
                          tunnelKey, peerId, vifId, hostId, interfaceName,
                          adminStateUp, portGroupIds)
            .setVtepMgmtIp(vtepMgmtIp.asProto)
            .setVtepMgmtPort(vtepMgmtPort)
            .setVtepVni(vtepVni)
            .setVtepTunnelIp(vtepTunnelIp.asProto)
            .setVtepTunnelZoneId(vtepTunnelZoneId.asProto)
            .build
    }

    private def createPortBuilder(id: UUID,
                                  bridgeId: UUID,
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
            .setNetworkId(bridgeId.asProto)
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

    protected def createTunnelZoneBuilder(id: UUID, name: String,
                                          hosts: Map[UUID, IPAddr]): TunnelZone.Builder = {
        val builder = TunnelZone.newBuilder
            .setId(id.asProto)
            .setName(name)
        val converter = getConverter(classOf[HostIpConverter])
        val hostsList = converter.asInstanceOf[MapConverter[UUID, IPAddr, HostToIp]]
            .toProto(hosts, classOf[HostToIp])
        builder.addAllHosts(hostsList)

        builder
    }

    protected def createHostBuilder(id: UUID,
                                    portInterfaceMapping: Map[UUID, String],
                                    tunnelZoneIds: Set[UUID]): Host.Builder = {
        val builder = Host.newBuilder
            .setId(id.asProto)
        val converter = getConverter(classOf[PortInterfaceConverter])
        val portToInterfaceList = converter.asInstanceOf[MapConverter[UUID, String, PortToInterface]]
            .toProto(portInterfaceMapping, classOf[PortToInterface])
        builder.addAllPortInterfaceMapping(portToInterfaceList)

        tunnelZoneIds foreach { tunnelId => builder.addTunnelZoneIds(tunnelId.asProto) }
        builder
    }

    private def getConverter(clazz: Class[_ <: ZoomConvert.Converter[_,_]])
        :ZoomConvert.Converter[_,_] = {

        converters.getOrElseUpdate(clazz, clazz.newInstance()
            .asInstanceOf[ZoomConvert.Converter[_,_]])
    }
}

object TopologyBuilder {

    private val random = new Random()

    def randomIPv4Subnet = new IPv4Subnet(random.nextInt(), random.nextInt(32))

    val converters = mutable.Map[Class[_ <: ZoomConvert.Converter[_,_]],
                                 ZoomConvert.Converter[_,_]]()
}

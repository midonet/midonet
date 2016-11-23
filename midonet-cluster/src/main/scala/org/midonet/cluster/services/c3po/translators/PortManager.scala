/*
 * Copyright 2016 Midokura SARL
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

import java.util.{List => JList}

import scala.collection.JavaConverters._
import scala.collection.mutable

import org.midonet.cluster.data.storage.{NotFoundException, ObjectNameNotUniqueException, Transaction}
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Commons.{IPAddress, UUID}
import org.midonet.cluster.models.Neutron.NeutronPort.{DeviceOwner, ExtraDhcpOpts}
import org.midonet.cluster.models.Neutron.{NeutronNetwork, NeutronPort, NeutronPortOrBuilder}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.NeutronTranslatorManager.{Delete, Operation, Update}
import org.midonet.cluster.util.SequenceDispenser
import org.midonet.cluster.util.SequenceDispenser.OverlayTunnelKey
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
import org.midonet.util.concurrent.toFutureOps

/**
 * Contains port-related operations shared by multiple translator classes.
 */
trait PortManager extends ChainManager with RouteManager {

    protected type DhcpUpdateFunction =
        (Dhcp.Builder, String, IPAddress, JList[ExtraDhcpOpts]) => Unit

    protected def newRouterPortBuilder(id: UUID, routerId: UUID,
                                       adminStateUp: Boolean = true)
    : Port.Builder = Port.newBuilder.setId(id)
                                    .setRouterId(routerId)
                                    .setAdminStateUp(adminStateUp)

    protected def assignTunnelKey(port: Port.Builder,
                                  sequenceDispenser: SequenceDispenser): Unit = {
        val tk = sequenceDispenser.next(OverlayTunnelKey).await()
        port.setTunnelKey(tk)
    }

    /**
      * Bind the specified port to the specified host and interface. Port and
      * host must exist in Midonet topology, and neither the port nor the
      * interface may already be bound on the specified host.
      */
    @throws[IllegalStateException]
    protected def bindPort(tx: Transaction, port: Port, hostId: UUID,
                           interfaceName: String): Unit = {
        if (port.hasHostId)
            throw new IllegalStateException(s"Port ${port.getId.asJava} is " +
                                            s"already bound.")

        tx.update(port.toBuilder
                      .setHostId(hostId)
                      .setInterfaceName(interfaceName)
                      .build())
    }

    protected def isOnUplinkNetwork(tx: Transaction, np: NeutronPort) = {
        NetworkTranslator.isUplinkNetwork(tx.get(classOf[NeutronNetwork],
                                                 np.getNetworkId))
    }

    protected def getHostIdByName(tx: Transaction, hostName: String) : UUID = {
        // FIXME: Find host ID by looping through all hosts
        // This is temporary until host ID of MidoNet could be
        // deterministically fetched from the host name.
        val hosts = tx.getAll(classOf[Host]).filter(_.getName == hostName)

        hosts.size match {
            case 1 => hosts.head.getId
            case 0 => throw new NotFoundException(classOf[Host], hostName)
            case _ => throw new ObjectNameNotUniqueException(classOf[Host],
                                                             hostName)
        }
    }

    /**
      * Updates the DHCP configuration by applying the given update function.
      */
    protected def updateDhcpEntries(tx: Transaction,
                                    nPort: NeutronPort,
                                    subnetCache: mutable.Map[UUID, Dhcp.Builder],
                                    updateFun: DhcpUpdateFunction,
                                    ignoreNonExistingDhcp: Boolean): Unit = {
        for (ipAlloc <- nPort.getFixedIpsList.asScala) {
            try {
                val subnet = subnetCache.getOrElseUpdate(
                    ipAlloc.getSubnetId,
                    tx.get(classOf[Dhcp], ipAlloc.getSubnetId).toBuilder)
                val mac = nPort.getMacAddress
                val ipAddress = ipAlloc.getIpAddress
                updateFun(subnet, mac, ipAddress, nPort.getExtraDhcpOptsList)
            } catch {
                case nfe: NotFoundException if ignoreNonExistingDhcp =>
                // Ignores DHCPs already deleted.
            }
        }
    }

    /**
      * Deletes a host entry with the given mac / IP address pair in DHCP.
      */
    protected def delDhcpHost(dhcp: Dhcp.Builder, mac: String,
                              ipAddr: IPAddress, opts: JList[ExtraDhcpOpts])
    : Unit = {
        val remove = dhcp.getHostsList.asScala.indexWhere(
            h => h.getMac == mac && h.getIpAddress == ipAddr)
        if (remove >= 0) dhcp.removeHosts(remove)
    }

    /**
      * Operations to delete a port's security chains (in, out, antispoof).
      */
    protected def deleteSecurityChainsOps(portId: UUID)
    : Seq[Operation[Chain]] = {
        Seq(Delete(classOf[Chain], inChainId(portId)),
            Delete(classOf[Chain], outChainId(portId)),
            Delete(classOf[Chain], antiSpoofChainId(portId)))
    }

    /**
      * Operations to delete a port's security chains (in, out, antispoof).
      */
    protected def deleteSecurityChains(tx: Transaction, portId: UUID)
    : Unit = {
        tx.delete(classOf[Chain], inChainId(portId), ignoresNeo = true)
        tx.delete(classOf[Chain], outChainId(portId), ignoresNeo = true)
        tx.delete(classOf[Chain], antiSpoofChainId(portId), ignoresNeo = true)
    }

    /**
      * Operations that remove a port's IP addresses from its IPAddrGroups.
      */
    protected def removeIpsFromIpAddrGroupsOps(tx: Transaction, port: NeutronPort)
    : Seq[Operation[IPAddrGroup]] = {
        // Remove the fixed IPs from IP Address Groups
        val ips = port.getFixedIpsList.asScala.map(_.getIpAddress)
        val sgIds = port.getSecurityGroupsList.asScala.toList
        val addrGrps = tx.getAll(classOf[IPAddrGroup], sgIds)
        for (addrGrp <- addrGrps) yield {
            val addrGrpBldr = addrGrp.toBuilder
            for (ipPort <- addrGrpBldr.getIpAddrPortsBuilderList.asScala) {
                if (ips.contains(ipPort.getIpAddress)) {
                    val idx = ipPort.getPortIdsList.indexOf(port.getId)
                    if (idx >= 0) ipPort.removePortIds(idx)
                }
            }
            Update(addrGrpBldr.build())
        }
    }

    protected def newRouterInterfacePortGroup(routerId: UUID,
                                              tenantId: String) = {
        val pgId = PortManager.routerInterfacePortGroupId(routerId)
        val name = PortManager.routerInterfacePortGroupName(routerId)
        val portGroup = PortGroup.newBuilder.setId(pgId)
                                            .setName(name)
                                            .setTenantId(tenantId)
                                            .setStateful(false)
        portGroup
    }

    protected def ensureRouterInterfacePortGroup(tx: Transaction,
                                                 routerId: UUID): UUID = {
        val pgId = PortManager.routerInterfacePortGroupId(routerId)

        if (!tx.exists(classOf[PortGroup], pgId)) {
            // NOTE(yamamoto): A router created by an old version
            // of translation doesn't have the port group.
            val router = tx.get(classOf[Router], routerId)
            val pg = newRouterInterfacePortGroup(routerId, router.getTenantId)
            getRouterInterfacePorts(tx, router).foreach(pg.addPortIds)
            tx.create(pg.build())
        }

        pgId
    }

    protected def getRouterInterfacePorts(tx: Transaction, router: Router)
    : Seq[UUID] = {
        val ports = router.getPortIdsList.asScala

        ports.filter(isRouterInterfacePeer(tx, _))
    }

    protected def isRouterInterfacePeer(tx: Transaction, portId: UUID)
    : Boolean = {
        // Using routerInterfacePortPeerId in the opposite way.
        val nPortId = PortManager.routerInterfacePortPeerId(portId)
        if (tx.exists(classOf[NeutronPort], nPortId)) {
            val nPort = tx.get(classOf[NeutronPort], nPortId)
            PortManager.isRouterInterfacePort(nPort)
        } else {
            false
        }
    }
}

object PortManager {
    def isVifPort(nPort: NeutronPortOrBuilder) =
        nPort.getDeviceOwner == DeviceOwner.COMPUTE
    def isDhcpPort(nPort: NeutronPortOrBuilder) =
        nPort.getDeviceOwner == DeviceOwner.DHCP
    def isFloatingIpPort(nPort: NeutronPortOrBuilder) =
        nPort.getDeviceOwner == DeviceOwner.FLOATINGIP
    def isRouterInterfacePort(nPort: NeutronPortOrBuilder) =
        nPort.getDeviceOwner == DeviceOwner.ROUTER_INTERFACE
    def isRouterGatewayPort(nPort: NeutronPortOrBuilder) =
        nPort.getDeviceOwner == DeviceOwner.ROUTER_GATEWAY
    def isVipPort(nPort: NeutronPortOrBuilder) =
        nPort.getDeviceOwner == DeviceOwner.LOADBALANCER
    def isRemoteSitePort(nPort: NeutronPortOrBuilder) =
        nPort.getDeviceOwner == DeviceOwner.REMOTE_SITE

    // NOTE(yamamoto): This is intended to be sync with Neutron's
    // is_port_trusted.
    def isTrustedPort(nPort: NeutronPortOrBuilder) =
        ! (isVifPort(nPort) || isVipPort(nPort))

    def isIpv6Port(nPort: NeutronPortOrBuilder) =
        nPort.getFixedIpsCount > 0 &&
        nPort.getFixedIps(0).getIpAddress.getVersion == Commons.IPVersion.V6

    def hasMacAndArpTableEntries(nPort: NeutronPortOrBuilder): Boolean =
        !isIpv6Port(nPort) &&
        (isVifPort(nPort) || isRouterInterfacePort(nPort) ||
         isRouterGatewayPort(nPort) || isRemoteSitePort(nPort))

    /** ID of Router Interface port peer. */
    def routerInterfacePortPeerId(portId: UUID): UUID =
        portId.xorWith(0x9c30300ec91f4f19L, 0x88449d37e61b60f0L)

    /** ID of port group for specified device. Currently only edge routers have
      * port groups. */
    def portGroupId(deviceId: UUID): UUID =
        deviceId.xorWith(0x3fb30e769f5041f1L, 0xa50c3c4fb09a6a18L)

    /** ID of port group for router interfaces of a router */
    def routerInterfacePortGroupId(deviceId: UUID): UUID =
        deviceId.xorWith(0x77e7c2e6ed0dbab5L, 0x245499dbae09aa26L)

    def routerInterfacePortGroupName(id: UUID) =
        "OS_PORT_GROUP_ROUTER_INTF_" + id.asJava
}

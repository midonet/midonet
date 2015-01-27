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

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

import com.google.protobuf.Message

import org.midonet.brain.services.c3po.midonet.{Create, Delete, MidoOp, Update}
import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.{IPAddress, IPSubnet, IPVersion, UUID}
import org.midonet.cluster.models.Neutron.{NeutronPort, NeutronSubnet}
import org.midonet.cluster.models.Topology.{Dhcp, IpAddrGroup, Network, Port, PortOrBuilder, Router}
import org.midonet.cluster.models.Topology.{Rule, Chain}
import org.midonet.cluster.util.{IPSubnetUtil, UUIDUtil}
import org.midonet.packets.{ARP, IPv4, IPv6}
import org.midonet.util.concurrent.toFutureOps

object PortTranslator {
    private def isIpv4(nSubnet: NeutronSubnet) = nSubnet.getIpVersion == 4
    private def isIpv6(nSubnet: NeutronSubnet) = nSubnet.getIpVersion == 6
    private def egressChainName(portId: UUID) =
        "OS_PORT_" + UUIDUtil.fromProto(portId) + "_INBOUND"

    private def ingressChainName(portId: UUID) =
        "OS_PORT_" + UUIDUtil.fromProto(portId) + "_OUTBOUND"
}

class PortTranslator(val storage: ReadOnlyStorage)
        extends NeutronTranslator[NeutronPort]
        with ChainManager with PortManager with RouteManager with RuleManager {
    import org.midonet.brain.services.c3po.translators.PortTranslator._

    override protected def translateCreate(nPort: NeutronPort): MidoOpList = {
        val midoPortBldr: Port.Builder = if (isRouterGatewayPort(nPort)) {
            // TODO Create a router port and set the provider router ID.
            null
        } else if (!isFloatingIpPort(nPort)) {
            // For all other port types except floating IP port, create a normal
            // bridge (network) port.
            translateNeutronPort(nPort)
        } else null

        val portId = nPort.getId
        val midoOps = new MidoOpListBuffer
        val portContext = initPortContext
        if (isVifPort(nPort)) {
            // Generate in/outbound chain IDs from Port ID.
            val chainIds = getChainIds(portId)
            midoPortBldr.setInboundFilterId(chainIds.inChainId)
            midoPortBldr.setOutboundFilterId(chainIds.outChainId)

            // Add new DHCP host entries
            updateDhcpEntries(nPort,
                              portContext.midoNetworks,
                              portContext.neutronSubnet,
                              addDhcpHost)
            updateSecurityBindings(nPort, null, midoPortBldr, portContext)
        } else if (isDhcpPort(nPort)) {
            updateDhcpEntries(nPort,
                              portContext.midoNetworks,
                              portContext.neutronSubnet,
                              addDhcpServer)
            midoOps ++= configureMetaDataService(nPort, portContext)
        }
        addMidoOps(portContext, midoOps)

        if (midoPortBldr != null) midoOps += Create(midoPortBldr.build)
        midoOps.toList
    }

    override protected def translateDelete(id: UUID): MidoOpList = {
        val midoOps = new MidoOpListBuffer

        val nPort = storage.get(classOf[NeutronPort], id).await()
        if (!isFloatingIpPort(nPort))
            midoOps += Delete(classOf[Port], id)

        val portContext = initPortContext
        if (isVifPort(nPort)) { // It's a VIF port.
            val mPort = storage.get(classOf[Port], id).await()
            // Delete old DHCP host entries
            updateDhcpEntries(nPort,
                              portContext.midoNetworks,
                              mutable.Map(), // Not interested in old subnets.
                              delDhcpHost)
            deleteSecurityBindings(nPort, mPort, portContext)
        } else if (isDhcpPort(nPort)) {
            updateDhcpEntries(nPort,
                              portContext.midoNetworks,
                              portContext.neutronSubnet,
                              delDhcpServer)
            midoOps ++= deleteMetaDataServiceRoute(nPort, portContext)
        }
        addMidoOps(portContext, midoOps)

        midoOps.toList
    }

    override protected def translateUpdate(nPort: NeutronPort): MidoOpList = {
        val midoOps = new MidoOpListBuffer

        // It is assumed that the fixed IPs assigned to a Neutron Port will not
        // be changed.
        val portId = nPort.getId
        val mPort = storage.get(classOf[Port], portId).await()
        if ((isVifPort(nPort) || isDhcpPort(nPort)) &&
            mPort.getAdminStateUp != nPort.getAdminStateUp)
            midoOps += Update(mPort.toBuilder
                                    .setAdminStateUp(nPort.getAdminStateUp)
                                    .build)

        if (isVifPort(nPort)) { // It's a VIF port.
            val portContext = initPortContext
            val oldNPort = storage.get(classOf[NeutronPort], portId).await()
            // Delete old DHCP host entries
            updateDhcpEntries(oldNPort,
                              portContext.midoNetworks,
                              mutable.Map(), // Not interested in old subnets.
                              delDhcpHost)
            // Add new DHCP host entriess
            updateDhcpEntries(nPort,
                              portContext.midoNetworks,
                              portContext.neutronSubnet,
                              addDhcpHost)
            updateSecurityBindings(nPort, oldNPort, mPort, portContext)
            addMidoOps(portContext, midoOps)
        }
        // TODO if a DHCP port, assert that the fixed IPs have't changed.

        midoOps.toList
    }

    /* A container class holding context associated with a Neutron Port CRUD.
     */
    private case class PortContext(
            midoNetworks: mutable.Map[UUID, Network.Builder],
            neutronSubnet: mutable.Map[UUID, NeutronSubnet],
            inRules: ListBuffer[MidoOp[Rule]],
            outRules: ListBuffer[MidoOp[Rule]],
            chains: ListBuffer[MidoOp[Chain]],
            updatedIpAddrGrps: ListBuffer[MidoOp[IpAddrGroup]])

    private def initPortContext =
        PortContext(mutable.Map[UUID, Network.Builder](),
                    mutable.Map[UUID, NeutronSubnet](),
                    ListBuffer[MidoOp[Rule]](),
                    ListBuffer[MidoOp[Rule]](),
                    ListBuffer[MidoOp[Chain]](),
                    ListBuffer[MidoOp[IpAddrGroup]]())


    private def addMidoOps(portModels: PortContext,
                           midoOps: MidoOpListBuffer) {
            midoOps ++= portModels.midoNetworks.values.map(n => Update(n.build))
            midoOps ++= portModels.inRules ++ portModels.outRules
            midoOps ++= portModels.chains
            midoOps ++= portModels.updatedIpAddrGrps
    }

    /* Update DHCP subnet entries in the Network's DHCP settings. */
    private def updateDhcpEntries(
            nPort: NeutronPort,
            networkCache: mutable.Map[UUID, Network.Builder],
            subnetCache: mutable.Map[UUID, NeutronSubnet],
            updateFun: (Network.Builder, IPSubnet, String, IPAddress) => Unit) {
        for (ipAlloc <- nPort.getFixedIpsList.asScala) {
            val subnet = storage.get(classOf[NeutronSubnet],
                                     ipAlloc.getSubnetId).await()
            subnetCache.put(ipAlloc.getSubnetId, subnet)
            val ipSubnet = IPSubnetUtil.toProto(subnet.getCidr)
            val network = networkCache.getOrElseUpdate(
                    subnet.getNetworkId,
                    storage.get(classOf[Network], subnet.getNetworkId)
                                                .await().toBuilder)

            val mac = nPort.getMacAddress
            val ipAddress = ipAlloc.getIpAddress
            updateFun(network, ipSubnet, mac, ipAddress)
        }
    }

    /* Find a DHCP subnet of the give bridge with the specified IP Subnet. */
    private def findDhcpSubnet(network: Network.Builder,
                               subnet: IPSubnet): Option[Dhcp.Builder] = {
        network.getDhcpSubnetsBuilderList.asScala
               .find( _.getSubnetAddress == subnet)
    }

    /* Looks up a subnet in the specified Network with corresponding subnet
     * address, and adds a host entry with the given mac / IP address pair. It's
     * a no-op if the subnet is not created with the Network, which is assumed
     * to have been checked by the caller. Such a case shouldn't really happen
     * since the Neutron API is the only component that updates Topology.
     */
    private def addDhcpHost(network: Network.Builder, subnet: IPSubnet,
                            mac: String, ipAddr: IPAddress) {
        findDhcpSubnet(network, subnet).foreach(_.addHostsBuilder()
                                                 .setMac(mac)
                                                 .setIpAddress(ipAddr))
    }

    /* Looks up a subnet in the specified Network with corresponding subnet
     * address, and deletes a host entry with the given mac / IP address pair.
     * It's a no-op if the subnet does not exist / is already deleted, which is
     * assumed to have been checked by the caller. Such a case shouldn't really
     * happen since the Neutron API is the only component that updates Topology.
     */
    private def delDhcpHost(network: Network.Builder, subnet: IPSubnet,
                            mac: String, ipAddr: IPAddress) {
        findDhcpSubnet(network, subnet).foreach { dhcp =>
                val remove = dhcp.getHostsList.asScala.indexWhere(
                    h => h.getMac == mac && h.getIpAddress == ipAddr)
                if (remove >= 0) dhcp.removeHosts(remove)
        }
    }

    /* Looks up a subnet in the given Network with the corresponding subnet
     * address and configures the DHCP server and an OPT 121 route to it with
     * the given IP address (the mac is actually not being used here). This
     * method is a no-op if the specified subnet does not exist with the
     * Network.
     */
    private def addDhcpServer(network: Network.Builder, subnet: IPSubnet,
                              mac: String, ipAddr: IPAddress) {
        if (subnet.getVersion == IPVersion.V4)
            findDhcpSubnet(network, subnet).foreach({subnet =>
                subnet.setServerAddress(ipAddr)
                val opt121 = subnet.addOpt121RoutesBuilder()
                opt121.setDstSubnet(META_DATA_SRVC)
                      .setGateway(ipAddr)
            })
    }

    /* Looks up a subnet in the given Network with the corresponding subnet
     * address and removes the DHCP server and OPT 121 route configurations with
     * the given IP address (the mac is actually not being used here). This
     * method is a no-op if the specified subnet does not exist with the
     * Network.
     */
    private def delDhcpServer(network: Network.Builder, subnet: IPSubnet,
                              mac: String, nextHopGw: IPAddress) {
        if (subnet.getVersion == IPVersion.V4)
            findDhcpSubnet(network, subnet).foreach({dhcpSubnet =>
                dhcpSubnet.clearServerAddress()
                val route = dhcpSubnet.getOpt121RoutesOrBuilderList.asScala
                            .indexWhere(isMetaDataSvrOpt121Route(_, nextHopGw))
                if (route >= 0) dhcpSubnet.removeOpt121Routes(route)
            })
    }

    /* Create chains, rules and IP Address Groups associated with nPort. */
    private def updateSecurityBindings(nPort: NeutronPort,
                                       nPortOld: NeutronPort,
                                       mPort: PortOrBuilder,
                                       portInfo: PortContext) {
        val portId = nPort.getId
        val inChainId = mPort.getInboundFilterId
        val outChainId = mPort.getOutboundFilterId
        val mac = nPort.getMacAddress
        // Create an IP spoofing protection rule.
        for (subnet <- portInfo.neutronSubnet.values) {
            val ipSubnet = IPSubnetUtil.toProto(subnet.getCidr)
            val ipEtherType = if (isIpv4(subnet)) IPv4.ETHERTYPE
                              else IPv6.ETHERTYPE
            // NOTE: if a port belongs to more than 1 subnet, the drop rules
            // that would be created below would drop all packets. Currently
            // we don't "handle" more than 1 IP address per port, so there
            // should be no problem, but this can potentially cause problems.
            portInfo.inRules += Create(dropRuleBuilder(inChainId)
                                       .setNwSrcIp(ipSubnet)
                                       .setNwSrcInv(true)
                                       .setDlType(ipEtherType).build)

            // TODO If a port is attached to an external NeutronNetwork,
            // add a route to the provider router.
        }

        // Create reverse flow rules.
        portInfo.outRules += Create(reverseFlowRule(outChainId))
        // MAC spoofing protection
        portInfo.inRules += Create(dropRuleBuilder(inChainId)
                                   .setDlSrc(mac)
                                   .setInvDlSrc(true).build)
        // Create reverse flow rules matching for inbound chain.
        portInfo.inRules += Create(reverseFlowRule(inChainId))
        // Add jump rules to corresponding inbound / outbound chains of IP
        // Address Groups (Neutron's Security Groups) that the port belongs to.
        for (sgId <- nPort.getSecurityGroupsList.asScala) {
            val ipAddrGrp = storage.get(classOf[IpAddrGroup], sgId).await()
            // Jump rules to inbound / outbound chains of IP Address Groups
            portInfo.inRules += Create(jumpRule(inChainId,
                                                ipAddrGrp.getInboundChainId))
            portInfo.outRules += Create(jumpRule(outChainId,
                                                 ipAddrGrp.getOutboundChainId))

            // Compute IP addresses that were removed from the IP Address Group
            // or added to it.
            val updatedIpAddrG = ipAddrGrp.toBuilder
            val ips = nPort.getFixedIpsList.asScala.map(_.getIpAddress)
            val oldIps = if (nPortOld != null) {
                    nPortOld.getFixedIpsList.asScala.map(_.getIpAddress)
                } else List()
            val removed = oldIps diff ips
            var added = ips diff oldIps
            for (ipPorts <- updatedIpAddrG.getIpAddrPortsBuilderList.asScala) {
                if (removed.contains(ipPorts.getIpAddress)) {
                    val idx = ipPorts.getPortIdList.asScala
                                                   .indexOf(portId)
                    if (idx >= 0) ipPorts.removePortId(idx)
                } else  if (added contains ipPorts.getIpAddress) {
                    // Exclude IPs that are already in IP Address Groups.
                    ipPorts.addPortId(portId)
                    added -= ipPorts.getIpAddress
                }
            }
            for (newIp <- added)
                updatedIpAddrG.addIpAddrPortsBuilder()
                              .setIpAddress(newIp)
                              .addPortId(portId)
            portInfo.updatedIpAddrGrps += Update(updatedIpAddrG.build)
        }

        // Drop non-ARP traffic that wasn't accepted by earlier rules.
        portInfo.inRules += Create(dropRuleBuilder(inChainId)
                                   .setDlType(ARP.ETHERTYPE)
                                   .setInvDlType(true).build)
        portInfo.outRules += Create(dropRuleBuilder(outChainId)
                                    .setDlType(ARP.ETHERTYPE)
                                    .setInvDlType(true).build)

        // Create in/outbound chains with the IDs of the above rules.
        val inChain = newChain(inChainId, egressChainName(portId),
                               toRuleIdList(portInfo.inRules))
        val outChain = newChain(outChainId, ingressChainName(portId),
                                toRuleIdList(portInfo.outRules))

        if (nPortOld != null) { // Update
            portInfo.chains += (Update(inChain), Update(outChain))

            val iChain = storage.get(classOf[Chain], inChainId).await()
            portInfo.inRules ++= iChain.getRuleIdsList.asScala
                                       .map(Delete(classOf[Rule], _))
            val oChain = storage.get(classOf[Chain], outChainId).await()
            portInfo.outRules ++= oChain.getRuleIdsList.asScala
                                        .map(Delete(classOf[Rule], _))
        } else { // Create
            portInfo.chains += (Create(inChain), Create(outChain))
        }
    }

    /* Delete chains, rules and IP Address Groups associated with nPortOld. */
    private def deleteSecurityBindings(nPortOld: NeutronPort,
                                       mPort: Port,
                                       portContext: PortContext) {
        val portId = nPortOld.getId
        val inChainId = mPort.getInboundFilterId
        val outChainId = mPort.getOutboundFilterId
        val iChain = storage.get(classOf[Chain], inChainId).await()
        portContext.inRules ++= iChain.getRuleIdsList.asScala
                                   .map(Delete(classOf[Rule], _))
        val oChain = storage.get(classOf[Chain], outChainId).await()
        portContext.outRules ++= oChain.getRuleIdsList.asScala
                                    .map(Delete(classOf[Rule], _))
        portContext.chains += (Delete(classOf[Chain], inChainId),
                            Delete(classOf[Chain], outChainId))

        // Remove the fixed IPs from IP Address Groups
        for (sgId <- nPortOld.getSecurityGroupsList.asScala) {
            val ipAddrG = storage.get(classOf[IpAddrGroup], sgId).await()
            val updatedIpAddrG = ipAddrG.toBuilder
            val oldIps = nPortOld.getFixedIpsList.asScala.map(_.getIpAddress)
            for (ipPorts <- updatedIpAddrG.getIpAddrPortsBuilderList.asScala) {
                if (oldIps.contains(ipPorts.getIpAddress)) {
                    val idx = ipPorts.getPortIdList.asScala
                                                   .indexWhere(_ == portId)
                    if (idx >= 0) ipPorts.removePortId(idx)
                }
            }
            portContext.updatedIpAddrGrps += Update(updatedIpAddrG.build)
        }
    }

    /* If the first fixed IP address is configured with a gateway IP address,
     * create a route to Meta Data Service.*/
    private def configureMetaDataService(nPort: NeutronPort,
                                         portContext: PortContext)
    : Option[MidoOp[_ <: Message]] = if (nPort.getFixedIpsCount > 0) {
        val nextHopGateway = nPort.getFixedIps(0).getIpAddress
        val nextHopGatewaySubnetId = nPort.getFixedIps(0).getSubnetId
        val srcSubnet = portContext.neutronSubnet(nextHopGatewaySubnetId)

        findGateway(srcSubnet, portContext) map { gateway =>
            val metaDataSvcRoute = createMetaDataServiceRoute(
                srcSubnet = IPSubnetUtil.toProto(srcSubnet.getCidr),
                nextHopPortId = gateway.routerPortId,
                nextHopGw = nextHopGateway,
                routerId = gateway.router.getId)
            Update(gateway.router.toBuilder.addRoutes(metaDataSvcRoute).build())
        }
    } else None

    /* If the first fixed IP address is configured with a gateway IP address,
     * delete a route to Meta Data Service from the gateway router.*/
    private def deleteMetaDataServiceRoute(nPort: NeutronPort,
                                           portContext: PortContext)
    : Option[MidoOp[_ <: Message]] = if (nPort.getFixedIpsCount > 0) {
        val nextHopGateway = nPort.getFixedIps(0).getIpAddress
        val nextHopGatewaySubnetId = nPort.getFixedIps(0).getSubnetId
        val srcSubnet = portContext.neutronSubnet(nextHopGatewaySubnetId)

        findGateway(srcSubnet, portContext).flatMap { gateway =>
            val svcRoute = gateway.router.getRoutesOrBuilderList.asScala
                                         .indexWhere(isMetaDataSvrRoute(
                                                 _, nextHopGateway))
            if (svcRoute < 0) None
            else Some(Update(gateway.router.toBuilder
                                    .removeRoutes(svcRoute).build()))
        }
    } else None

    private case class Gateway(routerPortId: UUID, router: Router)
    /* Find gateway router & router port configured with the subnet. */
    private def findGateway(subnet: NeutronSubnet,
                            portContext: PortContext) : Option[Gateway] = {
        if (!subnet.hasGatewayIp) return None
        val network = portContext.midoNetworks(subnet.getNetworkId)
        // Find a first logical port that has a peer port with the gateway IP.
        for (portId <- network.getPortIdsList.asScala) {
            val port = storage.get(classOf[Port], portId).await()
            if (port.hasPeerId) {
                val peer = storage.get(classOf[Port], port.getPeerId).await()
                if (subnet.getGatewayIp == peer.getPortAddress) {
                    val router = storage.get(classOf[Router],
                                             peer.getRouterId).await()
                    return Some(Gateway(peer.getId, router))
                }
            }
        }
        None
    }

    private def translateNeutronPort(nPort: NeutronPort): Port.Builder =
        Port.newBuilder.setId(nPort.getId)
            .setNetworkId(nPort.getNetworkId)
            .setAdminStateUp(nPort.getAdminStateUp)
}

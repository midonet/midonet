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

import org.midonet.brain.services.c3po.C3POStorageManager.Operation
import org.midonet.brain.services.c3po.midonet.{Create, Delete, MidoOp, Update}
import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.{IPAddress, IPSubnet, UUID}
import org.midonet.cluster.models.Neutron.NeutronPort.DeviceOwner
import org.midonet.cluster.models.Neutron.{NeutronPort, NeutronSubnet}
import org.midonet.cluster.models.Topology.Network.Dhcp
import org.midonet.cluster.models.Topology.Rule.Action.{ACCEPT, DROP}
import org.midonet.cluster.models.Topology.Rule.Condition.FragmentPolicy
import org.midonet.cluster.models.Topology.Rule.{Condition, JumpData, NatData}
import org.midonet.cluster.models.Topology.{Chain, IpAddrGroup, Network, Port, PortOrBuilder, Rule}
import org.midonet.cluster.util.{IPSubnetUtil, UUIDUtil}
import org.midonet.packets.{ARP, IPv4, IPv6}
import org.midonet.util.concurrent.toFutureOps

object PortTranslator {
    private def isVifPort(nPort: NeutronPort) = !nPort.hasDeviceOwner
    private def isDhcpPort(nPort: NeutronPort) =
        nPort.hasDeviceOwner && nPort.getDeviceOwner == DeviceOwner.DHCP
    private def isFloatingIpPort(nPort: NeutronPort) =
        nPort.hasDeviceOwner && nPort.getDeviceOwner == DeviceOwner.FLOATINGIP
    private def isRouterInterfacePort(nPort: NeutronPort) =
        nPort.hasDeviceOwner && nPort.getDeviceOwner == DeviceOwner.ROUTER_INTERFACE
    private def isRouterGatewayPort(nPort: NeutronPort) =
        nPort.hasDeviceOwner && nPort.getDeviceOwner == DeviceOwner.ROUTER_GATEWAY

    private def isIpv4(nSubnet: NeutronSubnet) = nSubnet.getIpVersion == 4
    private def isIpv6(nSubnet: NeutronSubnet) = nSubnet.getIpVersion == 6

    /* Find a DHCP subnet of the give bridge with the specified IP Subnet.
     * Throws IllegalArgumentException if not found.
     */
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

    private def egressChainName(portId: UUID) =
        "OS_PORT_" + UUIDUtil.fromProto(portId) + "_INBOUND"

    private def ingressChainName(portId: UUID) =
        "OS_PORT_" + UUIDUtil.fromProto(portId) + "_OUTBOUND"

    private def chainBuilder(chainId: UUID,
                             chainName: String,
                             ruleIds: Seq[UUID]) = {
        val chainBldr = Chain.newBuilder().setId(chainId).setName(chainName)
        ruleIds.foreach(chainBldr.addRuleIds)
        chainBldr.build
    }

    private def reverseFlowRule(chainId: UUID): Rule =
        Rule.newBuilder().setId(UUIDUtil.randomUuidProto)
                         .setChainId(chainId)
                         .setNatData(NatData.newBuilder
                                        .setAction(ACCEPT)
                                        .setIsForward(false)
                                        .build())
                         .build()

    // TODO(nicolas): Should we make this a literal rule?
    private def dropRuleBuilder(chainId: UUID): Rule.Builder =
        Rule.newBuilder().setId(UUIDUtil.randomUuidProto)
                         .setChainId(chainId)
                         .setLiteralAction(DROP)
                         .setCondition(Condition.newBuilder
                                           .setFragmentPolicy(FragmentPolicy.ANY)
                                           .build())

    private def jumpRule(fromChain: UUID, toChain: UUID) =
        Rule.newBuilder().setId(UUIDUtil.randomUuidProto)
                         .setJumpData(JumpData.newBuilder
                                        .setJumpTo(toChain)
                                        .build())
                         .setChainId(fromChain)
                         .build

    def toRuleIdList(ops: Seq[Operation[Rule]]) =
        ops.map {
                case Create(r: Rule) => r.getId
                case Update(r: Rule) => r.getId
                case Delete(_, id) => id
        }
}

class PortTranslator(private val storage: ReadOnlyStorage)
        extends NeutronTranslator[NeutronPort] {
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
        var midoOps = new MidoOpListBuffer
        if (isVifPort(nPort)) {
            // Generate in/outbound chain IDs from Port ID.
            val chainIds = getChainIds(portId)
            midoPortBldr.setInboundFilterId(chainIds.inChainId)
            midoPortBldr.setOutboundFilterId(chainIds.outChainId)

            val vifPortInfo = buildVifPortModels(nPort, null, midoPortBldr)
            addMidoOps(vifPortInfo, midoOps)
        }

        if (midoPortBldr != null) midoOps :+= Create(midoPortBldr.build)
        midoOps.toList
    }

    override protected def translateDelete(id: UUID): MidoOpList = {
        var midoOps = new MidoOpListBuffer

        val nPort = storage.get(classOf[NeutronPort], id).await()
        if (!isFloatingIpPort(nPort))
            midoOps :+= Delete(classOf[Port], id)

        if (isVifPort(nPort)) { // It's a VIF port.
            val mPort = storage.get(classOf[Port], id).await()
            val vifPortInfo = buildVifPortModels(null, nPort, mPort)
            addMidoOps(vifPortInfo, midoOps)
        }

        midoOps.toList
    }

    override protected def translateUpdate(nPort: NeutronPort): MidoOpList = {
        var midoOps = new MidoOpListBuffer

        val portId = nPort.getId
        val mPort = storage.get(classOf[Port], portId).await()
        if ((isVifPort(nPort) || isDhcpPort(nPort)) &&
            mPort.getAdminStateUp != nPort.getAdminStateUp)
            midoOps :+= Update(mPort.toBuilder
                                    .setAdminStateUp(nPort.getAdminStateUp)
                                    .build)

        if (isVifPort(nPort)) { // It's a VIF port.
            val oldNPort = storage.get(classOf[NeutronPort], portId).await()
            val vifPortInfo = buildVifPortModels(nPort, oldNPort, mPort)
            addMidoOps(vifPortInfo, midoOps)
        }
        midoOps.toList
    }

    /* A container class holding models associated with a VIF port CRUD that are
     * to be created / updated / deleted.
     */
    private case class VifPortModels(
            midoNetworks: mutable.Map[UUID, Network.Builder],
            neutronSubnet: mutable.Set[NeutronSubnet],
            inRules: ListBuffer[MidoOp[Rule]],
            outRules: ListBuffer[MidoOp[Rule]],
            chains: ListBuffer[MidoOp[Chain]],
            updatedIpAddrGrps: ListBuffer[MidoOp[IpAddrGroup]])

    private def addMidoOps(portModels: VifPortModels,
                           midoOps: MidoOpListBuffer) {
            midoOps ++= portModels.midoNetworks.values.map(n => Update(n.build))
            midoOps ++= portModels.inRules ++ portModels.outRules
            midoOps ++= portModels.chains
            midoOps ++= portModels.updatedIpAddrGrps
    }

    /* Builds models that need to be created / updated / deleted as part of a
     * Neutron Port CRUD operation.
     * @param nPort A Neutron Port that is to be created or updated.
     * @param nPortOld An old Neutron Port that is to be either updated or
     *                 deleted.
     * @param mPortOld An old MidoNet Port corresponding to the Neutron Port
     *                 that is to be either updated or deleted.
     */
    private def buildVifPortModels(nPort: NeutronPort,
                                   nPortOld: NeutronPort,
                                   mPortOld: PortOrBuilder): VifPortModels = {
        val inChainId = mPortOld.getInboundFilterId
        val outChainId = mPortOld.getOutboundFilterId
        val portModels = VifPortModels(mutable.Map[UUID, Network.Builder](),
                                       mutable.Set[NeutronSubnet](),
                                       ListBuffer[MidoOp[Rule]](),
                                       ListBuffer[MidoOp[Rule]](),
                                       ListBuffer[MidoOp[Chain]](),
                                       ListBuffer[MidoOp[IpAddrGroup]]())

        if (nPortOld != null) { // Port UPDATE or DELETE
            updateDhcpEntries(nPortOld,
                               portModels.midoNetworks,
                               mutable.Set(), // Not interested in old subnets.
                               delDhcpHost)
        }

        if (nPort != null) { // Port CREATE or UPDATE
            updateDhcpEntries(nPort,
                               portModels.midoNetworks,
                               portModels.neutronSubnet,
                               addDhcpHost)

            updateSecurityBindings(
                    nPort, nPortOld, inChainId, outChainId, portModels)
        } else { // Port DELETION
            deleteSecurityBindings(nPortOld, inChainId, outChainId, portModels)
        }

        portModels
    }

    /* Update host entries in the Network's DHCP settings. */
    private def updateDhcpEntries(
            nPort: NeutronPort,
            networkCache: mutable.Map[UUID, Network.Builder],
            subnetCache: mutable.Set[NeutronSubnet],
            updateHost: (Network.Builder, IPSubnet, String, IPAddress) => Unit) {
        for (ipAlloc <- nPort.getFixedIpsList.asScala) {
            val subnet = storage.get(classOf[NeutronSubnet],
                                     ipAlloc.getSubnetId).await()
            subnetCache.add(subnet)
            val ipSubnet = IPSubnetUtil.toProto(subnet.getCidr)
            val network = networkCache.getOrElseUpdate(
                    subnet.getNetworkId,
                    storage.get(classOf[Network], subnet.getNetworkId)
                                                .await().toBuilder)

            val mac = nPort.getMacAddress
            val ipAddress = ipAlloc.getIpAddress
            updateHost(network, ipSubnet, mac, ipAddress)
        }
    }


    /* Create chains, rules and IP Address Groups associated with nPort. */
    private def updateSecurityBindings(nPort: NeutronPort,
                                       nPortOld: NeutronPort,
                                       inChainId: UUID,
                                       outChainId: UUID,
                                       portInfo: VifPortModels) {
        val portId = nPort.getId
        val mac = nPort.getMacAddress
        // Create an IP spoofing protection rule.
        for (subnet <- portInfo.neutronSubnet) {
            val ipSubnet = IPSubnetUtil.toProto(subnet.getCidr)
            val ipEtherType = if (isIpv4(subnet)) IPv4.ETHERTYPE
                              else IPv6.ETHERTYPE
            // NOTE: if a port belongs to more than 1 subnet, the drop rules
            // that would be created below would drop all packets. Currently
            // we don't "handle" more than 1 IP address per port, so there
            // should be no problem, but this can potentially cause problems.
            portInfo.inRules += Create(dropRuleBuilder(inChainId)
                                       .setCondition(Condition.newBuilder()
                                                         .setNwSrcIp(ipSubnet)
                                                         .setNwSrcInv(true)
                                                         .setDlType(ipEtherType)
                                                     .build())
                                       .build)

            // TODO If a port is attached to an external NeutronNetwork,
            // add a route to the provider router.
        }

        // Create reverse flow rules.
        portInfo.outRules += Create(reverseFlowRule(outChainId))
        // MAC spoofing protection
        portInfo.inRules += Create(dropRuleBuilder(inChainId)
                                   .setCondition(Condition.newBuilder
                                                     .setDlSrc(mac)
                                                     .setInvDlSrc(true)
                                                     .build())
                                   .build)
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
                                   .setCondition(Condition.newBuilder()
                                                     .setDlType(ARP.ETHERTYPE)
                                                     .setInvDlType(true)
                                                     .build())
                                   .build())
        portInfo.outRules += Create(dropRuleBuilder(outChainId)
                                    .setCondition(Condition.newBuilder
                                                      .setDlType(ARP.ETHERTYPE)
                                                      .setInvDlType(true)
                                                      .build)
                                    .build())

        // Create in/outbound chains with the IDs of the above rules.
        val inChain = chainBuilder(inChainId, egressChainName(portId),
                                   toRuleIdList(portInfo.inRules))
        val outChain = chainBuilder(outChainId, ingressChainName(portId),
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
                                       inChainId: UUID,
                                       outChainId: UUID,
                                       portInfo: VifPortModels) {
        val portId = nPortOld.getId
        val iChain = storage.get(classOf[Chain], inChainId).await()
        portInfo.inRules ++= iChain.getRuleIdsList.asScala
                                   .map(Delete(classOf[Rule], _))
        val oChain = storage.get(classOf[Chain], outChainId).await()
        portInfo.outRules ++= oChain.getRuleIdsList.asScala
                                    .map(Delete(classOf[Rule], _))
        portInfo.chains += (Delete(classOf[Chain], inChainId),
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
            portInfo.updatedIpAddrGrps += Update(updatedIpAddrG.build)
        }
    }

    private def translateNeutronPort(nPort: NeutronPort): Port.Builder =
        Port.newBuilder.setId(nPort.getId)
            .setNetworkId(nPort.getNetworkId)
            .setAdminStateUp(nPort.getAdminStateUp)
}

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
import scala.collection.mutable

import com.google.protobuf.Message

import org.midonet.brain.services.c3po.C3POStorageManager.Operation
import org.midonet.brain.services.c3po.midonet.{Create, Delete, MidoOp, Update}
import org.midonet.brain.services.c3po.neutron
import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.{IPAddress, IPSubnet, IPVersion, UUID}
import org.midonet.cluster.models.Neutron.NeutronPort
import org.midonet.cluster.models.Neutron.NeutronPort.{DeviceOwner, IPAllocation}
import org.midonet.cluster.models.Neutron.{NeutronRouter => Router}
import org.midonet.cluster.models.Neutron.NeutronSubnet
import org.midonet.cluster.models.Topology.{Chain, IpAddrGroup, Network, Port, Rule}
import org.midonet.cluster.models.Topology.Rule.Action.{ACCEPT, DROP, JUMP}
import org.midonet.cluster.models.Topology.Rule.FragmentPolicy.ANY
import org.midonet.cluster.util.{IPSubnetUtil, UUIDUtil}
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
import org.midonet.packets.{ARP, IPv4, IPv6}
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

    private def addDhcpHost(network: Network.Builder,
                            subnet: IPSubnet,
                            mac: String,
                            ipAddr: IPAddress) = {
        val dhcpBuilder = network.getDhcpSubnetsBuilderList.asScala.find(
                _.getSubnetAddress == subnet)
        dhcpBuilder match {
            case Some(dhcp) => dhcp.addHostsBuilder()
                                   .setMac(mac)
                                   .setIpAddress(ipAddr)
            // No existing subnet. Neutron config error.
            case None =>
                throw new IllegalArgumentException(
                    "The given Neutron Subnet has not been created with Mido " +
                    "Network.")
        }
    }

    private def deleteDhcpHost(network: Network.Builder,
                               subnet: IPSubnet,
                               mac: String,
                               ipAddr: IPAddress) = {
        val dhcpBuilder = network.getDhcpSubnetsBuilderList.asScala.find(
                _.getSubnetAddress == subnet)
        dhcpBuilder match {
            case Some(dhcp) =>
                val remove = dhcp.getHostsList.asScala.indexWhere(h =>
                        h.getMac == mac && h.getIpAddress == ipAddr)
                if (remove >= 0) dhcp.removeHosts(remove)

            // No existing subnet. Neutron config error.
            case None =>
                throw new IllegalArgumentException(
                    "The given Neutron Subnet has not been created with Mido " +
                    "Network.")
        }
    }

    private def egressChainName(portId: UUID) =
        "OS_PORT_" + UUIDUtil.fromProto(portId) + "_INBOUND";

    private def ingressChainName(portId: UUID) =
        "OS_PORT_" + UUIDUtil.fromProto(portId) + "_OUTBOUND";

    private def chainBuilder(chainId: UUID,
                             chainName: String,
                             ruleIds: List[UUID]) = {
        val chainBldr = Chain.newBuilder().setId(chainId).setName(chainName)
        ruleIds.map(ruleId => chainBldr.addRuleIds(ruleId))
        chainBldr.build
    }

    private def reverseFlowRule(chainId: UUID): Rule =
        Rule.newBuilder().setId(UUIDUtil.randomUuidProto)
                         .setChainId(chainId)
                         .setAction(ACCEPT)
                         .setMatchReturnFlow(true).build

    private def dropRuleBuilder(chainId: UUID): Rule.Builder =
        Rule.newBuilder().setId(UUIDUtil.randomUuidProto)
                         .setChainId(chainId)
                         .setAction(DROP)
                         .setFragmentPolicy(ANY)

    private def jumpRule(fromChain: UUID, toChain: UUID) =
        Rule.newBuilder().setId(UUIDUtil.randomUuidProto)
                         .setAction(JUMP)
                         .setChainId(fromChain)
                         .setJumpTo(toChain).build

    def toRuleIdList(ops: List[Operation[Rule]]) =
        ops.map(_ match {
                case Create(r: Rule) => r.getId
                case Update(r: Rule) => r.getId
                case Delete(_, id) => id
        })
}

class PortTranslator(private val storage: ReadOnlyStorage)
        extends NeutronTranslator[NeutronPort] {
    import org.midonet.brain.services.c3po.translators.PortTranslator._

    override protected def translateCreate(nPort: NeutronPort)
    : List[MidoOp[_ <: Message]] = {
        var midoPortBldr: Port.Builder = null
        if (isRouterGatewayPort(nPort)) {
            // TODO Create a router port and set the provider router ID.
        } else if (!isFloatingIpPort(nPort)) {
            // For all other port types except floating IP port, create a normal
            // bridge (network) port.
            midoPortBldr = translateNeutronPort(nPort)
        }

        val portId = nPort.getId
        var midoOps: List[MidoOp[_ <: Message]] = List()
        if (isVifPort(nPort)) {
            // It's a VIF port.
            val inChainId = portId.nextUuid
            val outChainId = inChainId.nextUuid
            midoPortBldr.setInboundFilterId(inChainId)
            midoPortBldr.setOutboundFilterId(outChainId)

            val vifPortInfo =
                constructVifPortInfo(nPort, null, null, inChainId, outChainId)

            midoOps ++= vifPortInfo.midoNetworks.values.map(n => Update(n.build))
            midoOps ++= vifPortInfo.inRules ++ vifPortInfo.outRules
            midoOps ++= vifPortInfo.chains
            midoOps ++= vifPortInfo.updatedIpAddressGs
        }

        if (midoPortBldr != null) midoOps :+= (Create(midoPortBldr.build))
        midoOps
    }

    override protected def translateDelete(id: UUID)
    : List[MidoOp[_ <: Message]] = {
        val nPort = storage.get(classOf[NeutronPort], id).await()
        var midoOps: List[MidoOp[_ <: Message]] = List()

        if (!isFloatingIpPort(nPort))
            midoOps :+= (Delete(classOf[Port], id))

        if (isVifPort(nPort)) { // It's a VIF port.
            val mPort = storage.get(classOf[Port], id).await()
            val inChainId = mPort.getInboundFilterId
            val outChainId = mPort.getOutboundFilterId
            val vifPortInfo =
                constructVifPortInfo(null, nPort, mPort, inChainId, outChainId)
            midoOps ++= vifPortInfo.midoNetworks
                                   .values.map(n => Update(n.build))
            midoOps ++= vifPortInfo.inRules ++ vifPortInfo.outRules
            midoOps ++= vifPortInfo.chains
            midoOps ++= vifPortInfo.updatedIpAddressGs
        }

        midoOps
    }

    override protected def translateUpdate(nPort: NeutronPort)
    : List[MidoOp[_ <: Message]] = {
        val portId = nPort.getId
        val mPort = storage.get(classOf[Port], portId).await()
        var midoOps: List[MidoOp[_ <: Message]] = List()
        if ((isVifPort(nPort) || isDhcpPort(nPort)) &&
            mPort.getAdminStateUp != nPort.getAdminStateUp)
            midoOps :+= (Update(mPort.toBuilder()
                                     .setAdminStateUp(nPort.getAdminStateUp)
                                     .build))

        if (isVifPort(nPort)) { // It's a VIF port.
            val oldNPort = storage.get(classOf[NeutronPort], portId).await()

            // Update the security bindings.
            // Expected that a Mido Port has both in/outbound chains set.
            val inChainId = mPort.getInboundFilterId
            val outChainId = mPort.getOutboundFilterId
            val vifPortInfo = constructVifPortInfo(
                    nPort, oldNPort, mPort, inChainId, outChainId)
            midoOps ++= vifPortInfo.midoNetworks
                                   .values.map(n => Update(n.build))
            midoOps ++= vifPortInfo.inRules ++ vifPortInfo.outRules
            midoOps ++= vifPortInfo.chains
            midoOps ++= vifPortInfo.updatedIpAddressGs
        }
        midoOps
    }

    case class VifPortInfo(midoNetworks: mutable.Map[UUID, Network.Builder],
                           inRules: List[MidoOp[Rule]],
                           outRules: List[MidoOp[Rule]],
                           chains: List[MidoOp[Chain]],
                           updatedIpAddressGs: List[MidoOp[IpAddrGroup]])

    private def constructVifPortInfo(nPort: NeutronPort,
                                     nPortOld: NeutronPort,
                                     mPortOld: Port,
                                     inChainId: UUID,
                                     outChainId: UUID) = {
        val portId = if (nPort != null) nPort.getId else nPortOld.getId
        val midoNetworks = mutable.Map[UUID, Network.Builder]()
        var inRules = List[MidoOp[Rule]]()
        var outRules = List[MidoOp[Rule]]()
        var chains = List[MidoOp[Chain]]()
        var updatedIpAddressGs = List[MidoOp[IpAddrGroup]]()

        // Remove old DHCP Host entries from Networks.
        if (nPortOld != null) { // UPDATE or DELETE
            val oldMac = nPortOld.getMacAddress
            for (ipAlloc <- nPortOld.getFixedIpsList.asScala) {
                val subnet = storage.get(classOf[NeutronSubnet],
                                         ipAlloc.getSubnetId).await()
                val ipSubnet = IPSubnetUtil.toProto(subnet.getCidr)
                val network = midoNetworks.getOrElse(
                        subnet.getNetworkId, {
                            val newNetwork = storage.get(classOf[Network],
                                                         subnet.getNetworkId)
                                                    .await().toBuilder
                            midoNetworks(subnet.getNetworkId) = newNetwork
                            newNetwork
                        })

                deleteDhcpHost(network, ipSubnet, oldMac, ipAlloc.getIpAddress)
            }
        }

        if (nPort != null) {
            val mac = nPort.getMacAddress
            for (ipAlloc <- nPort.getFixedIpsList.asScala) {
                val subnet = storage.get(classOf[NeutronSubnet],
                                         ipAlloc.getSubnetId).await()
                val ipSubnet = IPSubnetUtil.toProto(subnet.getCidr)
                val network = midoNetworks.getOrElse(
                        subnet.getNetworkId, {
                            val newNetwork = storage.get(classOf[Network],
                                                         subnet.getNetworkId)
                                                    .await().toBuilder
                            midoNetworks(subnet.getNetworkId) = newNetwork
                            newNetwork
                        })

                addDhcpHost(network, ipSubnet, mac, ipAlloc.getIpAddress)

                // Create an IP spoofing protection rule.
                val ipEtherType = if (isIpv4(subnet)) IPv4.ETHERTYPE
                else IPv6.ETHERTYPE
                inRules :+= (Create(dropRuleBuilder(inChainId)
                                    .setNwSrcIp(ipSubnet)
                                    .setNwSrcInv(true)
                                    .setDlType(ipEtherType).build))

                // TODO If a port is attached to an external NeutronNetwork,
                // add a route to the provider router.
            }

            // Create reverse flow rules.
            outRules :+= (Create(reverseFlowRule(outChainId)))
            // MAC spoofing protection
            inRules :+= (Create(dropRuleBuilder(inChainId)
                                .setDlSrc(mac)
                                .setInvDlSrc(true).build))
            // Create reverse flow rules matching for inbound chain.
            inRules :+= (Create(reverseFlowRule(inChainId)))
            // Add jump rules to IP Address Group / Security Group
            for (sgId <- nPort.getSecurityGroupsList.asScala) {
                val ipAddrG = storage.get(classOf[IpAddrGroup], sgId).await()
                inRules :+= (Create(jumpRule(inChainId,
                                             ipAddrG.getInboundChainId)))
                outRules :+= (Create(jumpRule(outChainId,
                                              ipAddrG.getOutboundChainId)))

                // Add this port / IP Address mapping to the IP Address Group.
                val updatedIpAddrG = ipAddrG.toBuilder()
                val ips = nPort.getFixedIpsList.asScala.map(_.getIpAddress)
                val oldIps = if (nPortOld != null) {
                    nPortOld.getFixedIpsList.asScala.map(_.getIpAddress)
                } else List()
                var removed = oldIps diff ips
                var added = ips diff oldIps
                for (ipPorts <- updatedIpAddrG.getIpAddrPortsBuilderList.asScala) {
                    if (removed.contains(ipPorts.getIpAddress)) {
                        val idx = ipPorts.getPortIdList.asScala
                                                       .indexWhere(_ == portId)
                        if (idx >= 0) ipPorts.removePortId(idx)
                    } else  if (added contains ipPorts.getIpAddress) {
                        ipPorts.addPortId(portId)
                        added = added diff List(ipPorts.getIpAddress)
                    }
                }
                for (newIp <- added)
                    updatedIpAddrG.addIpAddrPortsBuilder()
                                  .setIpAddress(newIp)
                                  .addPortId(portId)
                updatedIpAddressGs :+= (Update(updatedIpAddrG.build))
            }
            // Drop non-ARP traffic
            inRules :+= (Create(dropRuleBuilder(inChainId)
                                .setDlType(ARP.ETHERTYPE)
                                .setInvDlType(true).build))
            outRules :+= (Create(dropRuleBuilder(outChainId)
                                 .setDlType(ARP.ETHERTYPE)
                                 .setInvDlType(true).build))

            val inChain = chainBuilder(inChainId, egressChainName(portId),
                                       toRuleIdList(inRules))
            val outChain = chainBuilder(outChainId, ingressChainName(portId),
                                        toRuleIdList(outRules))
            if (mPortOld != null) { // Update
                chains ++= List(Update(inChain), Update(outChain))

                val iChain = storage.get(classOf[Chain], inChainId).await()
                inRules ++= iChain.getRuleIdsList.asScala
                                                 .map(Delete(classOf[Rule], _))
                val oChain = storage.get(classOf[Chain], outChainId).await()
                outRules ++= oChain.getRuleIdsList.asScala
                                                  .map(Delete(classOf[Rule], _))
            } else { // Create
                chains ++= List(Create(inChain), Create(outChain))
            }
        } else { // Port deletion
            val oldInChainId = mPortOld.getInboundFilterId
            val oldOutChainId = mPortOld.getOutboundFilterId
            val iChain = storage.get(classOf[Chain], oldInChainId).await()
            inRules ++= iChain.getRuleIdsList.asScala
                                             .map(Delete(classOf[Rule], _))
            val oChain = storage.get(classOf[Chain], oldOutChainId).await()
            outRules ++= oChain.getRuleIdsList.asScala
                                              .map(Delete(classOf[Rule], _))
            chains ++= List(Delete(classOf[Chain], oldInChainId),
                            Delete(classOf[Chain], oldOutChainId))

            // Remove the fixed IPs from IP Address Groups
            for (sgId <- nPortOld.getSecurityGroupsList.asScala) {
                val ipAddrG = storage.get(classOf[IpAddrGroup], sgId).await()
                val updatedIpAddrG = ipAddrG.toBuilder()
                val oldIps = nPortOld.getFixedIpsList.asScala.map(_.getIpAddress)
                for (ipPorts <- updatedIpAddrG.getIpAddrPortsBuilderList.asScala) {
                    if (oldIps.contains(ipPorts.getIpAddress)) {
                        val idx = ipPorts.getPortIdList.asScala
                                                       .indexWhere(_ == portId)
                        if (idx >= 0) ipPorts.removePortId(idx)
                    }
                }
                updatedIpAddressGs :+= (Update(updatedIpAddrG.build))
            }
        }

        VifPortInfo(midoNetworks, inRules, outRules, chains, updatedIpAddressGs)
    }

    private def translateNeutronPort(nPort: NeutronPort): Port.Builder =
        Port.newBuilder.setId(nPort.getId)
            .setNetworkId(nPort.getNetworkId)
            .setAdminStateUp(nPort.getAdminStateUp)
}

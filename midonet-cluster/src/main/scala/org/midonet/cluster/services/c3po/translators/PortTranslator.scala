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

package org.midonet.cluster.services.c3po.translators

import java.util.{List => JList}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

import org.midonet.cluster.data.storage._
import org.midonet.cluster.models.Commons.{IPAddress, IPSubnet, UUID}
import org.midonet.cluster.models.Neutron.NeutronPort.{DeviceOwner, ExtraDhcpOpts}
import org.midonet.cluster.models.Neutron._
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.NeutronTranslatorManager._
import org.midonet.cluster.services.c3po.translators.L2GatewayConnectionTranslator.l2gwNetworkPortId
import org.midonet.cluster.services.c3po.translators.PortManager._
import org.midonet.cluster.services.c3po.translators.VpnServiceTranslator._
import org.midonet.cluster.util.DhcpUtil.asRichDhcp
import org.midonet.cluster.util.UUIDUtil.{asRichProtoUuid, fromProto, toProto}
import org.midonet.cluster.util.IPSubnetUtil.{fromAddr, univSubnet4}
import org.midonet.cluster.util._
import org.midonet.midolman.simulation.Bridge
import org.midonet.packets.{ARP, IPv4Addr, MAC}
import org.midonet.util.Range
import org.midonet.util.concurrent.toFutureOps

object PortTranslator {
    private def egressChainName(portId: UUID) =
        "OS_PORT_" + UUIDUtil.fromProto(portId) + "_INBOUND"

    private def ingressChainName(portId: UUID) =
        "OS_PORT_" + UUIDUtil.fromProto(portId) + "_OUTBOUND"

    private def antiSpoofChainName(portId: UUID) =
        "OS_PORT_" + UUIDUtil.fromProto(portId) + "_ANTI_SPOOF"

    private[c3po] def antiSpoofChainJumpRuleId(portId: UUID) =
        portId.xorWith(0x4bcef582eeae45acL, 0xb0304fc7e7b3ba0dL)
}

class PortTranslator(protected val storage: ReadOnlyStorage,
                     protected val stateTableStorage: StateTableStorage,
                     sequenceDispenser: SequenceDispenser)
        extends Translator[NeutronPort]
                with ChainManager with PortManager with RouteManager with RuleManager
                with StateTableManager {
    import BgpPeerTranslator._
    import RouterInterfaceTranslator._
    import org.midonet.cluster.services.c3po.translators.PortTranslator._

    /* Neutron does not maintain the back reference to the Floating IP, so we
     * need to do that by ourselves. */
    override protected def retainHighLevelModel(tx: Transaction,
                                                op: Operation[NeutronPort])
    : List[Operation[NeutronPort]] = {
        op match {
            case Create(nm) => List(Create(nm))
            case Update(nm, _) => List(Update(nm, NeutronPortUpdateValidator))
            case Delete(clazz, id) => List(Delete(clazz, id))
        }
    }

    override protected def translateCreate(tx: Transaction,
                                           nPort: NeutronPort): OperationList = {
        // Floating IPs and ports on uplink networks have no
        // corresponding Midonet port.
        if (isFloatingIpPort(nPort) || isOnUplinkNetwork(nPort)) return List()

        val midoOps = new OperationListBuffer
        if (hasMacAndArpTableEntries(nPort))
            midoOps ++= macAndArpTableEntryPaths(nPort).map(CreateNode(_, null))

        // Remote site ports have the MAC/ARP entries, but no Midonet port.
        if (isRemoteSitePort(nPort))
            return midoOps.toList

        // All other ports have a corresponding Midonet network (bridge) port.
        val midoPortBldr = translateNeutronPort(nPort)

        assignTunnelKey(midoPortBldr, sequenceDispenser)

        val portId = nPort.getId
        val portContext = initPortContext
        if (!isTrustedPort(nPort)) {
            // Generate in/outbound chain IDs from Port ID.
            midoPortBldr.setInboundFilterId(inChainId(portId))
            midoPortBldr.setOutboundFilterId(outChainId(portId))
        }
        updateSecurityBindings(nPort, None, midoPortBldr, portContext)
        if (isVifPort(nPort)) {
            // Add new DHCP host entries
            updateDhcpEntries(nPort,
                              portContext.midoDhcps,
                              addDhcpHost)
        } else if (isDhcpPort(nPort)) {
            updateDhcpEntries(nPort,
                              portContext.midoDhcps,
                              addDhcpServer)
            midoOps ++= configureMetaDataService(nPort)
        }

        addMidoOps(portContext, midoOps)
        midoOps += Create(midoPortBldr.build)
        midoOps.toList
    }

    override protected def translateDelete(tx: Transaction,
                                           nPort: NeutronPort)
    : OperationList = {
        // Nothing to do for floating IPs, since we didn't create
        // anything.
        if (isFloatingIpPort(nPort)) return List()

        val midoOps = new OperationListBuffer

        // No corresponding Midonet port for ports on uplink networks.
        if (isOnUplinkNetwork(nPort)) {
            // We don't create a corresponding Midonet network port for Neutron
            // ports on uplink networks, but for router interface ports, we do
            // need to delete the corresponding router port.
            if (isRouterInterfacePort(nPort)) {
                return List(Delete(classOf[Port],
                                   routerInterfacePortPeerId(nPort.getId)))
            } else {
                return List()
            }
        }

        if (hasMacAndArpTableEntries(nPort))
            midoOps ++= macAndArpTableEntryPaths(nPort).map(DeleteNode)

        // No corresponding Midonet port for the remote site port.
        if (isRemoteSitePort(nPort))
            return midoOps.toList

        midoOps += Delete(classOf[Port], nPort.getId)

        if (isRouterGatewayPort(nPort)) {
            // Delete the router port.
            val rPortId = RouterTranslator.tenantGwPortId(nPort.getId)
            midoOps += Delete(classOf[Port], rPortId)

            // Delete the SNAT rules if they exist.
            midoOps ++= deleteRouterSnatRulesOps(rPortId)
        } else if (isRouterInterfacePort(nPort)) {
            // Delete the SNAT rules on the tenant router referencing the port
            val rtr = storage.get(classOf[Router],
                                  toProto(nPort.getDeviceId)).await()
            val peerPortId = routerInterfacePortPeerId(nPort.getId)
            midoOps += Delete(classOf[Rule],
                              sameSubnetSnatRuleId(rtr.getInboundFilterId,
                                                   peerPortId))
            midoOps += Delete(classOf[Rule],
                              sameSubnetSnatRuleId(rtr.getOutboundFilterId,
                                                   peerPortId))

            // Delete the peer router port.
            midoOps += Delete(classOf[Port], peerPortId)

            val bgpNetId = bgpNetworkId(nPort.getId)
            if (rtr.getBgpNetworkIdsList.contains(bgpNetId)) {
                midoOps += Delete(classOf[BgpNetwork], bgpNetId)
            }
        }

        val portContext = initPortContext
        if (!isTrustedPort(nPort)) {
            portContext.chains ++= deleteSecurityChainsOps(nPort.getId)
            portContext.updatedIpAddrGrps ++=
                removeIpsFromIpAddrGroupsOps(nPort)
        }
        if (isVifPort(nPort)) { // It's a VIF port.
            // Delete old DHCP host entries
            updateDhcpEntries(nPort,
                              portContext.midoDhcps,
                              delDhcpHost)
            // Delete the ARP entries for associated Floating IP.
            midoOps ++= deleteFloatingIpArpEntries(nPort)
        } else if (isDhcpPort(nPort)) {
            updateDhcpEntries(nPort,
                              portContext.midoDhcps,
                              delDhcpServer,
                              // DHCP may have already been deleted.
                              ignoreNonExistingDhcp = true)
            midoOps ++= deleteMetaDataServiceRoute(nPort)
        }
        addMidoOps(portContext, midoOps)

        midoOps.toList
    }

    private def macAndArpTableEntryPaths(nPort: NeutronPort)
    : Seq[String] = {
        val portId = if (isRemoteSitePort(nPort)) {
            l2gwNetworkPortId(nPort.getNetworkId)
        } else nPort.getId
        val macPath = stateTableStorage.bridgeMacEntryPath(
            nPort.getNetworkId, 0, MAC.fromString(nPort.getMacAddress), portId)
        if (nPort.getFixedIpsCount == 0) {
            Seq(macPath)
        } else {
            Seq(macPath, arpEntryPath(nPort))
        }
    }

    override protected def translateUpdate(tx: Transaction,
                                           nPort: NeutronPort): OperationList = {
        // If the equivalent Midonet port doesn't exist, then it's either a
        // floating IP or port on an uplink network. In either case, we
        // don't create anything in the Midonet topology for this Neutron port,
        // so there's nothing to update.
        if(!storage.exists(classOf[Port], nPort.getId).await()) return List()

        val midoOps = new OperationListBuffer
        val mPortOps = new OperationListBuffer

        val portId = nPort.getId
        val oldNPort = storage.get(classOf[NeutronPort], nPort.getId).await()

        val newOwner = nPort.getDeviceOwner
        val oldOwner = oldNPort.getDeviceOwner
        if (newOwner != oldOwner && Set(newOwner, oldOwner).exists(
            !Set(DeviceOwner.COMPUTE,
                 DeviceOwner.ROUTER_INTERFACE).contains(_))) {
            throw new UnsupportedOperationException(
                "device_owner update from ${oldOwner} to ${newOwner}"
                + "isn't supported")
        }

        val oldMPort = storage.get(classOf[Port], portId).await()

        // It is assumed that the fixed IPs assigned to a Neutron Port will not
        // be changed.
        val mPort = if (portChanged(nPort, oldNPort)) {
            val bldr = oldMPort.toBuilder.setAdminStateUp(nPort.getAdminStateUp)

            if (isTrustedPort(nPort)) {
                bldr.clearInboundFilterId()
                bldr.clearOutboundFilterId()
            } else {
                // Generate in/outbound chain IDs from Port ID.
                bldr.setInboundFilterId(inChainId(portId))
                bldr.setOutboundFilterId(outChainId(portId))
            }

            // Neutron may specify binding information for router interface
            // ports on edge routers. For VIF/DHCP ports, binding information
            // is controlled by mm-ctl, and we shouldn't change it.
            if (nPort.getDeviceOwner != oldNPort.getDeviceOwner) {
                bldr.clearHostId().clearInterfaceName()
            }
            if (isRouterInterfacePort(nPort)) {
                if (hasBinding(nPort)) {
                    bldr.setHostId(getHostIdByName(nPort.getHostId))
                    bldr.setInterfaceName(nPort.getProfile.getInterfaceName)
                } else {
                    bldr.clearHostId().clearInterfaceName()
                }
            }

            mPortOps += Update(bldr.build())
            bldr
        } else {
            oldMPort
        }

        // router gateway ports only allow one ip address
        // only update the rules if that ip changed
        if (isRouterGatewayPort(nPort) &&
            (nPort.getFixedIpsList ne oldNPort.getFixedIpsList)) {
            val router = storage.get(classOf[Router], toProto(nPort.getDeviceId)).await()
            if (router.getVpnServiceIdsCount > 0) {
                // Update the vpn services associated to the router with the
                // new external ip.
                val vpnServices = storage.getAll(
                    classOf[VpnService], router.getVpnServiceIdsList.asScala)
                    .await()

                // No need to consider the case where there are no fixed ips.
                // Neutron does not allow this case.
                val oldExternalIp = oldNPort.getFixedIps(0).getIpAddress
                val newExternalIp = nPort.getFixedIps(0).getIpAddress
                midoOps ++= vpnServices.map(vpn => Update(
                    vpn.toBuilder.setExternalIp(newExternalIp).build))

                // Update the local redirect rules
                val chainId = router.getLocalRedirectChainId
                val chain = storage.get(classOf[Chain], chainId).await()
                val rules = storage.getAll(classOf[Rule], chain.getRuleIdsList.asScala).await()
                var rulesToUpdate = filterVpnRedirectRules(oldExternalIp, rules)
                rulesToUpdate = updateVpnRedirectRules(newExternalIp, rulesToUpdate)
                midoOps ++= rulesToUpdate.map(Update(_))
            }
        }

        val portContext = initPortContext
        updateSecurityBindings(nPort, Some(oldNPort), mPort, portContext)

        if (isVifPort(nPort)) { // It's a VIF port.
            // Delete old DHCP host entries
            updateDhcpEntries(oldNPort,
                              portContext.midoDhcps,
                              delDhcpHost)
            // Add new DHCP host entries
            updateDhcpEntries(nPort,
                              portContext.midoDhcps,
                              addDhcpHost)
        } else if (isVifPort(oldNPort)) {
            // Delete old DHCP host entries
            updateDhcpEntries(oldNPort,
                              portContext.midoDhcps,
                              delDhcpHost)
            // Delete the ARP entries for associated Floating IP.
            midoOps ++= deleteFloatingIpArpEntries(oldNPort)
        }
        if (hasMacAndArpTableEntries(nPort) ||
            hasMacAndArpTableEntries(oldNPort)) {
            midoOps ++= macTableUpdateOps(oldNPort, nPort)
            midoOps ++= arpTableUpdateOps(oldNPort, nPort)
        }
        addMidoOps(portContext, midoOps)
        midoOps ++= mPortOps

        midoOps ++= fipArpTableUpdateOps(oldMPort, oldNPort, nPort)

        // TODO if a DHCP port, assert that the fixed IPs haven't changed.

        // Adds support of fixed_ips update operation for
        // device_owner network:router_interface
        if (isRouterInterfacePort(nPort) &&
                (nPort.getFixedIpsList != oldNPort.getFixedIpsList))
            midoOps ++= routerInterfacePortFixedIpsUpdateOps(nPort)

        midoOps.toList
    }

    /* A container class holding context associated with a Neutron Port CRUD. */
    private case class PortContext(
            midoDhcps: mutable.Map[UUID, Dhcp.Builder],
            inRules: ListBuffer[Operation[Rule]],
            outRules: ListBuffer[Operation[Rule]],
            antiSpoofRules: ListBuffer[Operation[Rule]],
            chains: ListBuffer[Operation[Chain]],
            updatedIpAddrGrps: ListBuffer[Operation[IPAddrGroup]])

    private def initPortContext =
        PortContext(mutable.Map[UUID, Dhcp.Builder](),
                    ListBuffer[Operation[Rule]](),
                    ListBuffer[Operation[Rule]](),
                    ListBuffer[Operation[Rule]](),
                    ListBuffer[Operation[Chain]](),
                    ListBuffer[Operation[IPAddrGroup]]())


    private def addMidoOps(portContext: PortContext,
                           midoOps: OperationListBuffer) {
            midoOps ++= portContext.midoDhcps.values.map(d => Update(d.build))
            midoOps ++= portContext.chains
            midoOps ++= portContext.inRules
            midoOps ++= portContext.outRules
            midoOps ++= portContext.antiSpoofRules
            midoOps ++= portContext.updatedIpAddrGrps
    }

    /**
     * Builds an an update operation list for port, routers and SNAT rules
     * when fixed Ip address is changed for router interface port.
     */
    private def routerInterfacePortFixedIpsUpdateOps(nPort: NeutronPort)
    : OperationList = {
        val ops = new OperationListBuffer

        // Update port
        val portAddress = nPort.getFixedIps(0).getIpAddress
        val rtrPortId = PortManager.routerInterfacePortPeerId(nPort.getId)

        val rtrPort = storage.get(classOf[Port], rtrPortId).await()
        val rtrPortBldr = rtrPort.toBuilder.setPortAddress(portAddress)

        ops += Update(rtrPortBldr.build())

        // Update routes
        val ns = storage.get(classOf[NeutronSubnet],
            nPort.getFixedIps(0).getSubnetId).await()
        val rtrInterfaceRouteId = RouteManager.routerInterfaceRouteId(
            rtrPortId)
        val localRoute = newLocalRoute(rtrPortId, portAddress)
        val rifRoute = newNextHopPortRoute(nextHopPortId = rtrPortId,
            id = rtrInterfaceRouteId,
            srcSubnet = univSubnet4,
            dstSubnet = ns.getCidr)
        ops += Update(rifRoute)
        ops += Update(localRoute)

        // Update rules
        if (!isOnUplinkNetwork(nPort)) {
            val snatRuleId = sameSubnetSnatRuleId(
                outChainId(toProto(nPort.getDeviceId)), rtrPortId)
            val revSnatRuleId = sameSubnetSnatRuleId(
                inChainId(toProto(nPort.getDeviceId)), rtrPortId)
            val Seq(snatRule, revSnatRule) = storage.getAll(
                classOf[Rule], Seq(snatRuleId, revSnatRuleId)).await()

            val snatRuleBldr = snatRule.toBuilder
            snatRuleBldr.getNatRuleDataBuilder.getNatTargetsBuilder(0)
                .setNwStart(portAddress).setNwEnd(portAddress)

            val revSnatRuleBldr = revSnatRule.toBuilder
            revSnatRuleBldr.getConditionBuilder.setNwDstIp(
                fromAddr(portAddress))

            ops += Update(snatRuleBldr.build())
            ops += Update(revSnatRuleBldr.build())
        }
        ops.toList
    }

    // There's no binding unless both hostId and interfaceName are set.
    private def hasBinding(np: NeutronPort): Boolean =
        np.hasHostId && np.hasProfile && np.getProfile.hasInterfaceName

    /**
     * Returns true if a port has changed in a way relevant to a port update,
     * i.e. whether the admin state or host binding has changed.
     */
    private def portChanged(newPort: NeutronPort,
                            curPort: NeutronPort): Boolean = {
        if (newPort.getAdminStateUp != curPort.getAdminStateUp ||
            newPort.getDeviceOwner != curPort.getDeviceOwner ||
            hasBinding(newPort) != hasBinding(curPort)) return true

        hasBinding(newPort) &&
            (newPort.getHostId != curPort.getHostId ||
             newPort.getProfile.getInterfaceName !=
             curPort.getProfile.getInterfaceName)
    }

    /* Adds a host entry with the given mac / IP address pair to DHCP. */
    private def addDhcpHost(
            dhcp: Dhcp.Builder, mac: String, ipAddr: IPAddress,
            extraDhcpOpts: JList[ExtraDhcpOpts]) {
        val dhcpHost = dhcp.addHostsBuilder()
              .setMac(mac)
              .setIpAddress(ipAddr)
        for (opt <- extraDhcpOpts.asScala) {
            dhcpHost.addExtraDhcpOptsBuilder()
                  .setName(opt.getOptName)
                  .setValue(opt.getOptValue)
        }
    }

    /* Configures the DHCP server and an OPT 121 route to it with the given IP
     * address (the mac is actually not being used here). */
    private def addDhcpServer(dhcp: Dhcp.Builder, mac: String,
                              ipAddr: IPAddress,
                              opts: JList[ExtraDhcpOpts])
    : Unit = if (dhcp.isIpv4) {
        dhcp.setServerAddress(ipAddr)
        val opt121 = dhcp.addOpt121RoutesBuilder()
        opt121.setDstSubnet(RouteManager.META_DATA_SRVC)
              .setGateway(ipAddr)
    }

    /* Removes the DHCP server and OPT 121 route configurations with the given
     * IP address from DHCP (the mac is actually not being used here). */
    private def delDhcpServer(dhcp: Dhcp.Builder, mac: String,
                              nextHopGw: IPAddress,
                              opts: JList[ExtraDhcpOpts])
    : Unit = if (dhcp.isIpv4) {
        if (dhcp.hasDefaultGateway) {
            dhcp.setServerAddress(dhcp.getDefaultGateway)
        } else {
            dhcp.clearServerAddress()
        }
        val route = dhcp.getOpt121RoutesOrBuilderList.asScala
                        .indexWhere(isMetaDataSvrOpt121Route(_, nextHopGw))
        if (route >= 0) dhcp.removeOpt121Routes(route)
    }

    /* Builds the list of rules that implement "anti-spoofing". The rules
     * allowed are:
     *     - DHCP
     *     - All MAC/IPs associated with the port's fixed IPs.
     *     - All MAC/IPs listed in the port's Allowed Address pair list.
     */
    private def buildAntiSpoofRules(portCtx: PortContext,
                                    nPort: NeutronPort,
                                    portId: UUID,
                                    inChainId: UUID): Unit = {

        val mac = nPort.getMacAddress
        val spoofChainId = antiSpoofChainId(portId)

        portCtx.inRules += Create(
            jumpRuleWithId(antiSpoofChainJumpRuleId(portId),
                           inChainId, spoofChainId))

        // Don't filter DHCP
        val dhcpFrom = RangeUtil.toProto(new Range[Integer](68, 68))
        val dhcpTo = RangeUtil.toProto(new Range[Integer](67, 67))

        portCtx.antiSpoofRules += Create(returnRule(spoofChainId)
            .setCondition(anyFragCondition.setTpDst(dhcpTo).setTpSrc(dhcpFrom))
            .build())

        for (fixedIp <- nPort.getFixedIpsList.asScala) {
            addIpMacPair(portCtx.antiSpoofRules, spoofChainId,
                         IPSubnetUtil.fromAddr(fixedIp.getIpAddress), mac)
        }

        for (ipAddr <- nPort.getAllowedAddressPairsList.asScala) {
            addIpMacPair(portCtx.antiSpoofRules, spoofChainId,
                         ipAddr.getIpAddress, ipAddr.getMacAddress)
        }

        portCtx.antiSpoofRules += Create(dropRuleBuilder(spoofChainId)
                                             .setCondition(anyFragCondition)
                                             .build())
    }

    private def addIpMacPair(antiSpoofRules: ListBuffer[Operation[Rule]],
                             spoofChainId: UUID,
                             ip: IPSubnet, mac: String) = {
        antiSpoofRules += Create(returnRule(spoofChainId)
            .setCondition(anyFragCondition.setDlType(ARP.ETHERTYPE)
                                            .setNwSrcIp(ip))  // ARP.SPA
            .build())
        antiSpoofRules += Create(returnRule(spoofChainId)
            .setCondition(anyFragCondition.setNwSrcIp(ip).setDlSrc(mac))
            .build())
    }

    private def addPortIpToIpAddrGrp(ipAddrGrp: IPAddrGroup.Builder,
                                     ip: IPAddress,
                                     portId: UUID): Unit = {
        ipAddrGrp.getIpAddrPortsBuilderList.asScala.find(_.getIpAddress == ip) match {
            case Some(ipPort) => ipPort.addPortIds(portId)
            case None => ipAddrGrp.addIpAddrPortsBuilder()
                                  .setIpAddress(ip)
                                  .addPortIds(portId)
        }
    }

    private def removePortIpFromIpAddrGrp(ipAddrGroup: IPAddrGroup.Builder,
                                          ip: IPAddress,
                                          portId: UUID): Unit = {
        ipAddrGroup.getIpAddrPortsBuilderList.asScala.find(_.getIpAddress == ip) match {
            case Some(ipPort) =>
                val idx = ipPort.getPortIdsList.indexOf(portId)
                if (idx >= 0) ipPort.removePortIds(idx)
                if (ipPort.getPortIdsCount == 0) {
                    val ipx = ipAddrGroup.getIpAddrPortsBuilderList.asScala.indexOf(ipPort)
                    if (ipx >= 0) ipAddrGroup.removeIpAddrPorts(ipx)
                }
            case None =>
        }
    }

    private def updatePortSgAssociations(nPort: NeutronPort,
                                         nPortOld: Option[NeutronPort],
                                         portCtx: PortContext): Unit = {
        def getFixedIps(nPort: NeutronPort) = {
            if (isTrustedPort(nPort)) {
                Seq()
            } else {
                nPort.getFixedIpsList.asScala.map(_.getIpAddress)
            }
        }

        def getSecurityGroups(nPort: NeutronPort) = {
            if (isTrustedPort(nPort)) {
                Seq()
            } else {
                nPort.getSecurityGroupsList.asScala
            }
        }

        val newIps = getFixedIps(nPort)
        val newSgs = getSecurityGroups(nPort)

        val oldIps = nPortOld.toList.flatMap(getFixedIps)
        val oldSgs = nPortOld.toList.flatMap(getSecurityGroups)

        val removedSgs = oldSgs diff newSgs
        val addedSgs = newSgs diff oldSgs
        val keptSgs = newSgs diff addedSgs

        val removedIps = oldIps diff newIps
        val addedIps = newIps diff oldIps

        def updateSgIpAddrs(sgIds: Seq[UUID], ipsToAdd: Seq[IPAddress],
                            ipsToRemove: Seq[IPAddress]): Unit = {
            for (iag <- storage.getAll(classOf[IPAddrGroup], sgIds).await()) {
                val iagBldr = iag.toBuilder
                for (ip <- ipsToAdd)
                    addPortIpToIpAddrGrp(iagBldr, ip, nPort.getId)
                for (ip <- ipsToRemove)
                    removePortIpFromIpAddrGrp(iagBldr, ip, nPort.getId)
                portCtx.updatedIpAddrGrps += Update(iagBldr.build())
            }
        }

        // Remove all port/ips from the removed SGs
        updateSgIpAddrs(removedSgs, Seq(), oldIps)

        // Add all new port/ips to new SGs
        updateSgIpAddrs(addedSgs, newIps, Seq())

        // For all the SGs that have not changed, removed the IPs that have
        // been removed, and add the IPs that have been added to the port.
        updateSgIpAddrs(keptSgs, addedIps, removedIps)
    }

    private def buildSecurityGroupJumpRules(nPort: NeutronPort,
                                            portCtx: PortContext,
                                            inChainId: UUID,
                                            outChainId: UUID): Unit = {
        // Add jump rules to corresponding inbound / outbound chains of IP
        // Address Groups (Neutron's Security Groups) that the port belongs to.
        for (sgId <- nPort.getSecurityGroupsList.asScala) {
            val ipAddrGrp = storage.get(classOf[IPAddrGroup], sgId).await()
            // Jump rules to inbound / outbound chains of IP Address Groups
            portCtx.inRules += Create(jumpRule(inChainId,
                                               ipAddrGrp.getInboundChainId))
            portCtx.outRules += Create(jumpRule(outChainId,
                                                ipAddrGrp.getOutboundChainId))
        }

        // Drop non-ARP traffic that wasn't accepted by earlier rules.
        portCtx.inRules += Create(dropRuleBuilder(inChainId)
                                  .setCondition(anyFragCondition
                                                    .setDlType(ARP.ETHERTYPE)
                                                    .setInvDlType(true))
                                   .build)
        portCtx.outRules += Create(dropRuleBuilder(outChainId)
                                   .setCondition(anyFragCondition
                                                     .setDlType(ARP.ETHERTYPE)
                                                     .setInvDlType(true))
                                   .build)
    }

    /* Create chains, rules and IP Address Groups associated with nPort. */
    private def updateSecurityBindings(nPort: NeutronPort,
                                       nPortOld: Option[NeutronPort],
                                       mPort: PortOrBuilder,
                                       portCtx: PortContext) {
        val portId = nPort.getId
        val inChainId = mPort.getInboundFilterId
        val outChainId = mPort.getOutboundFilterId
        val antiSpfChainId = antiSpoofChainId(portId)

        // If this is an update, clear the chains. Ideally we would be a bit
        // smarter about this and only delete rules that actually need deleting,
        // but this is simpler.
        if (nPortOld.exists(!isTrustedPort(_))) {
            if (isTrustedPort(nPort)) {
                portCtx.chains ++= deleteSecurityChainsOps(portId)
            } else {
                portCtx.inRules ++= deleteRulesOps(
                    storage.get(classOf[Chain], inChainId).await())
                portCtx.outRules ++= deleteRulesOps(
                    storage.get(classOf[Chain], outChainId).await())
                portCtx.antiSpoofRules ++= deleteRulesOps(
                    storage.get(classOf[Chain], antiSpfChainId).await())
            }
        } else if (!isTrustedPort(nPort)) {
            // Create in/outbound chains.
            val inChain = newChain(inChainId, egressChainName(portId))
            val outChain = newChain(outChainId, ingressChainName(portId))
            val antiSpoofChain = newChain(
                antiSpfChainId, antiSpoofChainName(portId),
                jumpRuleIds = Seq(antiSpoofChainJumpRuleId(portId)))

            portCtx.chains += (
                Create(inChain), Create(outChain), Create(antiSpoofChain))
        }

        updatePortSgAssociations(nPort, nPortOld, portCtx)

        // Add new rules if appropriate.
        if (!isTrustedPort(nPort) && nPort.getPortSecurityEnabled) {

            buildAntiSpoofRules(portCtx, nPort, portId, inChainId)

            // Create return flow rules.
            portCtx.outRules += Create(returnFlowRule(outChainId))

            // Create return flow rules matching for inbound chain.
            portCtx.inRules += Create(returnFlowRule(inChainId))

            buildSecurityGroupJumpRules(nPort, portCtx, inChainId, outChainId)
        }
    }

    /* If the first fixed IP address is configured with a gateway IP address,
     * create a route to Meta Data Service.*/
    private def configureMetaDataService(nPort: NeutronPort): OperationList =
        findGateway(nPort).toList.map { gateway =>
            val route = newMetaDataServiceRoute(
                srcSubnet = gateway.nextHopDhcp.getSubnetAddress,
                nextHopPortId = gateway.peerRouterPortId,
                nextHopGw = gateway.nextHop)
            Create(route)
        }

    /* Deletes the meta data service route for the deleted Neutron Port if the
     * first fixed IP address is configured with a gateway IP address.*/
    private def deleteMetaDataServiceRoute(nPort: NeutronPort): OperationList = {
        val gateway = findGateway(nPort, ignoreDeletedDhcp = true)
                      .getOrElse(return List())
        val port = storage.get(classOf[Port], gateway.peerRouterPortId).await()
        val routeIds = port.getRouteIdsList.asScala
        val routes = storage.getAll(classOf[Route], routeIds).await()
        val route = routes.find(isMetaDataSvrRoute(_, gateway.nextHop))
        route.toList.map(r => Delete(classOf[Route], r.getId))
    }

    /* The next hop gateway context for Neutron Port. */
    private case class Gateway(nextHop: IPAddress, nextHopDhcp: Dhcp,
                               peerRouterPortId: UUID)

    /* Find gateway router & router port configured with the port's fixed IP. */
    private def findGateway(
            nPort: NeutronPort, ignoreDeletedDhcp: Boolean = false)
    : Option[Gateway] = if (nPort.getFixedIpsCount == 0) None else {
        val nextHopGateway = nPort.getFixedIps(0).getIpAddress
        val nextHopGatewaySubnetId = nPort.getFixedIps(0).getSubnetId

        val someDhcp: Option[Dhcp] = try {
            Option(storage.get(classOf[Dhcp], nextHopGatewaySubnetId).await())
        } catch {
            case nfe: NotFoundException if ignoreDeletedDhcp => None
        }
        someDhcp filter(_.hasRouterIfPortId) map { dhcp =>
            Gateway(nextHopGateway, dhcp, dhcp.getRouterIfPortId)
        }
    }

    private def translateNeutronPort(nPort: NeutronPort): Port.Builder = {
        val bldr = Port.newBuilder.setId(nPort.getId)
            .setNetworkId(nPort.getNetworkId)
            .setAdminStateUp(nPort.getAdminStateUp)

        if (hasBinding(nPort)) {
            bldr.setHostId(getHostIdByName(nPort.getHostId))
            bldr.setInterfaceName(nPort.getProfile.getInterfaceName)
        }

        bldr
    }

    private def macTableUpdateOps(oldPort: NeutronPort,
                                  newPort: NeutronPort): OperationList = {
        val ops = new OperationListBuffer
        if (hasMacAndArpTableEntries(newPort) !=
            hasMacAndArpTableEntries(oldPort) ||
            newPort.getMacAddress != oldPort.getMacAddress) {
            if (hasMacAndArpTableEntries(oldPort) && oldPort.hasMacAddress)
                ops += DeleteNode(macEntryPath(oldPort))
            if (hasMacAndArpTableEntries(newPort) && newPort.hasMacAddress)
                ops += CreateNode(macEntryPath(newPort))
        }
        ops.toList
    }

    private def arpTableUpdateOps(oldPort: NeutronPort,
                                  newPort: NeutronPort): OperationList = {
        // TODO: Support multiple fixed IPs.
        val oldIp = if (oldPort.getFixedIpsCount == 0) null
                    else oldPort.getFixedIps(0).getIpAddress.getAddress
        val newIp = if (newPort.getFixedIpsCount == 0) null
                    else newPort.getFixedIps(0).getIpAddress.getAddress

        val ops = new OperationListBuffer
        if (oldIp != newIp) {
            if (oldIp != null) ops += DeleteNode(arpEntryPath(oldPort))
            if (newIp != null) ops += CreateNode(arpEntryPath(newPort))
        }
        ops.toList
    }

    private def macEntryPath(nPort: NeutronPort): String = {
        stateTableStorage.bridgeMacEntryPath(
            nPort.getNetworkId, Bridge.UntaggedVlanId,
            MAC.fromString(nPort.getMacAddress), nPort.getId)
    }

    /*
     * Return a list of operations to update the entries in ARP tables that are
     * related of FIPs, and that are impacted by a change of MAC in the FIP's
     * gateway port that invalidates them.
     */
    private def fipArpTableUpdateOps(mPort: PortOrBuilder, oldPort: NeutronPort,
                                     newPort: NeutronPort): OperationList = {
        if (mPort.getFipNatRuleIdsCount == 0 ||
            newPort.getMacAddress == oldPort.getMacAddress)
            return List()

        /*
         * In order to get all the impacted entries the list of NAT rules of the
         * port is checked and from them we obtain the FIP, since the gateway
         * port has no direct back reference to the FIPs it is serving,
         *
         * The FIP ID can be obtained from the both SNAT/DNAT rules in a
         * deterministic from each one. Both are checked in case only one of
         * them is present, so duplicates in FIP ID list must be ignored
         */
        val natRuleIds = mPort.getFipNatRuleIdsList.asScala
        val natRules = storage.getAll(classOf[Rule], natRuleIds).await()
        val fipIds = for (natRule <- natRules) yield {
            val natRuleData = natRule.getNatRuleData
            if (natRuleData.getDnat) {
                RouteManager.fipDnatRuleId(natRule.getId)
            } else {
                RouteManager.fipSnatRuleId(natRule.getId)
            }
        }

        val fips = storage.getAll(classOf[FloatingIp], fipIds.distinct).await()
        fips.toList.flatMap { fip =>
            List(DeleteNode(fipArpEntryPath(oldPort, fip)),
                 CreateNode(fipArpEntryPath(newPort, fip)))
        }
    }

    private def arpEntryPath(nPort: NeutronPort): String = {
        stateTableStorage.bridgeArpEntryPath(
            nPort.getNetworkId,
            IPv4Addr(nPort.getFixedIps(0).getIpAddress.getAddress),
            MAC.fromString(nPort.getMacAddress))
    }

    private def fipArpEntryPath(nPort: NeutronPort, fip: FloatingIp): String = {
        stateTableStorage.bridgeArpEntryPath(
            nPort.getNetworkId,
            IPv4Addr(fip.getFloatingIpAddress.getAddress),
            MAC.fromString(nPort.getMacAddress))
    }

    /* Returns a Buffer of DeleteNode ops */
    private def deleteFloatingIpArpEntries(nPort: NeutronPort) = {
        for (fipId <- nPort.getFloatingIpIdsList.asScala) yield {
            // The following could be improved by possibly caching
            // Neutron Router and Port. But ZOOM internally caches objects,
            // therefore in practice it's OK?
            val fip = storage.get(classOf[FloatingIp], fipId).await()
            val router = storage.get(classOf[NeutronRouter],
                                     fip.getRouterId).await()
            val gwPort = storage.get(classOf[NeutronPort],
                                     router.getGwPortId).await()
            val arpPath = stateTableStorage.bridgeArpEntryPath(
                gwPort.getNetworkId,
                IPv4Addr(fip.getFloatingIpAddress.getAddress),
                MAC.fromString(gwPort.getMacAddress))
            DeleteNode(arpPath)
        }
    }

    private def deleteRouterSnatRulesOps(rPortId: UUID): OperationList = {
        import RouterTranslator._
        val rPort = storage.get(classOf[Port], rPortId).await()
        val rtrId = rPort.getRouterId

        // The rules should all exist, or none.
        if (!storage.exists(classOf[Rule], outSnatRuleId(rtrId)).await()) List()
        else List(Delete(classOf[Rule], outSnatRuleId(rtrId)),
                  Delete(classOf[Rule], outDropUnmatchedFragmentsRuleId(rtrId)),
                  Delete(classOf[Rule], inReverseSnatRuleId(rtrId)),
                  Delete(classOf[Rule], inDropWrongPortTrafficRuleId(rtrId)))
    }
}

private[translators] object NeutronPortUpdateValidator
        extends UpdateValidator[NeutronPort] {
    override def validate(oldNPort: NeutronPort, newNPort: NeutronPort)
    : NeutronPort = {
        if (oldNPort.getFloatingIpIdsCount > 0)
            newNPort.toBuilder
                    .addAllFloatingIpIds(oldNPort.getFloatingIpIdsList).build()
        else newNPort
    }
}

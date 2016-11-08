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
import org.midonet.cluster.services.c3po.translators.BgpPeerTranslator._
import org.midonet.cluster.services.c3po.translators.L2GatewayConnectionTranslator.l2gwNetworkPortId
import org.midonet.cluster.services.c3po.translators.PortManager._
import org.midonet.cluster.services.c3po.translators.PortTranslator._
import org.midonet.cluster.services.c3po.translators.RouterInterfaceTranslator._
import org.midonet.cluster.services.c3po.translators.VpnServiceTranslator._
import org.midonet.cluster.util.DhcpUtil.asRichDhcp
import org.midonet.cluster.util.IPSubnetUtil.{fromAddr, univSubnet4}
import org.midonet.cluster.util.UUIDUtil.{asRichProtoUuid, fromProto, toProto}
import org.midonet.cluster.util._
import org.midonet.midolman.simulation.Bridge
import org.midonet.packets.{ARP, IPv4Addr, MAC}
import org.midonet.util.Range

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

class PortTranslator(stateTableStorage: StateTableStorage,
                     sequenceDispenser: SequenceDispenser)
    extends Translator[NeutronPort]
        with ChainManager with PortManager with RouteManager with RuleManager {

    /**
      *  Neutron does not maintain the back reference to the Floating IP, so we
      * need to do that by ourselves.
      */
    override protected def retainHighLevelModel(tx: Transaction,
                                                op: Operation[NeutronPort])
    : List[Operation[NeutronPort]] = {
        op match {
            case Create(nm) => List(Create(nm))
            case Update(nm, _) => List(Update(nm, NeutronPortUpdateValidator))
            case Delete(clazz, id) => List(Delete(clazz, id))
        }
    }

    /**
      * @see [[Translator.translateCreate()]]
      */
    override protected def translateCreate(tx: Transaction, nPort: NeutronPort)
    : Unit = {
        // Floating IPs and ports on uplink networks have no
        // corresponding Midonet port.
        if (isFloatingIpPort(nPort) || isOnUplinkNetwork(tx, nPort)) {
            return
        }

        if (hasMacAndArpTableEntries(nPort)) {
            macAndArpTableEntryPaths(nPort).foreach(tx.createNode(_))
        }

        // Remote site ports have the MAC/ARP entries, but no Midonet port.
        if (isRemoteSitePort(nPort)) {
            return
        }

        // All other ports have a corresponding Midonet network (bridge) port.
        val midoPortBldr = translateNeutronPort(tx, nPort)

        assignTunnelKey(midoPortBldr, sequenceDispenser)

        val portId = nPort.getId
        val portContext = initPortContext
        if (!isTrustedPort(nPort)) {
            // Generate in/outbound chain IDs from Port ID.
            midoPortBldr.setInboundFilterId(inChainId(portId))
            midoPortBldr.setOutboundFilterId(outChainId(portId))
        }
        updateSecurityBindings(tx, nPort, None, midoPortBldr, portContext)
        if (isVifPort(nPort)) {
            // Add new DHCP host entries
            updateDhcpEntries(tx, nPort, portContext.midoDhcps, addDhcpHost,
                              ignoreNonExistingDhcp = false)
        } else if (isDhcpPort(nPort)) {
            updateDhcpEntries(tx, nPort, portContext.midoDhcps, addDhcpServer,
                              ignoreNonExistingDhcp = false)
            configureMetaDataService(tx, nPort)
        }

        addContextOps(tx, portContext)
        tx.create(midoPortBldr.build)
    }

    /**
      * @see [[Translator.translateDelete()]]
      */
    override protected def translateDelete(tx: Transaction, nPort: NeutronPort)
    : Unit = {
        // Nothing to do for floating IPs, since we didn't create
        // anything.
        if (isFloatingIpPort(nPort)) {
            return
        }

        // No corresponding Midonet port for ports on uplink networks.
        if (isOnUplinkNetwork(tx, nPort)) {
            // We don't create a corresponding Midonet network port for Neutron
            // ports on uplink networks, but for router interface ports, we do
            // need to delete the corresponding router port.
            if (isRouterInterfacePort(nPort)) {
                tx.delete(classOf[Port], routerInterfacePortPeerId(nPort.getId),
                          ignoresNeo = true)
                return
            } else {
                return
            }
        }

        if (hasMacAndArpTableEntries(nPort)) {
            for (path <- macAndArpTableEntryPaths(nPort)) {
                tx.deleteNode(path, idempotent = true)
            }
        }

        // No corresponding Midonet port for the remote site port.
        if (isRemoteSitePort(nPort)) {
            return
        }

        tx.delete(classOf[Port], nPort.getId, ignoresNeo = true)

        if (isRouterGatewayPort(nPort)) {
            val rPortId = RouterTranslator.tenantGwPortId(nPort.getId)

            // Delete the SNAT rules if they exist.
            deleteRouterSnatRules(tx, rPortId)

            // Delete the router port.
            tx.delete(classOf[Port], rPortId, ignoresNeo = true)
        } else if (isRouterInterfacePort(nPort)) {
            // Delete the SNAT rules on the tenant router referencing the port
            val rtr = tx.get(classOf[Router], toProto(nPort.getDeviceId))
            val peerPortId = routerInterfacePortPeerId(nPort.getId)
            tx.delete(classOf[Rule],
                      sameSubnetSnatRuleId(rtr.getInboundFilterId, peerPortId),
                      ignoresNeo = true)
            tx.delete(classOf[Rule],
                      sameSubnetSnatRuleId(rtr.getOutboundFilterId, peerPortId),
                      ignoresNeo = true)

            // Delete the peer router port.
            tx.delete(classOf[Port], peerPortId, ignoresNeo = true)

            val bgpNetId = bgpNetworkId(nPort.getId)
            if (rtr.getBgpNetworkIdsList.contains(bgpNetId)) {
                tx.delete(classOf[BgpNetwork], bgpNetId, ignoresNeo = true)
            }
        }

        val portContext = initPortContext
        if (!isTrustedPort(nPort)) {
            portContext.chains ++= deleteSecurityChainsOps(nPort.getId)
            portContext.updatedIpAddrGrps ++=
                removeIpsFromIpAddrGroupsOps(tx, nPort)
        }
        if (isVifPort(nPort)) { // It's a VIF port.
            // Delete old DHCP host entries
            updateDhcpEntries(tx, nPort, portContext.midoDhcps, delDhcpHost,
                              ignoreNonExistingDhcp = false)
            // Delete the ARP entries for associated Floating IP.
            deleteFloatingIpArpEntries(tx, nPort)
        } else if (isDhcpPort(nPort)) {
            updateDhcpEntries(tx, nPort, portContext.midoDhcps, delDhcpServer,
                              // DHCP may have already been deleted.
                              ignoreNonExistingDhcp = true)
            deleteMetaDataServiceRoute(tx, nPort)
        }
        addContextOps(tx, portContext)
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

    /**
      * @see [[Translator.translateUpdate()]]
      */
    override protected def translateUpdate(tx: Transaction, nPort: NeutronPort)
    : Unit = {
        // If the equivalent Midonet port doesn't exist, then it's either a
        // floating IP or port on an uplink network. In either case, we
        // don't create anything in the Midonet topology for this Neutron port,
        // so there's nothing to update.
        if(!tx.exists(classOf[Port], nPort.getId)) {
            return
        }

        var mPortOp: Option[Operation[Port]] = None

        val portId = nPort.getId
        val oldNPort = tx.get(classOf[NeutronPort], nPort.getId)

        val newOwner = nPort.getDeviceOwner
        val oldOwner = oldNPort.getDeviceOwner
        if (newOwner != oldOwner && Set(newOwner, oldOwner).exists(
            !Set(DeviceOwner.COMPUTE,
                 DeviceOwner.ROUTER_INTERFACE).contains(_))) {
            throw new UnsupportedOperationException(
                "device_owner update from ${oldOwner} to ${newOwner}"
                + "isn't supported")
        }

        val oldMPort = tx.get(classOf[Port], portId)

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
                    bldr.setHostId(getHostIdByName(tx, nPort.getHostId))
                    bldr.setInterfaceName(nPort.getProfile.getInterfaceName)
                } else {
                    bldr.clearHostId().clearInterfaceName()
                }
            }

            val port = bldr.build()
            mPortOp = Some(Update(port))
            port
        } else {
            oldMPort
        }

        // router gateway ports only allow one ip address
        // only update the rules if that ip changed
        if (isRouterGatewayPort(nPort) &&
            (nPort.getFixedIpsList != oldNPort.getFixedIpsList)) {
            val router = tx.get(classOf[Router], toProto(nPort.getDeviceId))
            if (router.getVpnServiceIdsCount > 0) {
                // Update the vpn services associated to the router with the
                // new external ip.
                val vpnServices = tx.getAll(classOf[VpnService],
                                            router.getVpnServiceIdsList.asScala)

                // No need to consider the case where there are no fixed ips.
                // Neutron does not allow this case.
                val oldExternalIp = oldNPort.getFixedIps(0).getIpAddress
                val newExternalIp = nPort.getFixedIps(0).getIpAddress
                for (vpnService <- vpnServices) {
                    tx.update(vpnService.toBuilder.setExternalIp(newExternalIp)
                                      .build())
                }

                // Update the local redirect rules
                val chainId = router.getLocalRedirectChainId
                val chain = tx.get(classOf[Chain], chainId)
                val rules = tx.getAll(classOf[Rule], chain.getRuleIdsList.asScala)
                var rulesToUpdate = filterVpnRedirectRules(oldExternalIp, rules)
                rulesToUpdate = updateVpnRedirectRules(newExternalIp, rulesToUpdate)
                for (rule <- rulesToUpdate) {
                    tx.update(rule)
                }
            }
        }

        val portContext = initPortContext
        updateSecurityBindings(tx, nPort, Some(oldNPort), mPort, portContext)

        if (isVifPort(nPort)) { // It's a VIF port.
            // Delete old DHCP host entries
            updateDhcpEntries(tx, oldNPort, portContext.midoDhcps, delDhcpHost,
                              ignoreNonExistingDhcp = false)
            // Add new DHCP host entries
            updateDhcpEntries(tx, nPort, portContext.midoDhcps, addDhcpHost,
                              ignoreNonExistingDhcp = false)
        } else if (isVifPort(oldNPort)) {
            // Delete old DHCP host entries
            updateDhcpEntries(tx, oldNPort, portContext.midoDhcps, delDhcpHost,
                              ignoreNonExistingDhcp = false)
            // Delete the ARP entries for associated Floating IP.
            deleteFloatingIpArpEntries(tx, oldNPort)
        }
        if (hasMacAndArpTableEntries(nPort) ||
            hasMacAndArpTableEntries(oldNPort)) {
            updateMacTable(tx, oldNPort, nPort)
            updateArpTable(tx, oldNPort, nPort)
        }
        addContextOps(tx, portContext)

        if (mPortOp.isDefined) {
            mPortOp.get.apply(tx)
        }

        fipArpTableUpdateOps(tx, oldMPort, oldNPort, nPort)

        // TODO if a DHCP port, assert that the fixed IPs haven't changed.

        // Adds support of fixed_ips update operation for
        // device_owner network:router_interface
        if (isRouterInterfacePort(nPort) &&
                (nPort.getFixedIpsList != oldNPort.getFixedIpsList)) {
            routerInterfacePortFixedIpsUpdateOps(tx, nPort)
        }
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


    private def addContextOps(tx: Transaction, portContext: PortContext): Unit = {
        portContext.midoDhcps.values.foreach(d => tx.update(d.build))
        portContext.chains.foreach(_.apply(tx))
        portContext.inRules.foreach(_.apply(tx))
        portContext.outRules.foreach(_.apply(tx))
        portContext.antiSpoofRules.foreach(_.apply(tx))
        portContext.updatedIpAddrGrps.foreach(_.apply(tx))
    }

    /**
      * Builds an an update operation list for port, routers and SNAT rules
      * when fixed IP address is changed for router interface port.
      */
    private def routerInterfacePortFixedIpsUpdateOps(tx: Transaction,
                                                     nPort: NeutronPort)
    : Unit = {
        // Update port
        val portAddress = nPort.getFixedIps(0).getIpAddress
        val rtrPortId = PortManager.routerInterfacePortPeerId(nPort.getId)

        val rtrPort = tx.get(classOf[Port], rtrPortId)
        val rtrPortBldr = rtrPort.toBuilder.setPortAddress(portAddress)

        tx.update(rtrPortBldr.build())

        // Update routes
        val ns = tx.get(classOf[NeutronSubnet], nPort.getFixedIps(0).getSubnetId)
        val rtrInterfaceRouteId = RouteManager.routerInterfaceRouteId(
            rtrPortId)
        val localRoute = newLocalRoute(rtrPortId, portAddress)
        val rifRoute = newNextHopPortRoute(nextHopPortId = rtrPortId,
            id = rtrInterfaceRouteId,
            srcSubnet = univSubnet4,
            dstSubnet = ns.getCidr)
        tx.update(rifRoute)
        tx.update(localRoute)

        // Update rules
        if (!isOnUplinkNetwork(tx, nPort)) {
            val snatRuleId = sameSubnetSnatRuleId(
                outChainId(toProto(nPort.getDeviceId)), rtrPortId)
            val revSnatRuleId = sameSubnetSnatRuleId(
                inChainId(toProto(nPort.getDeviceId)), rtrPortId)
            val Seq(snatRule, revSnatRule) =
                tx.getAll(classOf[Rule], Seq(snatRuleId, revSnatRuleId))

            val snatRuleBldr = snatRule.toBuilder
            snatRuleBldr.getNatRuleDataBuilder.getNatTargetsBuilder(0)
                .setNwStart(portAddress).setNwEnd(portAddress)

            val revSnatRuleBldr = revSnatRule.toBuilder
            revSnatRuleBldr.getConditionBuilder.setNwDstIp(
                fromAddr(portAddress))

            tx.update(snatRuleBldr.build())
            tx.update(revSnatRuleBldr.build())
        }
    }

    /**
      * There's no binding unless both hostId and interfaceName are set.
      */
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

    /**
      * Adds a host entry with the given mac / IP address pair to DHCP.
      */
    private def addDhcpHost(dhcp: Dhcp.Builder, mac: String, ipAddr: IPAddress,
                            extraDhcpOpts: JList[ExtraDhcpOpts])
    : Unit = {
        val dhcpHost = dhcp.addHostsBuilder()
              .setMac(mac)
              .setIpAddress(ipAddr)
        for (opt <- extraDhcpOpts.asScala) {
            dhcpHost.addExtraDhcpOptsBuilder()
                  .setName(opt.getOptName)
                  .setValue(opt.getOptValue)
        }
    }

    /**
      * Configures the DHCP server and an OPT 121 route to it with the given IP
      * address (the mac is actually not being used here).
      */
    private def addDhcpServer(dhcp: Dhcp.Builder, mac: String,
                              ipAddr: IPAddress,
                              opts: JList[ExtraDhcpOpts])
    : Unit = if (dhcp.isIpv4) {
        dhcp.setServerAddress(ipAddr)
        val opt121 = dhcp.addOpt121RoutesBuilder()
        opt121.setDstSubnet(RouteManager.META_DATA_SRVC)
              .setGateway(ipAddr)
    }

    /**
      * Removes the DHCP server and OPT 121 route configurations with the given
      * IP address from DHCP (the mac is actually not being used here).
      */
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

    /**
      * Builds the list of rules that implement "anti-spoofing". The rules
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

    private def updatePortSgAssociations(tx: Transaction,
                                         nPort: NeutronPort,
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
            for (iag <- tx.getAll(classOf[IPAddrGroup], sgIds)) {
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

    private def buildSecurityGroupJumpRules(tx: Transaction,
                                            nPort: NeutronPort,
                                            portCtx: PortContext,
                                            inChainId: UUID,
                                            outChainId: UUID): Unit = {
        // Add jump rules to corresponding inbound / outbound chains of IP
        // Address Groups (Neutron's Security Groups) that the port belongs to.
        for (sgId <- nPort.getSecurityGroupsList.asScala) {
            val ipAddrGrp = tx.get(classOf[IPAddrGroup], sgId)
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

    /**
      * Create chains, rules and IP Address Groups associated with nPort.
      */
    private def updateSecurityBindings(tx: Transaction,
                                       nPort: NeutronPort,
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
                    tx.get(classOf[Chain], inChainId))
                portCtx.outRules ++= deleteRulesOps(
                    tx.get(classOf[Chain], outChainId))
                portCtx.antiSpoofRules ++= deleteRulesOps(
                    tx.get(classOf[Chain], antiSpfChainId))
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

        updatePortSgAssociations(tx, nPort, nPortOld, portCtx)

        // Add new rules if appropriate.
        if (!isTrustedPort(nPort) && nPort.getPortSecurityEnabled) {

            buildAntiSpoofRules(portCtx, nPort, portId, inChainId)

            // Create return flow rules.
            portCtx.outRules += Create(returnFlowRule(outChainId))

            // Create return flow rules matching for inbound chain.
            portCtx.inRules += Create(returnFlowRule(inChainId))

            buildSecurityGroupJumpRules(tx, nPort, portCtx, inChainId,
                                        outChainId)
        }
    }

    /**
      * If the first fixed IP address is configured with a gateway IP address,
      * create a route to Meta Data Service.
      */
    private def configureMetaDataService(tx: Transaction,
                                         nPort: NeutronPort): Unit = {
        val gateway = findGateway(tx, nPort).getOrElse(return)
        val route = newMetaDataServiceRoute(
            srcSubnet = gateway.nextHopDhcp.getSubnetAddress,
            nextHopPortId = gateway.peerRouterPortId,
            nextHopGw = gateway.nextHop)
        tx.create(route)
    }

    /**
      * Deletes the meta data service route for the deleted Neutron Port if the
      * first fixed IP address is configured with a gateway IP address.
      */
    private def deleteMetaDataServiceRoute(tx: Transaction,
                                           nPort: NeutronPort): Unit = {
        val gateway =
            findGateway(tx, nPort, ignoreDeletedDhcp = true).getOrElse(return)
        val port = tx.get(classOf[Port], gateway.peerRouterPortId)
        val routeIds = port.getRouteIdsList.asScala
        val routes = tx.getAll(classOf[Route], routeIds)
        val route = routes.find(isMetaDataSvrRoute(_, gateway.nextHop))
        if (route.isDefined) {
            tx.delete(classOf[Route], route.get.getId, ignoresNeo = true)
        }
    }

    /**
      * The next hop gateway context for Neutron Port.
      */
    private case class Gateway(nextHop: IPAddress, nextHopDhcp: Dhcp,
                               peerRouterPortId: UUID)

    /**
      * Find gateway router & router port configured with the port's fixed IP.
      */
    private def findGateway(tx: Transaction, nPort: NeutronPort,
                            ignoreDeletedDhcp: Boolean = false)
    : Option[Gateway] = if (nPort.getFixedIpsCount == 0) None else {
        val nextHopGateway = nPort.getFixedIps(0).getIpAddress
        val nextHopGatewaySubnetId = nPort.getFixedIps(0).getSubnetId

        val someDhcp: Option[Dhcp] = try {
            Option(tx.get(classOf[Dhcp], nextHopGatewaySubnetId))
        } catch {
            case nfe: NotFoundException if ignoreDeletedDhcp => None
        }
        someDhcp filter(_.hasRouterIfPortId) map { dhcp =>
            Gateway(nextHopGateway, dhcp, dhcp.getRouterIfPortId)
        }
    }

    private def translateNeutronPort(tx: Transaction,
                                     nPort: NeutronPort): Port.Builder = {
        val bldr = Port.newBuilder.setId(nPort.getId)
            .setNetworkId(nPort.getNetworkId)
            .setAdminStateUp(nPort.getAdminStateUp)

        if (hasBinding(nPort)) {
            bldr.setHostId(getHostIdByName(tx, nPort.getHostId))
            bldr.setInterfaceName(nPort.getProfile.getInterfaceName)
        }

        bldr
    }

    private def updateMacTable(tx: Transaction,
                               oldPort: NeutronPort,
                               newPort: NeutronPort): Unit = {
        if (hasMacAndArpTableEntries(newPort) !=
            hasMacAndArpTableEntries(oldPort) ||
            newPort.getMacAddress != oldPort.getMacAddress) {
            if (hasMacAndArpTableEntries(oldPort) && oldPort.hasMacAddress)
                tx.deleteNode(macEntryPath(oldPort))
            if (hasMacAndArpTableEntries(newPort) && newPort.hasMacAddress)
                tx.createNode(macEntryPath(newPort))
        }
    }

    private def updateArpTable(tx: Transaction,
                               oldPort: NeutronPort,
                               newPort: NeutronPort): Unit = {
        // TODO: Support multiple fixed IPs.
        val oldIp = if (oldPort.getFixedIpsCount == 0) null
                    else oldPort.getFixedIps(0).getIpAddress.getAddress
        val newIp = if (newPort.getFixedIpsCount == 0) null
                    else newPort.getFixedIps(0).getIpAddress.getAddress

        if (oldIp != newIp) {
            if (oldIp != null) tx.deleteNode(arpEntryPath(oldPort))
            if (newIp != null) tx.createNode(arpEntryPath(newPort))
        }
    }

    private def macEntryPath(nPort: NeutronPort): String = {
        stateTableStorage.bridgeMacEntryPath(
            nPort.getNetworkId, Bridge.UntaggedVlanId,
            MAC.fromString(nPort.getMacAddress), nPort.getId)
    }

    /**
      * Return a list of operations to update the entries in ARP tables that are
      * related of FIPs, and that are impacted by a change of MAC in the FIP's
      * gateway port that invalidates them.
      */
    private def fipArpTableUpdateOps(tx: Transaction, mPort: PortOrBuilder,
                                     oldPort: NeutronPort,
                                     newPort: NeutronPort): Unit = {
        if (mPort.getFipNatRuleIdsCount == 0 ||
            newPort.getMacAddress == oldPort.getMacAddress) {
            return
        }

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
        val natRules = tx.getAll(classOf[Rule], natRuleIds)
        val fipIds = for (natRule <- natRules) yield {
            val natRuleData = natRule.getNatRuleData
            if (natRuleData.getDnat) {
                RouteManager.fipDnatRuleId(natRule.getId)
            } else {
                RouteManager.fipSnatRuleId(natRule.getId)
            }
        }

        for (fip <- tx.getAll(classOf[FloatingIp], fipIds.distinct)) {
            tx.deleteNode(fipArpEntryPath(oldPort, fip))
            tx.createNode(fipArpEntryPath(newPort, fip))
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

    /**
      * Returns a Buffer of DeleteNode ops
      */
    private def deleteFloatingIpArpEntries(tx: Transaction,
                                           nPort: NeutronPort): Unit = {
        for (fipId <- nPort.getFloatingIpIdsList.asScala) {
            // The following could be improved by possibly caching
            // Neutron Router and Port. But ZOOM internally caches objects,
            // therefore in practice it's OK?
            val fip = tx.get(classOf[FloatingIp], fipId)
            val router = tx.get(classOf[NeutronRouter], fip.getRouterId)
            val gwPort = tx.get(classOf[NeutronPort], router.getGwPortId)
            val arpPath = stateTableStorage.bridgeArpEntryPath(
                gwPort.getNetworkId,
                IPv4Addr(fip.getFloatingIpAddress.getAddress),
                MAC.fromString(gwPort.getMacAddress))
            tx.deleteNode(arpPath)
        }
    }

    private def deleteRouterSnatRules(tx: Transaction, rPortId: UUID): Unit = {
        import RouterTranslator._
        if (!tx.exists(classOf[Port], rPortId)) {
            return
        }
        val rPort = tx.get(classOf[Port], rPortId)
        val rtrId = rPort.getRouterId

        // The rules should all exist, or none.
        if (tx.exists(classOf[Rule], outSnatRuleId(rtrId))) {
            tx.delete(classOf[Rule], outSnatRuleId(rtrId),
                      ignoresNeo = true)
            tx.delete(classOf[Rule], inReverseSnatRuleId(rtrId),
                      ignoresNeo = true)
            tx.delete(classOf[Rule], skipSnatGwPortRuleId(rtrId),
                      ignoresNeo = true)
            tx.delete(classOf[Rule], dstRewrittenSnatRuleId(rtrId),
                      ignoresNeo = true)

            // old translation
            tx.delete(classOf[Rule], inDropWrongPortTrafficRuleId(rtrId),
                      ignoresNeo = true)
            tx.delete(classOf[Rule], outDropUnmatchedFragmentsRuleId(rtrId),
                      ignoresNeo = true)
        }
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

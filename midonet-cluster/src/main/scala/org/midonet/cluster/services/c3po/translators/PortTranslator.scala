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

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

import org.midonet.cluster.data.storage.{NotFoundException, ReadOnlyStorage, UpdateValidator}
import org.midonet.cluster.models.Commons.{IPAddress, UUID}
import org.midonet.cluster.models.Neutron.{FloatingIp, NeutronPort, NeutronRouter}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.C3POStorageManager.{Create, Delete, Operation, Update}
import org.midonet.cluster.services.c3po.midonet.{CreateNode, DeleteNode}
import org.midonet.cluster.services.c3po.translators.PortManager._
import org.midonet.cluster.util.DhcpUtil.asRichDhcp
import org.midonet.cluster.util.UUIDUtil.{asRichProtoUuid, fromProto, toProto}
import org.midonet.cluster.util._
import org.midonet.midolman.state.PathBuilder
import org.midonet.packets.ARP
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
                     protected val pathBldr: PathBuilder,
                     sequenceDispenser: SequenceDispenser)
        extends Translator[NeutronPort]
                with ChainManager with PortManager with RouteManager with RuleManager
                with BridgeStateTableManager {
    import RouterInterfaceTranslator._
    import org.midonet.cluster.services.c3po.translators.PortTranslator._

    /* Neutron does not maintain the back reference to the Floating IP, so we
     * need to do that by ourselves. */
    override protected def retainHighLevelModel(op: Operation[NeutronPort])
    : List[Operation[NeutronPort]] = {
        op match {
            case Create(nm) => List(Create(nm))
            case Update(nm, _) => List(Update(nm, NeutronPortUpdateValidator))
            case Delete(clazz, id) => List(Delete(clazz, id))
        }
    }

    override protected def translateCreate(nPort: NeutronPort): OperationList = {
        // Floating IPs, VIPs, and ports on uplink networks have no
        // corresponding Midonet port.
        if (isVipPort(nPort) || isFloatingIpPort(nPort) ||
            isOnUplinkNetwork(nPort)) return List()

        // All other ports have a corresponding Midonet network (bridge) port.
        val midoPortBldr = translateNeutronPort(nPort)

        assignTunnelKey(midoPortBldr, sequenceDispenser)

        val portId = nPort.getId
        val midoOps = new OperationListBuffer
        val portContext = initPortContext
        if (isVifPort(nPort)) {
            // Generate in/outbound chain IDs from Port ID.
            midoPortBldr.setInboundFilterId(inChainId(portId))
            midoPortBldr.setOutboundFilterId(outChainId(portId))

            // Add MAC->port map entry.
            midoOps += CreateNode(macEntryPath(nPort.getNetworkId,
                                               nPort.getMacAddress,
                                               nPort.getId))
            // Add an ARP entry.
            // TODO: Support multiple fixed IPs.
            if (nPort.getFixedIpsCount > 0) {
                val fixedIp = nPort.getFixedIps(0).getIpAddress.getAddress
                midoOps += CreateNode(arpEntryPath(nPort.getNetworkId,
                                                   fixedIp,
                                                   nPort.getMacAddress))
            }

            // Add new DHCP host entries
            updateDhcpEntries(nPort,
                              portContext.midoDhcps,
                              addDhcpHost)
            updateSecurityBindings(nPort, null, midoPortBldr, portContext)
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

    override protected def translateDelete(id: UUID): OperationList = {
        val nPort = storage.get(classOf[NeutronPort], id).await()

        // Nothing to do for floating IPs or VIPs, since we didn't create
        // anything.
        if (isVipPort(nPort) || isFloatingIpPort(nPort)) return List()

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

        midoOps += Delete(classOf[Port], id)

        if (isRouterGatewayPort(nPort)) {
            // Delete the router port.
            val rPortId = RouterTranslator.tenantGwPortId(id)
            midoOps += Delete(classOf[Port], rPortId)

            // Delete the SNAT rules if they exist.
            midoOps ++= deleteRouterSnatRulesOps(rPortId)

            // Delete the ARP entry in the external network if it exists.
            midoOps ++= deleteArpEntry(nPort)
        } else if (isRouterInterfacePort(nPort)) {
            // Update any routes using this port as a gateway.
            val subnetId = nPort.getFixedIps(0).getSubnetId
            midoOps ++= updateGatewayRoutesOps(null, subnetId)

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
        }

        val portContext = initPortContext
        if (isVifPort(nPort)) { // It's a VIF port.
            val mPort = storage.get(classOf[Port], id).await()
            // Delete old DHCP host entries
            updateDhcpEntries(nPort,
                              portContext.midoDhcps,
                              delDhcpHost)
            deleteSecurityBindings(nPort, mPort, portContext)
            midoOps += DeleteNode(pathBldr.getBridgeMacPortEntryPath(nPort))
            // Delete the ARP entry for this port.
            midoOps ++= deleteArpEntry(nPort)
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

    override protected def translateUpdate(nPort: NeutronPort): OperationList = {
        // If the equivalent Midonet port doesn't exist, then it's either a
        // floating IP, VIP,  or port on an uplink network. In either case, we
        // don't create anything in the Midonet topology for this Neutron port,
        // so there's nothing to update.
        if(!storage.exists(classOf[Port], nPort.getId).await()) return List()

        val midoOps = new OperationListBuffer

        // It is assumed that the fixed IPs assigned to a Neutron Port will not
        // be changed.
        val portId = nPort.getId
        val mPort = storage.get(classOf[Port], portId).await()
        val oldNPort = storage.get(classOf[NeutronPort], portId).await()
        if (portChanged(nPort, oldNPort)) {
            val bldr = mPort.toBuilder.setAdminStateUp(nPort.getAdminStateUp)

            // Neutron may specify binding information for router interface
            // ports on edge routers. For VIF/DHCP ports, binding information
            // is controlled by mm-ctl, and we shouldn't change it.
            if (isRouterInterfacePort(nPort)) {
                if (hasBinding(nPort)) {
                    bldr.setHostId(getHostIdByName(nPort.getHostId))
                    bldr.setInterfaceName(nPort.getProfile.getInterfaceName)
                } else {
                    bldr.clearHostId().clearInterfaceName()
                }
            }

            midoOps += Update(bldr.build())
        }

        if (isVifPort(nPort)) { // It's a VIF port.
            val portContext = initPortContext
            // Delete old DHCP host entries
            updateDhcpEntries(oldNPort,
                              portContext.midoDhcps,
                              delDhcpHost)
            // Add new DHCP host entries
            updateDhcpEntries(nPort,
                              portContext.midoDhcps,
                              addDhcpHost)
            updateSecurityBindings(nPort, oldNPort, mPort, portContext)
            addMidoOps(portContext, midoOps)
            midoOps ++= macTableUpdateOps(oldNPort, nPort)
            midoOps ++= arpTableUpdateOps(oldNPort, nPort)
        }

        // TODO if a DHCP port, assert that the fixed IPs haven't changed.

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
            hasBinding(newPort) != hasBinding(curPort)) return true

        hasBinding(newPort) &&
            (newPort.getHostId != curPort.getHostId ||
             newPort.getProfile.getInterfaceName !=
             curPort.getProfile.getInterfaceName)
    }

    /* Adds a host entry with the given mac / IP address pair to DHCP. */
    private def addDhcpHost(
            dhcp: Dhcp.Builder, mac: String, ipAddr: IPAddress) {
        dhcp.addHostsBuilder()
            .setMac(mac)
            .setIpAddress(ipAddr)
    }

    /* Configures the DHCP server and an OPT 121 route to it with the given IP
     * address (the mac is actually not being used here). */
    private def addDhcpServer(dhcp: Dhcp.Builder, mac: String,
                              ipAddr: IPAddress): Unit = if (dhcp.isIpv4) {
        dhcp.setServerAddress(ipAddr)
        val opt121 = dhcp.addOpt121RoutesBuilder()
        opt121.setDstSubnet(RouteManager.META_DATA_SRVC)
              .setGateway(ipAddr)
    }

    /* Removes the DHCP server and OPT 121 route configurations with the given
     * IP address from DHCP (the mac is actually not being used here). */
    private def delDhcpServer(dhcp: Dhcp.Builder, mac: String,
                              nextHopGw: IPAddress): Unit = if (dhcp.isIpv4) {
        dhcp.clearServerAddress()
        val route = dhcp.getOpt121RoutesOrBuilderList.asScala
                        .indexWhere(isMetaDataSvrOpt121Route(_, nextHopGw))
        if (route >= 0) dhcp.removeOpt121Routes(route)
    }

    /* Builds the list of rules that implement "anti-spoofing". The rules
     * allowed are:
     *     - DHCP
     *     - ARP
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

        // Don't filter ARP
        portCtx.antiSpoofRules += Create(returnRule(spoofChainId)
            .setCondition(anyFragCondition().setDlType(ARP.ETHERTYPE))
            .build())

        // Don't filter DHCP
        val dhcpFrom = RangeUtil.toProto(new Range[Integer](68, 68))
        val dhcpTo = RangeUtil.toProto(new Range[Integer](67, 67))

        portCtx.antiSpoofRules += Create(returnRule(spoofChainId)
            .setCondition(anyFragCondition().setTpDst(dhcpTo).setTpSrc(dhcpFrom))
            .build())

        for (fixedIp <- nPort.getFixedIpsList.asScala) {
            portCtx.antiSpoofRules += Create(returnRule(spoofChainId)
                .setCondition(anyFragCondition()
                                  .setNwSrcIp(IPSubnetUtil.fromAddr(fixedIp.getIpAddress))
                                  .setDlSrc(mac))
                .build())
        }

        for (ipAddr <- nPort.getAllowedAddressPairsList.asScala) {
            portCtx.antiSpoofRules += Create(returnRule(spoofChainId)
                .setCondition(anyFragCondition().setNwSrcIp(ipAddr.getIpAddress)
                                  .setDlSrc(ipAddr.getMacAddress))
                .build())
        }

        portCtx.antiSpoofRules += Create(dropRuleBuilder(spoofChainId)
                                             .setCondition(anyFragCondition())
                                             .build())
    }

    private def buildSecurityGroupJumpRules(nPort: NeutronPort,
                                            nPortOld: NeutronPort,
                                            portCtx: PortContext,
                                            portId: UUID,
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
                    val idx = ipPorts.getPortIdsList.indexOf(portId)
                    if (idx >= 0) ipPorts.removePortIds(idx)
                } else  if (added contains ipPorts.getIpAddress) {
                    // Exclude IPs that are already in IP Address Groups.
                    ipPorts.addPortIds(portId)
                    added -= ipPorts.getIpAddress
                }
            }
            for (newIp <- added)
                updatedIpAddrG.addIpAddrPortsBuilder()
                              .setIpAddress(newIp)
                              .addPortIds(portId)
            portCtx.updatedIpAddrGrps += Update(updatedIpAddrG.build)
        }

        // Drop non-ARP traffic that wasn't accepted by earlier rules.
        portCtx.inRules += Create(dropRuleBuilder(inChainId)
                                  .setCondition(anyFragCondition()
                                                    .setDlType(ARP.ETHERTYPE)
                                                    .setInvDlType(true))
                                   .build)
        portCtx.outRules += Create(dropRuleBuilder(outChainId)
                                   .setCondition(anyFragCondition()
                                                     .setDlType(ARP.ETHERTYPE)
                                                     .setInvDlType(true))
                                   .build)
    }

    /* Create chains, rules and IP Address Groups associated with nPort. */
    private def updateSecurityBindings(nPort: NeutronPort,
                                       nPortOld: NeutronPort,
                                       mPort: PortOrBuilder,
                                       portCtx: PortContext) {
        val portId = nPort.getId
        val inChainId = mPort.getInboundFilterId
        val outChainId = mPort.getOutboundFilterId
        val antiSpfChainId = antiSpoofChainId(portId)

        // Create in/outbound chains.
        val inChain = newChain(inChainId, egressChainName(portId))
        val outChain = newChain(outChainId, ingressChainName(portId))
        val antiSpoofChain = newChain(
            antiSpfChainId, antiSpoofChainName(portId),
            jumpRuleIds = Seq(antiSpoofChainJumpRuleId(portId)))

        // If this is an update, clear the chains. Ideally we would be a bit
        // smarter about this and only delete rules that actually need deleting,
        // but this is simpler.
        if (nPortOld != null) { // Update
            portCtx.inRules ++= deleteRulesOps(
                storage.get(classOf[Chain], inChainId).await())
            portCtx.outRules ++= deleteRulesOps(
                storage.get(classOf[Chain], outChainId).await())
            portCtx.antiSpoofRules ++= deleteRulesOps(
                storage.get(classOf[Chain], antiSpfChainId).await())
        } else { // Create
            portCtx.chains += (
                Create(inChain), Create(outChain), Create(antiSpoofChain))
        }

        // Add new rules if appropriate.
        if (nPort.getPortSecurityEnabled) {

            // Create return flow rules.
            portCtx.outRules += Create(returnFlowRule(outChainId))

            // Create return flow rules matching for inbound chain.
            portCtx.inRules += Create(returnFlowRule(inChainId))

            buildAntiSpoofRules(portCtx, nPort, portId, inChainId)

            buildSecurityGroupJumpRules(nPort, nPortOld, portCtx, portId,
                                        inChainId, outChainId)
        }
    }

    /* Delete chains, rules and IP Address Groups associated with nPortOld. */
    private def deleteSecurityBindings(nPortOld: NeutronPort,
                                       mPort: Port,
                                       portContext: PortContext) {
        val portId = nPortOld.getId

        portContext.chains ++= List(
            Delete(classOf[Chain], mPort.getInboundFilterId),
            Delete(classOf[Chain], mPort.getOutboundFilterId),
            Delete(classOf[Chain], antiSpoofChainId(mPort.getId)))

        // Remove the fixed IPs from IP Address Groups
        for (sgId <- nPortOld.getSecurityGroupsList.asScala) {
            val ipAddrG = storage.get(classOf[IPAddrGroup], sgId).await()
            val updatedIpAddrG = ipAddrG.toBuilder
            val oldIps = nPortOld.getFixedIpsList.asScala.map(_.getIpAddress)
            for (ipPorts <- updatedIpAddrG.getIpAddrPortsBuilderList.asScala) {
                if (oldIps.contains(ipPorts.getIpAddress)) {
                    val idx = ipPorts.getPortIdsList.indexOf(portId)
                    if (idx >= 0) ipPorts.removePortIds(idx)
                }
            }
            portContext.updatedIpAddrGrps += Update(updatedIpAddrG.build)
        }
    }

    /* If the first fixed IP address is configured with a gateway IP address,
     * create a route to Meta Data Service.*/
    private def configureMetaDataService(nPort: NeutronPort): OperationList =
        findGateway(nPort).toList.flatMap { gateway =>
            val route = newMetaDataServiceRoute(
                srcSubnet = gateway.nextHopDhcp.getSubnetAddress,
                nextHopPortId = gateway.peerRouterPortId,
                nextHopGw = gateway.nextHop)
            List(Create(route))
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
        if (newPort.getMacAddress != oldPort.getMacAddress) {
            if (oldPort.hasMacAddress)
                ops += DeleteNode(pathBldr.getBridgeMacPortEntryPath(oldPort))
            if (newPort.hasMacAddress)
                ops += CreateNode(pathBldr.getBridgeMacPortEntryPath(newPort))
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

    private def arpEntryPath(nPort: NeutronPort): String = {
        arpEntryPath(nPort.getNetworkId,
                     nPort.getFixedIps(0).getIpAddress.getAddress,
                     nPort.getMacAddress)
    }

    private def deleteArpEntry(nPort: NeutronPort) = {
        if (nPort.getFixedIpsCount > 0) {
            Some(DeleteNode(arpEntryPath(nPort)))
        } else None
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
            val arpPath = arpEntryPath(gwPort.getNetworkId,
                                       fip.getFloatingIpAddress.getAddress,
                                       gwPort.getMacAddress)
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

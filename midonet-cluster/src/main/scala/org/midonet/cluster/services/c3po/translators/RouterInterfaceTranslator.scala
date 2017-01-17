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

import org.midonet.cluster.ClusterConfig
import org.midonet.cluster.data.storage.Transaction
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Commons.{Condition, IPSubnet, UUID, _}
import org.midonet.cluster.models.Neutron.NeutronPort.{DeviceOwner, IPAllocation}
import org.midonet.cluster.models.Neutron._
import org.midonet.cluster.models.Topology.Rule.Action
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.NeutronTranslatorManager.Operation
import org.midonet.cluster.services.c3po.translators.PortManager.routerInterfacePortPeerId
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.cluster.util.IPSubnetUtil._
import org.midonet.cluster.util.SequenceDispenser
import org.midonet.cluster.util.SequenceDispenser.{Fip64TunnelKey, OverlayTunnelKey}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.packets.{MAC, TunnelKeys}
import org.midonet.util.concurrent.toFutureOps

object RouterInterfaceTranslator {

    case class PortAddress(subnet: IPSubnet, address: IPAddress, subnetId: UUID,
                           isGateway: Boolean)

    /**
      * Deterministically generate 'same subnet' SNAT rule ID from chain ID
      * and port ID.  'Same subnet' SNAT is a rule applied to traffic that
      * ingresses in from and egresses out of the same tenant router port,
      * without ever going to the uplink.
      */
    def sameSubnetSnatRuleId(chainId: UUID, portId: UUID) =
        chainId.xorWith(portId.getMsb, portId.getLsb)
            .xorWith(0x3bcf2eb64be211e5L, 0x84ae0242ac110003L)
}

class RouterInterfaceTranslator(sequenceDispenser: SequenceDispenser,
                                config: ClusterConfig)
    extends Translator[NeutronRouterInterface] with ChainManager
            with PortManager with RuleManager {

    import BgpPeerTranslator._
    import RouterInterfaceTranslator._

    /* NeutronRouterInterface is a binding information and has no unique ID.
     * We don't persist it in Storage. */
    override protected def retainHighLevelModel(tx: Transaction,
                                                op: Operation[NeutronRouterInterface])
    : List[Operation[NeutronRouterInterface]] = List()

    override protected def translateCreate(tx: Transaction,
                                           ri: NeutronRouterInterface): Unit = {
        // At this point, we will already have translated the task to create
        // the NeutronPort with id ri.getPortId.
        val nPort = tx.get(classOf[NeutronPort], ri.getPortId)

        // A NeutronRouterInterface is a link between a Neutron router and a
        // Neutron network, so we will need to create a Midonet port on the
        // router with ID nPort.getDeviceId. If nPort is on an uplink network,
        // then there is no corresponding Midonet network, and the router port
        // is bound to a host interface.
        val isUplink = isOnUplinkNetwork(tx, nPort)

        // Convert Neutron/network port to router interface port if it isn't
        // already one.
        // NOTE(yamamoto): This isn't necessary for Neutron 8.0.0 (Mitaka)
        // and later, because the way to update device_owner has been
        // changed.  (If0178887282456842b6078a851a9233cb58a391a)
        if (nPort.getDeviceOwner != DeviceOwner.ROUTER_INTERFACE)
            convertRouterPort(tx, nPort, isUplink, ri.getId)

        // Create a generic router port builder without IP configuration.
        val builder = createRouterPort(ri, nPort)

        // Set the port addresses.
        val addresses = addAddresses(tx, builder,
                                     ri.getSubnetIdsList.asScala,
                                     nPort, isUplink)

        val isIPv6 = addresses.exists(_.subnet.getVersion == IPVersion.V6)
        buildRouterPort(tx, builder, ri, nPort, isUplink, isIPv6)
        val routerPort = builder.build()
        tx.create(routerPort)

        for (address <- addresses) {
            setupRouterPort(tx, ri, nPort, routerPort, isUplink, address)
        }
    }

    override protected def translateDelete(tx: Transaction,
                                           ri: NeutronRouterInterface): Unit = {
        throw new IllegalArgumentException(
            "NeutronRouterInterface delete not supported.")
    }

    override protected def translateUpdate(tx: Transaction,
                                           ri: NeutronRouterInterface): Unit = {
        // If the Neutron port still exist it means that router interface
        // port is updated with router-interface-delete <router> subnet=<subnet_id>
        // command
        if (!tx.exists(classOf[NeutronPort], ri.getPortId)) {
            return
        }

        val nPort = tx.get(classOf[NeutronPort], ri.getPortId)
        val routerPortId = routerInterfacePortPeerId(nPort.getId)
        val oldRouterPort = tx.get(classOf[Port], routerPortId)
        val isUplink = isOnUplinkNetwork(tx, nPort)

        val builder = oldRouterPort.toBuilder
        builder.clearDhcpId()
        builder.clearPortAddress()
        builder.clearPortSubnet()

        val fixedIps = nPort.getFixedIpsList.asScala
        val oldSubnets = oldRouterPort.getPortSubnetList.asScala
        val removeSubnets = ri.getSubnetIdsList.asScala
        // Get ids of "current" subnets of the router interface. They might
        // differ from the fixed ips of the neutron port, if router-interface-delete
        // is called with subnet=subnet_id parameter several times.

        val keepSubnets = for (fixedIp <- fixedIps
            if oldSubnets.exists { oldSubnet =>
                oldSubnet.getAddress == fixedIp.getIpAddress.getAddress
            } && !removeSubnets.contains(fixedIp.getSubnetId) )
                yield fixedIp.getSubnetId

        addAddresses(tx, builder, keepSubnets, nPort, isUplink)
        val hasIPv6 =
            tx.getAll(classOf[NeutronSubnet], keepSubnets)
                .map(_.getCidr).exists(subnet => subnet.getVersion == IPVersion.V6)

        if (!isUplink && !hasIPv6) {
            builder.clearTunnelKey()
        }

        if (!hasIPv6) {
            builder.clearFipNatRuleIds()
        }
        tx.update(builder.build())

        // Delete routes and snat rules from ZK
        val removeFixedIps =
            for (fixedIp <- fixedIps if removeSubnets.
                contains(fixedIp.getSubnetId)) yield fixedIp

        removeFixedIps.foreach { fixedIp =>
            rollbackRouterSubnetPort(tx, ri, nPort,
                                     routerPortId,
                                     isUplink, fixedIp)
        }
    }

    private def createRouterPort(ri: NeutronRouterInterface, nPort: NeutronPort)
    : Port.Builder = {
        // The id field of a router interface is the router ID. Since a router
        // can have multiple interfaces, this doesn't uniquely identify it.
        val routerPortId = routerInterfacePortPeerId(nPort.getId)
        val routerPortMac = if (nPort.hasMacAddress) {
            nPort.getMacAddress
        } else {
            MAC.random().toString
        }

        newRouterPortBuilder(routerPortId, ri.getId,
                             adminStateUp = true)
            .setPortMac(routerPortMac)
    }

    private def addAddresses(tx: Transaction, builder: Port.Builder,
                             subnetIds: Seq[Commons.UUID], nPort: NeutronPort,
                             isUplink: Boolean): Seq[PortAddress] = {

        def subnetsContain(subnets: Seq[IPSubnet], address: Commons.IPAddress) = {
            subnets.exists(_.asJava.containsAddress(address.asIPAddress))
        }
        val subnets =
            tx.getAll(classOf[NeutronSubnet], subnetIds).map(_.getCidr)

        if (nPort.getFixedIpsCount > 0) {
            for (fixedIp <- nPort.getFixedIpsList.asScala
                 if fixedIp.hasIpAddress && fixedIp.hasSubnetId &&
                    subnetsContain(subnets, fixedIp.getIpAddress)) yield {
                addPortAddressFromFixedIp(tx, builder, fixedIp, isUplink)
            }
        } else {
            for (subnetId <- subnetIds) yield {
                addPortAddressFromSubnet(tx, builder, subnetId, isUplink)
            }
        }
    }

    private def addPortAddressFromFixedIp(tx: Transaction, builder: Port.Builder,
                                          fixedIp: IPAllocation,
                                          isUplink: Boolean): PortAddress = {
        val nSubnet = tx.get(classOf[NeutronSubnet], fixedIp.getSubnetId)

        val isGateway = nSubnet.hasGatewayIp &&
                        fixedIp.getIpAddress == nSubnet.getGatewayIp

        // If this router port is the subnet's gateway, set the DHCP id field
        // if not yet set. This permits the easy retrieval of the DHCP's gateway
        // port via a backreference, when creating a DHCP port.
        if (!isUplink && isGateway && !builder.hasDhcpId) {
            builder.setDhcpId(fixedIp.getSubnetId)
        }

        addPortAddress(tx, builder, nSubnet, fixedIp.getIpAddress,
                       isGateway = isGateway)
    }

    private def addPortAddressFromSubnet(tx: Transaction, builder: Port.Builder,
                                         subnetId: UUID,
                                         isUplink: Boolean): PortAddress = {
        val nSubnet = tx.get(classOf[NeutronSubnet], subnetId)

        if (!nSubnet.hasGatewayIp) {
            throw new IllegalArgumentException(
                "Cannot create router interface because port " +
                s"${builder.getId.asJava} has no fixed IPs and subnet " +
                s"${subnetId.asJava} has no gateway IP")
        }

        // If this router port is the subnet's gateway, set the DHCP id field
        // if not yet set. This permits the easy retrieval of the DHCP's gateway
        // port via a backreference, when creating a DHCP port.
        if (!isUplink && !builder.hasDhcpId) {
            builder.setDhcpId(subnetId)
        }

        addPortAddress(tx, builder, nSubnet, nSubnet.getGatewayIp,
                       isGateway = true)
    }

    private def addPortAddress(tx: Transaction, builder: Port.Builder,
                               nSubnet: NeutronSubnet, ipAddress: IPAddress,
                               isGateway: Boolean)
    : PortAddress = {

        val address = ipAddress.asIPAddress
        val subnet = nSubnet.getCidr.asJava

        if (!subnet.containsAddress(address)) {
            throw new IllegalArgumentException(
                s"Port address $address does not belong to subnet " +
                s"${nSubnet.getId.asJava} with CIDR $subnet")
        }

        val portSubnet = IPSubnet.newBuilder()
            .setVersion(ipAddress.getVersion)
            .setAddress(address.toString)
            .setPrefixLength(subnet.getPrefixLen)
            .build()

        builder.addPortSubnet(portSubnet)
        if (!builder.hasPortAddress) {
            builder.setPortAddress(ipAddress)
        }

        PortAddress(portSubnet, ipAddress, nSubnet.getId, isGateway)
    }

    private def setupRouterPort(tx: Transaction, ri: NeutronRouterInterface,
                                nPort: NeutronPort, routerPort: Port,
                                isUplink: Boolean, portAddress: PortAddress)
    : Unit = {
        if (portAddress.subnet.getVersion == IPVersion.V4) {
            setupRouterPort4(tx, ri, nPort, routerPort, isUplink, portAddress)
        } else {
            setupRouterPort6(tx, ri, routerPort, isUplink, portAddress)
        }
    }

    private def setupRouterPort4(tx: Transaction, ri: NeutronRouterInterface,
                                 nPort: NeutronPort, routerPort: Port,
                                 isUplink: Boolean, portAddress: PortAddress)
    : Unit = {
        if (!isUplink) {
            // Only create the metadata service route if this router interface
            // port has the DHCP's gateway IP.
            if (portAddress.isGateway) {
                createMetadataServiceRoute(tx, routerPort.getId,
                                           portAddress.address,
                                           portAddress.subnetId,
                                           portAddress.subnet)
            }

            // Add dynamic SNAT rules and the reverse SNAT on the router chains
            // so that for any traffic that was DNATed back to the same network
            // would still work by forcing it to come back to the router. One
            // such case is VIP.
            val router = tx.get(classOf[Router], ri.getId)

            createForwardSnatRule(tx, ri, router.getOutboundFilterId,
                                  routerPort.getId, portAddress.address)
            createReverseSnatRule(tx, ri, router.getInboundFilterId,
                                  routerPort.getId, portAddress.address)

            // Add a BGP network if the router is configured for interior ports
            // BGP.
            createBgpNetwork(tx, ri, nPort, routerPort, portAddress)
        }

        // Create local and port routes.
        val routerInterfaceRouteId =
            RouteManager.routerInterfaceRouteId(routerPort.getId, portAddress.address)

        val portRoute = newNextHopPortRoute(nextHopPortId = routerPort.getId,
                                            id = routerInterfaceRouteId,
                                            srcSubnet = AnyIPv4Subnet,
                                            dstSubnet = portAddress.subnet)
        val localRoute = newLocalRoute(routerPort.getId, portAddress.address)

        tx.create(portRoute)
        tx.create(localRoute)
    }

    private def setupRouterPort6(tx: Transaction, ri: NeutronRouterInterface,
                                 port: Port, isUplink: Boolean,
                                 portAddress: PortAddress)
    : Unit = {
        if (!isUplink) {
            val skipNat4Rule = Rule.newBuilder()
                .setId(floatNat64ChainId(port.getId))
                .setChainId(floatNat64ChainId(ri.getId))
                .setFipPortId(port.getId)
                .setType(Rule.Type.LITERAL_RULE)
                .setCondition(Commons.Condition.newBuilder()
                                  .addOutPortIds(port.getId)
                                  .setNwDstIp(RouterTranslator.Nat64Pool))
                .setAction(Action.ACCEPT)
                .build()
            tx.create(skipNat4Rule)
            val routerInterfaceRouteId =
                RouteManager.routerInterfaceRouteId(port.getId, portAddress.address)

            // Create port route for the NAT64 pool.
            val portRoute = newNextHopFip64Route(nextHopPortId = port.getId,
                                                 id = routerInterfaceRouteId,
                                                 srcSubnet = AnyIPv4Subnet,
                                                 dstSubnet = RouterTranslator.Nat64Pool)
            tx.create(portRoute)
        }

        // Create local route.
        val localRoute = newLocalRoute(port.getId, portAddress.address)
        tx.create(localRoute)
    }

    private def createForwardSnatRule(tx: Transaction,
                                      ri: NeutronRouterInterface,
                                      chainId: UUID, portId: UUID,
                                      portAddress: IPAddress): Unit = {

        val ruleId = sameSubnetSnatRuleId(chainId, portId)

        if (tx.exists(classOf[Rule], ruleId)) {
            throw new IllegalArgumentException(
                s"Cannot create router interface for router ${ri.getId.asJava} " +
                s"to multiple IPv4 subnets (duplicate forward SNAT rule " +
                s"${ruleId.asJava})")
        }

        val condition = Condition.newBuilder()
            .addInPortIds(portId)
            .addOutPortIds(portId)
            .setNwDstIp(RouteManager.META_DATA_SRVC)
            .setNwDstInv(true)
            .setMatchForwardFlow(true).build()

        val natTarget = natRuleData(portAddress, dnat = false,
                                    dynamic = true,
                                    config.translators.dynamicNatPortStart,
                                    config.translators.dynamicNatPortEnd)
        tx create newRule(chainId)
                .setId(ruleId)
                .setType(Rule.Type.NAT_RULE)
                .setCondition(condition)
                .setNatRuleData(natTarget)
                .setAction(Rule.Action.ACCEPT).build()
    }

    private def createReverseSnatRule(tx: Transaction,
                                      ri: NeutronRouterInterface,
                                      chainId: UUID, portId: UUID,
                                      portAddress: IPAddress): Unit = {

        val ruleId = sameSubnetSnatRuleId(chainId, portId)

        if (tx.exists(classOf[Rule], ruleId)) {
            throw new IllegalArgumentException(
                s"Cannot create router interface for router ${ri.getId.asJava} " +
                s"to multiple IPv4 subnets (duplicate reverse SNAT rule " +
                s"${ruleId.asJava})")
        }

        val cond = Condition.newBuilder()
                .addInPortIds(portId)
                .setNwDstIp(fromAddress(portAddress))
                .setMatchReturnFlow(true).build()

        val natTarget = reverseNatRuleData(dnat = false)

        tx create newRule(chainId)
            .setId(sameSubnetSnatRuleId(chainId, portId))
            .setType(Rule.Type.NAT_RULE)
            .setCondition(cond)
            .setNatRuleData(natTarget)
            .setAction(Rule.Action.ACCEPT).build()
    }

    private def createBgpNetwork(tx: Transaction, ri: NeutronRouterInterface,
                                 nPort: NeutronPort, port: Port,
                                 portAddress: PortAddress): Unit = {
        if (tx.exists(classOf[Port], quaggaPortId(ri.getId))) {
            // Trim the port subnet to the network address.
            val subnet = portAddress.subnet.toBuilder
                .setAddress(portAddress.subnet.asJava.toNetworkAddress.toString)
                .build()

            tx.create(BgpPeerTranslator.makeBgpNetwork(ri.getId, subnet,
                                                       nPort.getId))
        }
    }

    /**
      * Convert the non RIF port to a RIF port.
      */
    private def convertRouterPort(tx: Transaction, nPort: NeutronPort,
                                  isUplink: Boolean, routerId: UUID): Unit = {
        assert(nPort.getDeviceOwner != DeviceOwner.ROUTER_INTERFACE)

        tx.update(nPort.toBuilder
                      .setDeviceOwner(DeviceOwner.ROUTER_INTERFACE)
                      .setDeviceId(routerId.asJava.toString)
                      .build())

        // If it's a VIF port, remove chains from the Midonet port. Unless it's
        // on an uplink network, in which case there's no Midonet port.
        if (!isUplink && PortManager.isVifPort(nPort)) {
            deleteSecurityChains(tx, nPort.getId)
            removeIpsFromIpAddrGroupsOps(tx, nPort)

            // Delete DHCP hosts.
            val dhcps = mutable.Map[UUID, Dhcp.Builder]()
            updateDhcpEntries(tx, nPort, dhcps, delDhcpHost,
                              ignoreNonExistingDhcp = false)
            dhcps.values.map(_.build()).foreach(tx.update(_))
        }
    }

    private def createMetadataServiceRoute(tx: Transaction,
                                           routerPortId: UUID,
                                           address: IPAddress,
                                           subnetId: UUID,
                                           subnet: IPSubnet): Unit = {
        // If a DHCP port exists, add a Metadata Service Route. We can tell by
        // looking at the Dhcp's server address. If it has no associated DHCP
        // port, then its serverAdress field will be the same as its gatewayIp.
        // Otherwise it will be the address of the DHCP port.
        val dhcp = tx.get(classOf[Dhcp], subnetId)

        if (dhcp.hasServerAddress &&
            dhcp.getServerAddress != dhcp.getDefaultGateway) {
            tx.create(newMetaDataServiceRoute(subnet, routerPortId,
                                              dhcp.getServerAddress, address))
        }
    }

    private def buildRouterPort(tx: Transaction, builder: Port.Builder,
                                ri: NeutronRouterInterface, nPort: NeutronPort,
                                isUplink: Boolean, hasIPv6: Boolean): Unit = {
        if (isUplink) {
            // The port will be bound to a host rather than connected to a
            // network port. Add it to the edge router's port group.
            builder.addPortGroupIds(PortManager.portGroupId(ri.getId))

            // Set the legacy tunnel key for an uplink port.
            val tunnelKey = TunnelKeys.LegacyPortType(
                sequenceDispenser.next(OverlayTunnelKey).await())
            builder.setTunnelKey(tunnelKey)

            // Bind the port to the interface.
            builder.setHostId(getHostIdByName(tx, nPort.getHostId))
            builder.setInterfaceName(nPort.getProfile.getInterfaceName)
        } else {
            val portGroupId = ensureRouterInterfacePortGroup(tx, ri.getId)
            builder.addPortGroupIds(portGroupId)

            // Connect the router port to the network port, which has the same
            // ID as nPort.
            builder.setPeerId(nPort.getId)

            // Set the FIP64 tunnel key if the port has at least one IPv6
            // address.
            if (hasIPv6) {
                val tunnelKey = TunnelKeys.Fip64Type(
                    sequenceDispenser.next(Fip64TunnelKey).await())
                builder.setTunnelKey(tunnelKey)
            }
        }
    }

    private def rollbackRouterSubnetPort(tx: Transaction,
                                         ri: NeutronRouterInterface,
                                         nPort: NeutronPort,
                                         routerPortId: UUID,
                                         isUplink: Boolean,
                                         fixedIp: IPAllocation)
    : Unit = {
        if (fixedIp.getIpAddress.getVersion == IPVersion.V4) {
            rollbackRouterSubnetPort4(tx, ri, nPort, routerPortId,
                                      isUplink, fixedIp)
        } else {
            rollbackRouterSubnetPort6(tx, ri, routerPortId,
                                      isUplink, fixedIp)
        }
    }

    /**
      * Reverse of setupRouterPort4()
      * param routerPort: routerInterface port to update
      * param addressToRemove: subnet to remove
      */
    private def rollbackRouterSubnetPort4(tx: Transaction,
                                          ri: NeutronRouterInterface,
                                          nPort: NeutronPort,
                                          routerPortId: UUID,
                                          isUplink: Boolean,
                                          fixedIp: IPAllocation)
    : Unit = {
        if (!isUplink) {
            if (isGateway(tx, fixedIp)) {
                deleteMetadataServiceRoute(tx, routerPortId,
                                           fixedIp.getIpAddress)
            }

            val router = tx.get(classOf[Router], ri.getId)

            val outChainId = router.getOutboundFilterId
            val outRuleId = sameSubnetSnatRuleId(outChainId, routerPortId)
            tx.delete(classOf[Rule], outRuleId)

            val inChainId = router.getInboundFilterId
            val inRuleId = sameSubnetSnatRuleId(inChainId, routerPortId)
            tx.delete(classOf[Rule], inRuleId)

            val bgpNetworId = BgpPeerTranslator.bgpNetworkId(nPort.getId)
            // bgp network seems to be created for each subnet of router interface
            //   so each one, will try to remove it, but only first one succed ?
            tx.delete(classOf[BgpNetwork], bgpNetworId, ignoresNeo = true)
        }

        // Create local and port routes.
        val routerInterfaceRouteId =
            RouteManager.routerInterfaceRouteId(routerPortId,
                                                fixedIp.getIpAddress)
        tx.delete(classOf[Route], routerInterfaceRouteId)

        val localRouteId = RouteManager.localRouteId(routerPortId,
                                                     fixedIp.getIpAddress)
        tx.delete(classOf[Route], localRouteId)
    }

    /**
      * Reverse of setupRouterPort6()
      * param routerPort: routerInterface port to update
      * param addressToRemove: subnet to remove
      */
    private def rollbackRouterSubnetPort6(tx: Transaction,
                                          ri: NeutronRouterInterface,
                                          routerPortId: UUID,
                                          isUplink: Boolean,
                                          fixedIp: IPAllocation)
    : Unit = {
        if (!isUplink) {
            val routerInterfaceRouteId =
                RouteManager.routerInterfaceRouteId(routerPortId,
                                                    fixedIp.getIpAddress)

            tx.delete(classOf[Route], routerInterfaceRouteId)
        }

        val localRouteId = RouteManager.localRouteId(routerPortId,
                                                     fixedIp.getIpAddress)
        tx.delete(classOf[Route], localRouteId)
    }

    private def deleteMetadataServiceRoute(tx: Transaction,
                                           routerPortId: UUID,
                                           portAddress: IPAddress): Unit = {
        val metaDataSeriveId = RouteManager
            .metadataServiceRouteId(routerPortId, portAddress)
        tx.delete(classOf[Route], metaDataSeriveId, ignoresNeo = true)
    }

    private def deleteSkipSnat4Rule(tx: Transaction, routerPortId: UUID):
        Unit = {
        tx.delete(classOf[Rule], floatNat64ChainId(routerPortId))
    }

    private def isGateway(tx: Transaction, fixedIp: IPAllocation) = {
        val nSubnet = tx.get(classOf[NeutronSubnet], fixedIp.getSubnetId)
        nSubnet.hasGatewayIp && nSubnet.getGatewayIp == fixedIp.getIpAddress
    }
}

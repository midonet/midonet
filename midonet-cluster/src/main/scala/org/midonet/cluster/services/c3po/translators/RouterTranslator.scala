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

import org.midonet.cluster.ClusterConfig
import org.midonet.cluster.data.storage.{StateTableStorage, Transaction}
import org.midonet.cluster.models.Commons.Condition.FragmentPolicy
import org.midonet.cluster.models.Commons._
import org.midonet.cluster.models.Neutron.NeutronPort.IPAllocation
import org.midonet.cluster.models.Neutron.{NeutronPort, NeutronRouter, NeutronSubnet}
import org.midonet.cluster.models.Topology.Rule.{Action, NatTarget}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.translators.BgpPeerTranslator._
import org.midonet.cluster.services.c3po.translators.RouterInterfaceTranslator._
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.cluster.util.IPSubnetUtil
import org.midonet.cluster.util.IPSubnetUtil._
import org.midonet.cluster.util.SequenceDispenser
import org.midonet.cluster.util.SequenceDispenser.Fip64TunnelKey
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid

import org.midonet.containers
import org.midonet.packets.{ICMP, IPv4Subnet, MAC, TunnelKeys}
import org.midonet.util.concurrent.toFutureOps

class RouterTranslator(sequenceDispenser: SequenceDispenser,
                       stateTableStorage: StateTableStorage,
                       config: ClusterConfig)
    extends Translator[NeutronRouter]
    with ChainManager with PortManager with RouteManager
    with RouterManager with RuleManager {

    import RouteManager._
    import RouterTranslator._

    override protected def translateCreate(tx: Transaction,
                                           nRouter: NeutronRouter): Unit = {

        val routerBuilder = Router.newBuilder()
            .setId(nRouter.getId)
            .setInboundFilterId(inChainId(nRouter.getId))
            .setOutboundFilterId(outChainId(nRouter.getId))
            .setForwardChainId(fwdChainId(nRouter.getId))
        if (nRouter.hasTenantId) routerBuilder.setTenantId(nRouter.getTenantId)
        if (nRouter.hasName) routerBuilder.setName(nRouter.getName)
        if (nRouter.hasAdminStateUp)
            routerBuilder.setAdminStateUp(nRouter.getAdminStateUp)

        val router = routerBuilder.build()
        val inChain = newChain(router.getInboundFilterId,
                               preRouteChainName(nRouter.getId))
        val outChain = newChain(router.getOutboundFilterId,
                                postRouteChainName(nRouter.getId))
        val fwdChain = newChain(router.getForwardChainId,
                                forwardChainName(nRouter.getId))
        val floatSnatExactChain = newChain(floatSnatExactChainId(nRouter.getId),
                                           floatSnatExactChainName(nRouter.getId))
        val floatSnatChain = newChain(floatSnatChainId(nRouter.getId),
                                      floatSnatChainName(nRouter.getId))
        val skipSnatChain = newChain(skipSnatChainId(nRouter.getId),
                                     skipSnatChainName(nRouter.getId))

        // This is actually only needed for edge routers, but edge routers are
        // only defined by having an interface to an uplink network, so it's
        // simpler just to create a port group for every router than to
        // create or delete the port group when a router becomes or ceases to
        // be an edge router as interfaces are created and deleted.
        val pgId = PortManager.portGroupId(nRouter.getId)
        val portGroup = PortGroup.newBuilder.setId(pgId)
                                            .setName(portGroupName(pgId))
                                            .setTenantId(nRouter.getTenantId)
                                            .setStateful(true)
                                            .build()
        val routerInterfacePortGroup = newRouterInterfacePortGroup(
            nRouter.getId, nRouter.getTenantId).build()

        List(floatSnatExactChain, floatSnatChain, skipSnatChain,
             inChain, outChain, fwdChain, router, portGroup,
             routerInterfacePortGroup,
             jumpRule(outChain.getId, floatSnatExactChain.getId),
             jumpRule(outChain.getId, floatSnatChain.getId),
             jumpRule(outChain.getId, skipSnatChain.getId)).foreach(tx.create)

        createGatewayPort(tx, nRouter, router)
    }

    override protected def translateDelete(tx: Transaction,
                                           nRouter: NeutronRouter): Unit = {
        // ServiceContainerGroup may not exist, but delete is idempotent.
        tx.delete(classOf[Chain], inChainId(nRouter.getId), ignoresNeo = true)
        tx.delete(classOf[Chain], outChainId(nRouter.getId), ignoresNeo = true)
        tx.delete(classOf[Chain], fwdChainId(nRouter.getId), ignoresNeo = true)
        tx.delete(classOf[Chain], floatSnatExactChainId(nRouter.getId),
                  ignoresNeo = true)
        tx.delete(classOf[Chain], floatSnatChainId(nRouter.getId), ignoresNeo = true)
        tx.delete(classOf[Chain], skipSnatChainId(nRouter.getId), ignoresNeo = true)
        tx.delete(classOf[PortGroup], PortManager.portGroupId(nRouter.getId),
                  ignoresNeo = true)
        tx.delete(classOf[PortGroup],
                  PortManager.routerInterfacePortGroupId(nRouter.getId),
                  ignoresNeo = true)
        tx.delete(classOf[ServiceContainerGroup],
                  quaggaContainerGroupId(nRouter.getId), ignoresNeo = true)
        tx.delete(classOf[Router], nRouter.getId, ignoresNeo = true)
    }

    override protected def translateUpdate(tx: Transaction,
                                           nRouter: NeutronRouter): Unit = {
        checkOldRouterTranslation(tx, nRouter.getId)
        val router = tx.get(classOf[Router], nRouter.getId)

        tx.update(router.toBuilder
                      .setAdminStateUp(nRouter.getAdminStateUp)
                      .setName(nRouter.getName)
                      .setTenantId(nRouter.getTenantId)
                      .build())

        updateGatewayPort(tx, nRouter, router)
        updateExtraRoutes(tx, nRouter)
    }

    private def createGatewayPort(tx: Transaction, nRouter: NeutronRouter,
                                  router : Router): Unit = {
        if (nRouter.hasGwPortId) {

            /* There's a bit of a quirk in the translation here. We actually
             * create two linked Midonet ports for a single Neutron gateway
             * port, one on an external network, and one on the tenant router.
             * We have to do this because Neutron has no concept of router
             * ports.
             *
             * When creating a router with a gateway port, Neutron first creates
             * the gateway port in a separate transaction. That gets handled in
             * PortTranslator.translateCreate. By the time we get here, a port
             * with an ID equal the NeutronRouter's gw_port_id and the
             * link-local gateway IP address (169.254.55.1) has already been
             * added to the external network (specified by the port's
             * networkId).
             *
             * Now, when translating the Neutron Router creation or update, we
             * need to create a port on the tenant router which has the Neutron
             * gateway port's IP address and link it to the gateway port on the
             * external network. Note that the Neutron gateway port's ID goes to
             * the port on the external network, while its IP address goes to
             * the port on the tenant router.
             */
            val nPort = tx.get(classOf[NeutronPort], nRouter.getGwPortId)

            // Create the generic router port builder without IP configuration.
            val builder = createGatewayPort(tx, nRouter, router, nPort)

            val addresses = for (fixedIp <- nPort.getFixedIpsList.asScala
                                 if fixedIp.hasIpAddress &&
                                    fixedIp.hasSubnetId) yield {
                addGatewayPortAddress(tx, builder, nRouter, fixedIp)
            }

            val port = builder.build()
            tx.create(port)

            for (address <- addresses) {
                setupGatewayPort(tx, nRouter, router, port, address)
            }
        }
    }

    private def createGatewayPort(tx: Transaction, nRouter: NeutronRouter,
                                  router: Router, nPort: NeutronPort)
    : Port.Builder = {
        val routerPortId = tenantGwPortId(nRouter.getGwPortId)
        val routerPortMac = if (nPort.hasMacAddress) nPort.getMacAddress
                            else MAC.random().toString

        newRouterPortBuilder(routerPortId, nRouter.getId,
                             adminStateUp = true)
            .setPortMac(routerPortMac)
            .setPeerId(nPort.getId)
    }

    private def addGatewayPortAddress(tx: Transaction, builder: Port.Builder,
                                      nRouter: NeutronRouter,
                                      fixedIp: IPAllocation): PortAddress = {

        // Add a port address for the given subnet: the address is added to the
        // port subnet list, and only the first address is set to the port
        // address for compatibility.
        val dhcp = tx.get(classOf[Dhcp], fixedIp.getSubnetId)

        val address = fixedIp.getIpAddress.asIPAddress
        val subnet = dhcp.getSubnetAddress.asJava

        if (!subnet.containsAddress(address)) {
            throw new IllegalArgumentException(
                s"Port address $address does not belong to DHCP subnet " +
                s"$subnet")
        }

        val portSubnet = IPSubnet.newBuilder()
            .setVersion(fixedIp.getIpAddress.getVersion)
            .setAddress(address.toString)
            .setPrefixLength(subnet.getPrefixLen)
            .build()

        builder.addPortSubnet(portSubnet)
        if (!builder.hasPortAddress) {
            builder.setPortAddress(fixedIp.getIpAddress)
        }

        val internalSubnet =
            if (fixedIp.getIpAddress.getVersion == IPVersion.V6) {
                Option(addGatewayPortAddress6(tx, builder, nRouter, fixedIp))
            } else None

        PortAddress(portSubnet, fixedIp.getIpAddress, fixedIp.getSubnetId,
                    internalSubnet)
    }

    private def addGatewayPortAddress6(tx: Transaction, builder: Port.Builder,
                                       nRouter: NeutronRouter,
                                       fixedIp: IPAllocation): IPv4Subnet = {

        val router = tx.get(classOf[Router], nRouter.getId)
        val routerPorts = tx.getAll(classOf[Port],
                                    router.getPortIdsList.asScala)

        // Add internal subnet that allows the port to communicate with VPP.
        val internalSubnet = containers.findLocalSubnet(routerPorts)
        val internalAddress = containers.routerPortAddress(internalSubnet)
        val subnet = new IPv4Subnet(internalAddress, internalSubnet.getPrefixLen)

        val tunnelKey =
            TunnelKeys.Fip64Type(sequenceDispenser.next(Fip64TunnelKey).await())

        builder.addPortSubnet(subnet.asProto)
        builder.setTunnelKey(tunnelKey)

        subnet
    }

    private def setupGatewayPort(tx: Transaction, nRouter: NeutronRouter,
                                 router: Router, port: Port,
                                 portAddress: PortAddress): Unit = {
        if (portAddress.subnet.getVersion == IPVersion.V4) {
            setupGatewayPort4(tx, nRouter, router, port, portAddress)
        } else {
            setupGatewayPort6(tx, nRouter, router, port, portAddress)
        }
    }

    private def setupGatewayPort4(tx: Transaction, nRouter: NeutronRouter,
                                  router: Router, port: Port,
                                  portAddress: PortAddress): Unit = {
        val dhcp = tx.get(classOf[Dhcp], portAddress.subnetId)
        val subnet = tx.get(classOf[NeutronSubnet], portAddress.subnetId)

        val portRoute = newNextHopPortRoute(
            nextHopPortId = port.getId,
            id = routerInterfaceRouteId(port.getId, portAddress.address),
            srcSubnet = IPSubnetUtil.AnyIPv4Subnet,
            dstSubnet = subnet.getCidr)

        val localRoute = newLocalRoute(port.getId, portAddress.address)

        val defaultRoute = defaultGwRoute(dhcp, port.getId, portAddress.address)

        tx.create(defaultRoute)
        tx.create(localRoute)
        tx.create(portRoute)
        createSnatRules(tx, nRouter, router, portAddress.address, port.getId)
    }

    private def setupGatewayPort6(tx: Transaction, nRouter: NeutronRouter,
                                  router: Router, port: Port,
                                  portAddress: PortAddress): Unit = {
        val portRouteId =
            RouteManager.routerInterfaceRouteId(port.getId, portAddress.address)

        // Determine the gateway port address and subnet.
        val internalSubnet = portAddress.internalSubnet.get
        val vppAddress = containers.containerPortAddress(internalSubnet)

        // Create the NAT64 rule containing the port IPv6 address and the
        // NAT64 pool.
        val natPoolAddress = IPAddress.newBuilder()
            .setAddress(Nat64Pool.getAddress)
            .setVersion(IPVersion.V4)
        val nat64Rule = Rule.newBuilder()
            .setId(nat64RuleId(port.getId))
            .setFipPortId(port.getId)
            .setType(Rule.Type.NAT64_RULE)
            .setNat64RuleData(Rule.Nat64RuleData.newBuilder()
                                  .setPortAddress(portAddress.subnet)
                                  .setNatPool(NatTarget.newBuilder()
                                                  .setNwStart(natPoolAddress)
                                                  .setNwEnd(natPoolAddress)
                                                  .setTpStart(0)
                                                  .setTpEnd(0)))
            .build()
        tx.create(nat64Rule)

        // Create the route to the gateway port.
        val localRoute = newLocalRoute(port.getId,
                                       internalSubnet.getAddress.asProto)
        val portRoute = newNextHopPortRoute(nextHopPortId = port.getId,
                                            id = portRouteId,
                                            srcSubnet = AnyIPv4Subnet,
                                            dstSubnet = Nat64Pool,
                                            nextHopGwIpAddr = vppAddress.asProto)

        tx.create(portRoute)
        tx.create(localRoute)
    }

    private def updateGatewayPort(tx: Transaction, nRouter: NeutronRouter,
                                  router: Router): Unit = {
        // It is assumed that the gateway port may not be removed while there
        // still is a VIP associated with it. Therefore we don't need to worry
        // about cleaning up the ARP entry for the VIP associated with this
        // Router via a corresponding Load Balancer.

        val oldRouter = tx.get(classOf[NeutronRouter], nRouter.getId)
        if (!oldRouter.hasGwPortId && nRouter.hasGwPortId) {
            createGatewayPort(tx, nRouter, router)
        } else if (oldRouter.hasGwPortId && !nRouter.hasGwPortId) {
            deleteGatewayPort(tx, oldRouter)
        } else if (nRouter.hasGwPortId &&
                   isSnatEnabled(oldRouter) != isSnatEnabled(nRouter)) {
            val nPort = tx.get(classOf[NeutronPort], nRouter.getGwPortId)
            val portAddress = nPort.getFixedIps(0).getIpAddress
            if (portAddress.getVersion == IPVersion.V4) {
                updateGatewayPort4(tx, nRouter, router, nPort, portAddress)
            }
        }
    }

    private def updateGatewayPort4(tx: Transaction, nRouter: NeutronRouter,
                                   router: Router, nPort: NeutronPort,
                                   portAddress: IPAddress): Unit = {
        // If the gateway port isn't linked to the router yet, do that.
        if (isSnatEnabled(nRouter)) {
            createSnatRules(tx, nRouter, router, portAddress,
                            tenantGwPortId(nPort.getId))
        } else {
            deleteSnatRules(tx, nRouter.getId)
        }
    }

    private def deleteGatewayPort(tx: Transaction, nRouter: NeutronRouter)
    : Unit = {
        val routerPortId = tenantGwPortId(nRouter.getGwPortId)

        if (tx.exists(classOf[Port], routerPortId)) {
            val port = tx.get(classOf[Port], routerPortId)

            for (address <- port.getPortSubnetList.asScala) {
                if (address.getVersion == IPVersion.V4) {
                    deleteGatewayPort4(tx, nRouter)
                } else {
                    deleteGatewayPort6(tx, nRouter, routerPortId)
                }
            }

            // Delete the port: will also delete the routes through the
            // referential integrity.
            tx.delete(classOf[Port], routerPortId, ignoresNeo = true)
        }
    }

    private def deleteGatewayPort4(tx: Transaction, nRouter: NeutronRouter)
    : Unit = {
        // Delete the SNAT rules.
        deleteSnatRules(tx, nRouter.getId)
    }

    private def deleteGatewayPort6(tx: Transaction, nRouter: NeutronRouter,
                                   routerPortId: UUID): Unit = {
        // Delete the NAT64 rule.
        tx.delete(classOf[Rule], nat64RuleId(routerPortId), ignoresNeo = true)
    }

    private def updateExtraRoutes(tx: Transaction, nRouter: NeutronRouter)
    : Unit = {

        val oldRouterFtr = tx.get(classOf[Router], nRouter.getId)
        val oldNRouter = tx.get(classOf[NeutronRouter], nRouter.getId)
        val oldNRouteIds = oldNRouter.getRoutesList.asScala
            .map(r => extraRouteId(nRouter.getId, r))

        // Create a map of routeID and the route itself
        val routes = nRouter.getRoutesList.asScala
            .map(r => (extraRouteId(nRouter.getId, r), r)).toMap

        // Process deletion
        val delRouteIds = oldNRouteIds -- routes.keys
        delRouteIds foreach { routeId =>
            tx.delete(classOf[Route], routeId, ignoresNeo = true)
            tx.delete(classOf[BgpNetwork], bgpNetworkId(routeId),
                      ignoresNeo = true)
        }

        // Determine routes to add
        val newRoutes = routes -- oldNRouteIds

        // Get the ports on this router
        val portIds = oldRouterFtr.getPortIdsList.asScala
        val ports = tx.getAll(classOf[Port], portIds)
        val bgpConfigured = isBgpSpeakerConfigured(tx, nRouter.getId)
        newRoutes foreach { case (rId, r) =>

            if (r.getDestination.getVersion == IPVersion.V6 ||
                r.getNexthop.getVersion == IPVersion.V6) {
                throw new IllegalArgumentException(
                    "IPv6 is not supported in this version of MidoNet.")
            }

            val nextHopPort = ports.find(isValidRouteOnPort(r.getNexthop, _))
                .getOrElse(
                    throw new IllegalArgumentException(
                        "No valid port was found to add route: " + r))

            val newRoute = newNextHopPortRoute(nextHopPort.getId, id = rId,
                                               dstSubnet = r.getDestination,
                                               nextHopGwIpAddr = r.getNexthop)
            if (bgpConfigured) {
                tx.create(makeBgpNetworkFromRoute(nRouter.getId, r))
            }
            tx.create(newRoute)
        }
    }

    /**
      * Creates a default route for gateway port.
      */
    private def defaultGwRoute(dhcp: Dhcp, portId: UUID, address: IPAddress)
    : Route = {
        val nextHopIp =
            if (dhcp.hasDefaultGateway) dhcp.getDefaultGateway else null
        newNextHopPortRoute(portId, id = gatewayRouteId(portId, address),
                            gatewayDhcpId = dhcp.getId,
                            nextHopGwIpAddr = nextHopIp)
    }

    private def createSnatRules(tx: Transaction, nRouter: NeutronRouter,
                                router: Router, portAddress: IPAddress,
                                portId: UUID): Unit = {
        // If one of the rules exists, they should all exist already.
        if (!isSnatEnabled(nRouter) ||
            tx.exists(classOf[Rule], outSnatRuleId(nRouter.getId)))
            return

        val portSubnet = IPSubnetUtil.fromAddress(portAddress)

        def outRuleBuilder(ruleId: UUID) = Rule.newBuilder
            .setId(ruleId)
            .setChainId(router.getOutboundFilterId)
        def outRuleConditionBuilder = Condition.newBuilder
            .addOutPortIds(portId)
            .setNwSrcIp(portSubnet)
            .setNwSrcInv(true)
        def inRuleBuilder(ruleId: UUID) = Rule.newBuilder
            .setId(ruleId)
            .setChainId(router.getInboundFilterId)
        def inRuleConditionBuilder = Condition.newBuilder
            .setNwDstIp(portSubnet)
        def skipSnatGwPortRuleBuilder() = Rule.newBuilder
            .setId(skipSnatGwPortRuleId(router.getId))
            .setType(Rule.Type.LITERAL_RULE)
            .setAction(Action.ACCEPT)
            .setChainId(skipSnatChainId(router.getId))
            .setCondition(anyFragCondition.addInPortIds(portId))
        def dstRewrittenSnatRuleBuilder() =
            outRuleBuilder(dstRewrittenSnatRuleId(router.getId))
            .setCondition(anyFragCondition.setMatchNwDstRewritten(true))
        def applySnat(bldr: Rule.Builder) =
            bldr.setType(Rule.Type.NAT_RULE)
                .setAction(Action.ACCEPT)
                .setNatRuleData(natRuleData(portAddress, dnat = false,
                                            dynamic = true,
                                            config.translators.dynamicNatPortStart,
                                            config.translators.dynamicNatPortEnd))

        List(applySnat(outRuleBuilder(outSnatRuleId(nRouter.getId))
                 .setCondition(outRuleConditionBuilder)),
             applySnat(dstRewrittenSnatRuleBuilder()),
             outRuleBuilder(outDropUnmatchedFragmentsRuleId(nRouter.getId))
                 .setType(Rule.Type.LITERAL_RULE)
                 .setAction(Action.DROP)
                 .setCondition(outRuleConditionBuilder
                                   .setFragmentPolicy(FragmentPolicy.ANY)),
             inRuleBuilder(inReverseSnatRuleId(nRouter.getId))
                 .setType(Rule.Type.NAT_RULE)
                 .setAction(Action.ACCEPT)
                 .setCondition(inRuleConditionBuilder)
                 .setNatRuleData(revNatRuleData(dnat = false)),
             inRuleBuilder(inDropWrongPortTrafficRuleId(nRouter.getId))
                 .setType(Rule.Type.LITERAL_RULE)
                 .setAction(Action.DROP)
                 .setCondition(inRuleConditionBuilder
                                   .setNwProto(ICMP.PROTOCOL_NUMBER)
                                   .setNwProtoInv(true)),
             skipSnatGwPortRuleBuilder()
        ).foreach(bldr => tx.create(bldr.build()))
    }

    private def deleteSnatRules(tx: Transaction, routerId: UUID): Unit = {
        // They should all exist, or none.
        if (tx.exists(classOf[Rule], outSnatRuleId(routerId))) {
            tx.delete(classOf[Rule], inDropWrongPortTrafficRuleId(routerId),
                      ignoresNeo = true)
            tx.delete(classOf[Rule], inReverseSnatRuleId(routerId),
                      ignoresNeo = true)
            tx.delete(classOf[Rule], outDropUnmatchedFragmentsRuleId(routerId),
                      ignoresNeo = true)
            tx.delete(classOf[Rule], outSnatRuleId(routerId), ignoresNeo = true)
            tx.delete(classOf[Rule], dstRewrittenSnatRuleId(routerId),
                      ignoresNeo = true)
            tx.delete(classOf[Rule], skipSnatGwPortRuleId(routerId),
                      ignoresNeo = true)
        }
    }

    private def isSnatEnabled(nRouter: NeutronRouter): Boolean = {
        nRouter.hasExternalGatewayInfo &&
            nRouter.getExternalGatewayInfo.getEnableSnat
    }

}

object RouterTranslator {

    case class PortAddress(subnet: IPSubnet, address: IPAddress, subnetId: UUID,
                           internalSubnet: Option[IPv4Subnet])

    def preRouteChainName(id: UUID) = "OS_PRE_ROUTING_" + id.asJava

    def postRouteChainName(id: UUID) = "OS_POST_ROUTING_" + id.asJava

    def forwardChainName(id: UUID) = "OS_FORWARD_" + id.asJava

    def floatSnatExactChainName(id: UUID) = "OS_FLOAT_SNAT_EXACT_" + id.asJava

    def floatSnatChainName(id: UUID) = "OS_FLOAT_SNAT_" + id.asJava

    def skipSnatChainName(id: UUID) = "OS_SKIP_SNAT_" + id.asJava

    def portGroupName(id: UUID) = "OS_PORT_GROUP_" + id.asJava

    /** ID of tenant router port that connects to external network port. */
    def tenantGwPortId(providerGwPortId: UUID) =
        providerGwPortId.xorWith(0xd3a45084502f4366L, 0x9fd7d26cc7566972L)

    // Deterministically generate SNAT rule IDs so we can delete them without
    // fetching and examining each rule to see whether it's an SNAT rule.
    def outSnatRuleId(routerId: UUID): UUID =
        routerId.xorWith(0x68fd6d8bbd3343d8L, 0x9909aa4ad4b691d8L)
    def outDropUnmatchedFragmentsRuleId(routerId: UUID): UUID =
        routerId.xorWith(0xbac97789e63e4663L, 0xa00989d341c8636fL)
    def inReverseSnatRuleId(routerId: UUID): UUID =
        routerId.xorWith(0x928eb605e3e04119L, 0x8c40e4ca90769cf4L)
    def inDropWrongPortTrafficRuleId(routerId: UUID): UUID =
        routerId.xorWith(0xb807509d2fa04b9eL, 0x96b1f45a04e6d128L)
    def dstRewrittenSnatRuleId(routerId: UUID): UUID =
        routerId.xorWith(0xf5d39101f0431a3eL, 0xdd7a9236bf83a3e7L)

    def skipSnatGwPortRuleId(routerId: UUID): UUID =
        routerId.xorWith(0x6f90386f458e12ffL, 0x6ec14164bde7cee6L)
}

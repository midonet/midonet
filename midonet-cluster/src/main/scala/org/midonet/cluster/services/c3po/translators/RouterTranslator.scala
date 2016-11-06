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
import org.midonet.cluster.models.Neutron.{NeutronPort, NeutronRouter, NeutronSubnet}
import org.midonet.cluster.models.Topology.Rule.Action
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.translators.BgpPeerTranslator._
import org.midonet.cluster.util.IPSubnetUtil
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
import org.midonet.packets.{ICMP, MAC}

class RouterTranslator(stateTableStorage: StateTableStorage,
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

            // Neutron gateway port assumed to have one IP, namely the gateway IP.
            val gwIpAddr = nPort.getFixedIps(0).getIpAddress
            createGatewayPort4(tx, nRouter, router, nPort, gwIpAddr)
        }
    }

    private def createGatewayPort4(tx: Transaction, nRouter: NeutronRouter,
                                   router: Router, nPort: NeutronPort,
                                   portAddress: IPAddress): Unit = {
        val subnetId = nPort.getFixedIps(0).getSubnetId
        val dhcp = tx.get(classOf[Dhcp], subnetId)

        val routerPortId = tenantGwPortId(nRouter.getGwPortId)
        val routerPortMac = if (nPort.hasMacAddress) nPort.getMacAddress
                            else MAC.random().toString

        val routerPort = newRouterPortBuilder(routerPortId, nRouter.getId,
                                              adminStateUp = true)
            .setPortSubnet(dhcp.getSubnetAddress)
            .setPortAddress(portAddress)
            .setPortMac(routerPortMac)
            .setPeerId(nPort.getId)
            .build()

        val subnet = tx.get(classOf[NeutronSubnet], subnetId)
        val portRoute = newNextHopPortRoute(
            nextHopPortId = routerPortId,
            id = routerInterfaceRouteId(routerPortId),
            srcSubnet = IPSubnetUtil.univSubnet4,
            dstSubnet = subnet.getCidr)

        val localRoute = newLocalRoute(routerPortId,
                                       routerPort.getPortAddress)

        val defaultRoute = defaultGwRoute(dhcp, routerPortId)

        tx.create(routerPort)
        tx.create(defaultRoute)
        tx.create(localRoute)
        tx.create(portRoute)
        createSnatRules(tx, nRouter, router, portAddress, routerPortId)
    }

    private def updateGatewayPort(tx: Transaction, nRouter: NeutronRouter,
                                  router: Router): Unit = {
        // It is assumed that the gateway port may not be removed while there
        // still is a VIP associated with it. Therefore we don't need to worry
        // about cleaning up the ARP entry for the VIP associated with this
        // Router via a corresponding Load Balancer.

        val oldRouter = tx.get(classOf[NeutronRouter], nRouter.getId)
        if (!oldRouter.hasGwPortId && nRouter.hasGwPortId) {
            val nPort = tx.get(classOf[NeutronPort], nRouter.getGwPortId)
            val portAddress = nPort.getFixedIps(0).getIpAddress
            createGatewayPort4(tx, nRouter, router, nPort, portAddress)
        } else if (oldRouter.hasGwPortId && !nRouter.hasGwPortId) {
            deleteGatewayPort4(tx, nRouter)
        } else if (isSnatEnabled(oldRouter) != isSnatEnabled(nRouter)) {
            val nPort = tx.get(classOf[NeutronPort], nRouter.getGwPortId)
            val portAddress = nPort.getFixedIps(0).getIpAddress
            updateGatewayPort4(tx, nRouter, router, nPort, portAddress)
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

    private def deleteGatewayPort4(tx: Transaction, nRouter: NeutronRouter)
    : Unit = {
        val routerPortId = tenantGwPortId(nRouter.getGwPortId)

        tx.delete(classOf[Port], routerPortId, ignoresNeo = true)
        tx.delete(classOf[Route], gatewayRouteId(routerPortId),
                  ignoresNeo = true)
        tx.delete(classOf[Route], localRouteId(routerPortId),
                  ignoresNeo = true)
        tx.delete(classOf[Route], routerInterfaceRouteId(routerPortId),
                  ignoresNeo = true)

        deleteSnatRules(tx, nRouter.getId)
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
    private def defaultGwRoute(dhcp: Dhcp, portId: UUID): Route = {
        val nextHopIp =
            if (dhcp.hasDefaultGateway) dhcp.getDefaultGateway else null
        newNextHopPortRoute(portId, id = gatewayRouteId(portId),
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

        val portSubnet = IPSubnetUtil.fromAddr(portAddress)

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

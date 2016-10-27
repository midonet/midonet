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
import org.midonet.cluster.models.Commons.{Condition, IPAddress, IPVersion, UUID}
import org.midonet.cluster.models.Neutron.{NeutronPort, NeutronRouter, NeutronSubnet}
import org.midonet.cluster.models.Topology.Rule.Action
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.translators.BgpPeerTranslator._
import org.midonet.cluster.util.IPSubnetUtil
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
import org.midonet.packets.ICMP

class RouterTranslator(stateTableStorage: StateTableStorage,
                       config: ClusterConfig)
    extends Translator[NeutronRouter]
    with ChainManager with PortManager with RouteManager
    with RouterManager with RuleManager {

    import RouterTranslator._
    import org.midonet.cluster.services.c3po.translators.RouteManager._

    override protected def translateCreate(tx: Transaction,
                                           nr: NeutronRouter): Unit = {
        val r = translate(nr)
        val inChain = newChain(r.getInboundFilterId,
                               preRouteChainName(nr.getId))
        val outChain = newChain(r.getOutboundFilterId,
                                postRouteChainName(nr.getId))
        val fwdChain = newChain(r.getForwardChainId,
                                forwardChainName(nr.getId))
        val floatSnatExactChain = newChain(floatSnatExactChainId(nr.getId),
                                           floatSnatExactChainName(nr.getId))
        val floatSnatChain = newChain(floatSnatChainId(nr.getId),
                                      floatSnatChainName(nr.getId))
        val skipSnatChain = newChain(skipSnatChainId(nr.getId),
                                     skipSnatChainName(nr.getId))

        // This is actually only needed for edge routers, but edge routers are
        // only defined by having an interface to an uplink network, so it's
        // simpler just to create a port group for every router than to
        // create or delete the port group when a router becomes or ceases to
        // be an edge router as interfaces are created and deleted.
        val pgId = PortManager.portGroupId(nr.getId)
        val portGroup = PortGroup.newBuilder.setId(pgId)
                                            .setName(portGroupName(pgId))
                                            .setTenantId(nr.getTenantId)
                                            .setStateful(true)
                                            .build()
        val routerInterfacePortGroup = newRouterInterfacePortGroup(
            nr.getId, nr.getTenantId).build()

        List(floatSnatExactChain, floatSnatChain, skipSnatChain,
             inChain, outChain, fwdChain, r, portGroup,
             routerInterfacePortGroup,
             jumpRule(outChain.getId, floatSnatExactChain.getId),
             jumpRule(outChain.getId, floatSnatChain.getId),
             jumpRule(outChain.getId, skipSnatChain.getId)).foreach(tx.create)
        gatewayPortCreates(tx, nr, r)
    }

    override protected def translateDelete(tx: Transaction,
                                           nr: NeutronRouter): Unit = {
        // ServiceContainerGroup may not exist, but delete is idempotent.
        tx.delete(classOf[Chain], inChainId(nr.getId), ignoresNeo = true)
        tx.delete(classOf[Chain], outChainId(nr.getId), ignoresNeo = true)
        tx.delete(classOf[Chain], fwdChainId(nr.getId), ignoresNeo = true)
        tx.delete(classOf[Chain], floatSnatExactChainId(nr.getId),
                  ignoresNeo = true)
        tx.delete(classOf[Chain], floatSnatChainId(nr.getId), ignoresNeo = true)
        tx.delete(classOf[Chain], skipSnatChainId(nr.getId), ignoresNeo = true)
        tx.delete(classOf[PortGroup], PortManager.portGroupId(nr.getId),
                  ignoresNeo = true)
        tx.delete(classOf[PortGroup],
                  PortManager.routerInterfacePortGroupId(nr.getId),
                  ignoresNeo = true)
        tx.delete(classOf[ServiceContainerGroup],
                  quaggaContainerGroupId(nr.getId), ignoresNeo = true)
        tx.delete(classOf[Router], nr.getId, ignoresNeo = true)
    }

    override protected def translateUpdate(tx: Transaction,
                                           nr: NeutronRouter): Unit = {
        checkOldRouterTranslation(tx, nr.getId)
        val router = tx.get(classOf[Router], nr.getId).toBuilder
        router.setAdminStateUp(nr.getAdminStateUp)
        router.setName(nr.getName)
        router.setTenantId(nr.getTenantId)

        tx.update(router.build())
        gatewayPortUpdates(tx, nr, router)
        extraRoutesUpdates(tx, nr)
    }

    private def translate(nr: NeutronRouter): Router = {
        val bldr = Router.newBuilder()
        bldr.setId(nr.getId)
        bldr.setInboundFilterId(inChainId(nr.getId))
        bldr.setOutboundFilterId(outChainId(nr.getId))
        bldr.setForwardChainId(fwdChainId(nr.getId))
        if (nr.hasTenantId) bldr.setTenantId(nr.getTenantId)
        if (nr.hasName) bldr.setName(nr.getName)
        if (nr.hasAdminStateUp) bldr.setAdminStateUp(nr.getAdminStateUp)
        bldr.build()
    }

    private def gatewayPortCreates(tx: Transaction, nr: NeutronRouter,
                                   r: RouterOrBuilder): Unit =
        if (nr.hasGwPortId) {

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
            val PortPair(nGwPort, extNwPort) = getPortPair(tx, nr.getGwPortId)

            // Neutron gateway port assumed to have one IP, namely the gateway IP.
            val gwIpAddr = nGwPort.getFixedIps(0).getIpAddress

            val subnetId = nGwPort.getFixedIps(0).getSubnetId
            val dhcp = tx.get(classOf[Dhcp], subnetId)

            val trPortId = tenantGwPortId(nr.getGwPortId)
            val gwMac = if (nGwPort.hasMacAddress) Some(nGwPort.getMacAddress)
                        else None
            val trPort = newTenantRouterGWPort(trPortId, nr.getId,
                                               dhcp.getSubnetAddress, gwIpAddr,
                                               mac = gwMac)

            val ns = tx.get(classOf[NeutronSubnet], subnetId)
            val rifRoute = newNextHopPortRoute(
                nextHopPortId = trPortId,
                id = routerInterfaceRouteId(trPortId),
                srcSubnet = IPSubnetUtil.univSubnet4,
                dstSubnet = ns.getCidr)

            val defaultRoute = defaultGwRoute(dhcp, trPortId)

            tx.create(trPort)
            linkPorts(tx, trPort, extNwPort)
            tx.create(defaultRoute)
            tx.create(rifRoute)
            snatRuleCreates(tx, nr, r, gwIpAddr, trPortId)
        }

    private def extraRoutesUpdates(tx: Transaction, nr: NeutronRouter): Unit = {

        val oldRouterFtr = tx.get(classOf[Router], nr.getId)
        val oldNRouter = tx.get(classOf[NeutronRouter], nr.getId)
        val oldNRouteIds = oldNRouter.getRoutesList.asScala
            .map(r => extraRouteId(nr.getId, r))

        // Create a map of routeID and the route itself
        val routes = nr.getRoutesList.asScala
            .map(r => (extraRouteId(nr.getId, r), r)).toMap

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
        val bgpConfigured = isBgpSpeakerConfigured(tx, nr.getId)
        newRoutes foreach { case (rId, r) =>

            if (r.getDestination.getVersion == IPVersion.V6 ||
                r.getNexthop.getVersion == IPVersion.V6) {
                throw new IllegalArgumentException(
                    "IPv6 is not supported in this version of Midonet.")
            }

            val nextHopPort = ports.find(isValidRouteOnPort(r.getNexthop, _))
                .getOrElse(
                    throw new IllegalArgumentException(
                        "No valid port was found to add route: " + r))

            val newRt = newNextHopPortRoute(nextHopPort.getId, id = rId,
                                            dstSubnet = r.getDestination,
                                            nextHopGwIpAddr = r.getNexthop)
            if (bgpConfigured) {
                tx.create(makeBgpNetworkFromRoute(nr.getId, r))
            }
            tx.create(newRt)
        }
    }

    private def gatewayPortUpdates(tx: Transaction, nr: NeutronRouter,
                                   r: RouterOrBuilder): Unit = {
        // It is assumed that the gateway port may not be removed while there
        // still is a VIP associated with it. Therefore we don't need to worry
        // about cleaning up the ARP entry for the VIP associated with this
        // Router via a corresponding Load Balancer.
        if (nr.hasGwPortId) {
            val PortPair(nGwPort, extNwPort) = getPortPair(tx, nr.getGwPortId)

            // If the gateway port isn't linked to the router yet, do that.
            if (!extNwPort.hasPeerId) {
                gatewayPortCreates(tx, nr, r)
            } else if (snatEnabled(nr)) {
                val gwIpAddr = nGwPort.getFixedIps(0).getIpAddress
                snatRuleCreates(tx, nr, r, gwIpAddr,
                                tenantGwPortId(extNwPort.getId))
            } else {
                snatDeleteRules(tx, nr.getId)
            }
        }
    }

    /** Create default route for gateway port. */
    private def defaultGwRoute(dhcp: Dhcp, portId: UUID): Route = {
        val nextHopIp =
            if (dhcp.hasDefaultGateway) dhcp.getDefaultGateway else null
        newNextHopPortRoute(portId, id = gatewayRouteId(portId),
                            gatewayDhcpId = dhcp.getId,
                            nextHopGwIpAddr = nextHopIp)
    }

    private def snatRuleCreates(tx: Transaction, nr: NeutronRouter,
                                  r: RouterOrBuilder, gwIpAddr: IPAddress,
                                  tenantGwPortId: UUID): Unit = {
        // If one of the rules exists, they should all exist already.
        if (!snatEnabled(nr) ||
            tx.exists(classOf[Rule], outSnatRuleId(nr.getId)))
            return

        val portSubnet = IPSubnetUtil.fromAddr(gwIpAddr)

        def outRuleBuilder(ruleId: UUID) = Rule.newBuilder
            .setId(ruleId)
            .setChainId(r.getOutboundFilterId)
        def outRuleConditionBuilder = Condition.newBuilder
            .addOutPortIds(tenantGwPortId)
            .setNwSrcIp(portSubnet)
            .setNwSrcInv(true)
        def inRuleBuilder(ruleId: UUID) = Rule.newBuilder
            .setId(ruleId)
            .setChainId(r.getInboundFilterId)
        def inRuleConditionBuilder = Condition.newBuilder
            .setNwDstIp(portSubnet)
        def skipSnatGwPortRuleBuilder() = Rule.newBuilder
            .setId(skipSnatGwPortRuleId(r.getId))
            .setType(Rule.Type.LITERAL_RULE)
            .setAction(Action.ACCEPT)
            .setChainId(skipSnatChainId(r.getId))
            .setCondition(anyFragCondition.addInPortIds(tenantGwPortId))
        def dstRewrittenSnatRuleBuilder() =
            outRuleBuilder(dstRewrittenSnatRuleId(r.getId))
            .setCondition(anyFragCondition.setMatchNwDstRewritten(true))
        def applySnat(bldr: Rule.Builder) =
            bldr.setType(Rule.Type.NAT_RULE)
                .setAction(Action.ACCEPT)
                .setNatRuleData(natRuleData(gwIpAddr, dnat = false,
                                            dynamic = true,
                                            config.translators.dynamicNatPortStart,
                                            config.translators.dynamicNatPortEnd))

        List(applySnat(outRuleBuilder(outSnatRuleId(nr.getId))
                 .setCondition(outRuleConditionBuilder)),
             applySnat(dstRewrittenSnatRuleBuilder()),
             outRuleBuilder(outDropUnmatchedFragmentsRuleId(nr.getId))
                 .setType(Rule.Type.LITERAL_RULE)
                 .setAction(Action.DROP)
                 .setCondition(outRuleConditionBuilder
                                   .setFragmentPolicy(FragmentPolicy.ANY)),
             inRuleBuilder(inReverseSnatRuleId(nr.getId))
                 .setType(Rule.Type.NAT_RULE)
                 .setAction(Action.ACCEPT)
                 .setCondition(inRuleConditionBuilder)
                 .setNatRuleData(revNatRuleData(dnat = false)),
             inRuleBuilder(inDropWrongPortTrafficRuleId(nr.getId))
                 .setType(Rule.Type.LITERAL_RULE)
                 .setAction(Action.DROP)
                 .setCondition(inRuleConditionBuilder
                                   .setNwProto(ICMP.PROTOCOL_NUMBER)
                                   .setNwProtoInv(true)),
             skipSnatGwPortRuleBuilder()
        ).foreach(bldr => tx.create(bldr.build()))
    }

    private def snatDeleteRules(tx: Transaction, routerId: UUID): Unit = {
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

    private def snatEnabled(nr: NeutronRouter): Boolean = {
        nr.hasExternalGatewayInfo && nr.getExternalGatewayInfo.getEnableSnat
    }

    /**
     * Neutron and Midonet port with same ID.
     */
    case class PortPair(neutronPort: NeutronPort, midonetPort: Port) {
        assert(neutronPort.getId == midonetPort.getId)
    }

    /**
     * Gets the Neutron and Midonet ports with the same ID.
     */
    private def getPortPair(tx: Transaction, portId: UUID): PortPair = {
        def neutronPort = tx.get(classOf[NeutronPort], portId)
        def midonetPort = tx.get(classOf[Port], portId)
        PortPair(neutronPort, midonetPort)
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

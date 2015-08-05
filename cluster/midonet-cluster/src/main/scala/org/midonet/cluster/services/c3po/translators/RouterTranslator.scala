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

import org.midonet.cluster.data.storage.{ReadOnlyStorage, UpdateValidator}
import org.midonet.cluster.models.Commons.{IPAddress, UUID}
import org.midonet.cluster.models.Neutron.{NeutronPort, NeutronRouter}
import org.midonet.cluster.models.Topology.Rule.{Action, FragmentPolicy}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.midonet.{Create, CreateNode, Delete, Update}
import org.midonet.cluster.util.IPSubnetUtil
import org.midonet.cluster.util.UUIDUtil.{asRichProtoUuid, fromProto}
import org.midonet.midolman.state.PathBuilder
import org.midonet.packets.ICMP
import org.midonet.util.concurrent.toFutureOps

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

class RouterTranslator(protected val storage: ReadOnlyStorage,
                       protected val pathBldr: PathBuilder)
    extends NeutronTranslator[NeutronRouter]
    with ChainManager with PortManager with RouteManager with RuleManager
    with BridgeStateTableManager {
    import RouterTranslator._
    import org.midonet.cluster.services.c3po.translators.RouteManager._

    override protected def translateCreate(nr: NeutronRouter): MidoOpList = {
        val r = translate(nr)
        val inChain = newChain(r.getInboundFilterId,
                               preRouteChainName(nr.getId))
        val outChain = newChain(r.getOutboundFilterId,
                                postRouteChainName(nr.getId))

        // This is actually only needed for edge routers, but edge routers are
        // only defined by having an interface to an uplink network, so it's
        // simpler just to create a port group for every router than to
        // create or delete the port group when a router becomes or ceases to
        // be an edge router as interfaces are created and deleted.
        val pgId = PortManager.portGroupId(nr.getId)
        val portGroup = PortGroup.newBuilder.setId(pgId).build()

        val gwPortOps = gatewayPortCreateOps(nr, r)

        val ops = new MidoOpListBuffer
        ops += Create(r)
        ops += Create(inChain)
        ops += Create(outChain)
        ops += Create(portGroup)
        ops ++= gwPortOps
        ops.toList
    }

    override protected def translateDelete(id: UUID): MidoOpList = {
        List(Delete(classOf[Chain], inChainId(id)),
             Delete(classOf[Chain], outChainId(id)),
             Delete(classOf[PortGroup], PortManager.portGroupId(id)),
             Delete(classOf[Router], id))
    }

    override protected def translateUpdate(nr: NeutronRouter): MidoOpList = {
        val r = translate(nr)
        val gwOps = gatewayPortUpdateOps(nr, r)
        val rtOps = extraRoutesUpdateOps(nr, r)
        Update(r, RouterUpdateValidator) :: gwOps ++ rtOps
    }

    private object RouterUpdateValidator extends UpdateValidator[Router] {
        override def validate(oldRouter: Router, newRouter: Router): Router = {
            // These properties are not initialized by translation from
            // Neutron and should not be overwritten.
            newRouter.toBuilder
                     .addAllPortIds(oldRouter.getPortIdsList)
                     .addAllRouteIds(oldRouter.getRouteIdsList)
                     .build()
        }
    }

    private def translate(nr: NeutronRouter): Router = {
        val bldr = Router.newBuilder()
        bldr.setId(nr.getId)
        bldr.setInboundFilterId(inChainId(nr.getId))
        bldr.setOutboundFilterId(outChainId(nr.getId))
        if (nr.hasTenantId) bldr.setTenantId(nr.getTenantId)
        if (nr.hasName) bldr.setName(nr.getName)
        if (nr.hasAdminStateUp) bldr.setAdminStateUp(nr.getAdminStateUp)
        bldr.build()
    }

    private def gatewayPortCreateOps(nr: NeutronRouter,
                                     r: Router): MidoOpList = {
        if (!nr.hasGwPortId) return List()

        /* There's a bit of a quirk in the translation here. We actually create
         * two linked Midonet ports for a single Neutron gateway port, one on
         * an external network, and one on the tenant router. We have to do
         * this because Neutron has no concept of router ports.
         *
         * When creating a router with a gateway port, Neutron first creates the
         * gateway port in a separate transaction. That gets handled in
         * PortTranslator.translateCreate. By the time we get here, a port with
         * an ID equal the NeutronRouter's gw_port_id and the link-local gateway
         * IP address (169.254.55.1) has already been added to the external
         * network (specified by the port's networkId).
         *
         * Now, when translating the Neutron Router creation or update, we need
         * to create a port on the tenant router which has the Neutron gateway
         * port's IP address and link it to the gateway port on the external
         * network. Note that the Neutron gateway port's ID goes to the port on
         * the external network, while its IP address goes to the port on the
         * tenant router.
         */
        val PortPair(nGwPort, extNwPort) = getPortPair(nr.getGwPortId)

        // Neutron gateway port assumed to have one IP, namely the gateway IP.
        val gwIpAddr = nGwPort.getFixedIps(0).getIpAddress

        val subnetId = nGwPort.getFixedIps(0).getSubnetId
        val dhcp = storage.get(classOf[Dhcp], subnetId).await()

        // Create port on tenant router and link to the external network port.
        val trPortId = tenantGwPortId(nr.getGwPortId)
        val gwMac = if (nGwPort.hasMacAddress) Some(nGwPort.getMacAddress)
                    else None
        val trPort = newTenantRouterGWPort(trPortId, nr.getId,
                                           dhcp.getSubnetAddress, gwIpAddr,
                                           mac = gwMac)

        val linkOps = linkPortOps(trPort, extNwPort)

        val netRoute = newNextHopPortRoute(trPortId,
                                           id = networkRouteId(trPortId),
                                           dstSubnet = dhcp.getSubnetAddress)
        val defaultRoute = defaultGwRoute(dhcp, trPortId)

        val snatOps = snatRuleCreateOps(nr, r, gwIpAddr, trPortId)

        val ops = new MidoOpListBuffer
        ops += Create(trPort)
        ops ++= linkOps
        ops += Create(netRoute)
        ops += Create(defaultRoute)
        ops ++= snatOps

        // Add gateway port IP/MAC address to the external network's ARP table.
        gwMac foreach { mac =>
            val arpPath = arpEntryPath(
                    nGwPort.getNetworkId, gwIpAddr.getAddress, mac)
            ops += CreateNode(arpPath, null)
        }

        ops.toList
    }

    private def extraRoutesUpdateOps(nr: NeutronRouter,
                                     mr: Router): MidoOpList = {
        val ops = new MidoOpListBuffer

        val mRouteIds = mr.getRouteIdsList.asScala
        val commonIds = new ListBuffer[UUID]
        nr.getRoutesList.asScala foreach { r =>
            val rId = extraRouteId(nr.getId, r)

            if (!mRouteIds.contains(rId)) {
                val newRt = newNextHopPortRoute(
                    nr.getGwPortId, id = rId,
                    dstSubnet = r.getDestination,
                    nextHopGwIpAddr = r.getNexthop)
                ops += Create(newRt)
            } else {
                commonIds += rId
            }
        }

        val delRoutes = mRouteIds filterNot (commonIds contains)
        delRoutes foreach (ops += Delete(classOf[Route], _))

        ops.toList
    }

    private def gatewayPortUpdateOps(nr: NeutronRouter,
                                     r: Router): MidoOpList = {
        if (!nr.hasGwPortId) return List()
        val PortPair(nGwPort, extNwPort) = getPortPair(nr.getGwPortId)

        // If the gateway port isn't linked to the router yet, do that.
        if (!extNwPort.hasPeerId) return gatewayPortCreateOps(nr, r)

        if (snatEnabled(nr)) {
            val gwIpAddr = nGwPort.getFixedIps(0).getIpAddress
            snatRuleCreateOps(nr, r, gwIpAddr, tenantGwPortId(extNwPort.getId))
        } else {
            snatDeleteRuleOps(nr.getId)
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

    private def snatRuleCreateOps(nr: NeutronRouter, r: Router,
                                  gwIpAddr: IPAddress, tenantGwPortId: UUID)
    : MidoOpList = {
        // If one of the rules exists, they should all exist already.
        if (!snatEnabled(nr) ||
            storage.exists(classOf[Rule], outSnatRuleId(nr.getId)).await())
            return List()

        val portSubnet = IPSubnetUtil.fromAddr(gwIpAddr)

        def outRuleBuilder(ruleId: UUID) = Rule.newBuilder
            .setId(ruleId)
            .setChainId(r.getOutboundFilterId)
            .addOutPortIds(tenantGwPortId)
            .setNwSrcIp(portSubnet)
            .setNwSrcInv(true)
        def inRuleBuilder(ruleId: UUID) = Rule.newBuilder
            .setId(ruleId)
            .setChainId(r.getInboundFilterId)
            .setNwDstIp(portSubnet)

        List(outRuleBuilder(outSnatRuleId(nr.getId))
                 .setType(Rule.Type.NAT_RULE)
                 .setAction(Action.ACCEPT)
                 .setNatRuleData(natRuleData(gwIpAddr, dnat = false)),
             outRuleBuilder(outDropUnmatchedFragmentsRuleId(nr.getId))
                 .setType(Rule.Type.LITERAL_RULE)
                 .setAction(Action.DROP)
                 .setFragmentPolicy(FragmentPolicy.ANY),
             inRuleBuilder(inReverseSnatRuleId(nr.getId))
                 .setType(Rule.Type.NAT_RULE)
                 .setAction(Action.ACCEPT)
                 .addInPortIds(tenantGwPortId)
                 .setNatRuleData(revNatRuleData(dnat = false)),
             inRuleBuilder(inDropWrongPortTrafficRuleId(nr.getId))
                 .setType(Rule.Type.LITERAL_RULE)
                 .setAction(Action.DROP)
                 .setNwProto(ICMP.PROTOCOL_NUMBER).setNwProtoInv(true)
        ).map(bldr => Create(bldr.build()))
    }

    private def snatDeleteRuleOps(routerId: UUID): MidoOpList = {
        // They should all exist, or none.
        if (!storage.exists(classOf[Rule], outSnatRuleId(routerId)).await())
            return List()

        List(Delete(classOf[Rule], inDropWrongPortTrafficRuleId(routerId)),
             Delete(classOf[Rule], inReverseSnatRuleId(routerId)),
             Delete(classOf[Rule], outDropUnmatchedFragmentsRuleId(routerId)),
             Delete(classOf[Rule], outSnatRuleId(routerId)))
    }

    private def snatEnabled(nr: NeutronRouter): Boolean = {
        nr.hasExternalGatewayInfo && nr.getExternalGatewayInfo.getEnableSnat
    }

    /**
     * Neutron and Midonet port with same ID.
     */
    case class PortPair(neutron: NeutronPort, midonet: Port) {
        assert(neutron.getId == midonet.getId)
    }

    /**
     * Gets the Neutron and Midonet ports with the same ID.
     */
    private def getPortPair(portId: UUID): PortPair = {
        def neutronFuture = storage.get(classOf[NeutronPort], portId)
        def midonetFuture = storage.get(classOf[Port], portId)
        PortPair(neutronFuture.await(), midonetFuture.await())
    }
}

object RouterTranslator {
    def preRouteChainName(id: UUID) = "OS_PRE_ROUTING_" + id.asJava

    def postRouteChainName(id: UUID) = "OS_PORT_ROUTING_" + id.asJava

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
}
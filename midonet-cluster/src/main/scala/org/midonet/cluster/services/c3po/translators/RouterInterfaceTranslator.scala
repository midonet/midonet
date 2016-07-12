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

import scala.collection.mutable

import org.midonet.cluster.ClusterConfig
import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.{Condition, IPSubnet, UUID}
import org.midonet.cluster.models.Neutron.NeutronPort.DeviceOwner
import org.midonet.cluster.models.Neutron._
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.C3POStorageManager.{Create, Operation, Update}
import org.midonet.cluster.services.c3po.translators.PortManager.routerInterfacePortPeerId
import org.midonet.cluster.util.IPSubnetUtil._
import org.midonet.cluster.util.SequenceDispenser
import org.midonet.cluster.util.UUIDUtil.{asRichProtoUuid, fromProto}
import org.midonet.util.concurrent.toFutureOps

object RouterInterfaceTranslator {

    /** Deterministically generate 'same subnet' SNAT rule ID from chain ID
      * and port ID.  'Same subnet' SNAT is a rule applied to traffic that
      * ingresses in from and egresses out of the same tenant router port,
      * without ever going to the uplink.
      * */
    def sameSubnetSnatRuleId(chainId: UUID, portId: UUID) =
        chainId.xorWith(portId.getMsb, portId.getLsb)
            .xorWith(0x3bcf2eb64be211e5L, 0x84ae0242ac110003L)
}

class RouterInterfaceTranslator(protected val storage: ReadOnlyStorage,
                                sequenceDispenser: SequenceDispenser,
                                config: ClusterConfig)
    extends Translator[NeutronRouterInterface]
            with ChainManager
            with PortManager
            with RuleManager {

    import BgpPeerTranslator._
    import RouterInterfaceTranslator._

    /* NeutronRouterInterface is a binding information and has no unique ID.
     * We don't persist it in Storage. */
    override protected def retainHighLevelModel(
            op: Operation[NeutronRouterInterface]) = List()

    override protected def translateCreate(ri : NeutronRouterInterface)
    : OperationList = {
        // At this point, we will already have translated the task to create
        // the NeutronPort with id ri.getPortId.
        val nPort = storage.get(classOf[NeutronPort], ri.getPortId).await()

        // A NeutronRouterInterface is a link between a Neutron router and a
        // Neutron network, so we will need to create a Midonet port on the
        // router with ID nPort.getDeviceId. If nPort is on an uplink network,
        // then there is no corresponding Midonet network, and the router port
        // is bound to a host interface.
        val isUplink = isOnUplinkNetwork(nPort)

        val ns = storage.get(classOf[NeutronSubnet], ri.getSubnetId).await()

        val (rtrPortOps, rtrPort) = buildRouterPort(nPort, isUplink, ri, ns)

        // Add a route to the Interface subnet.
        val routerInterfaceRouteId =
            RouteManager.routerInterfaceRouteId(rtrPort.getId)
        val rifRoute = newNextHopPortRoute(nextHopPortId = rtrPort.getId,
                                           id = routerInterfaceRouteId,
                                           srcSubnet = univSubnet4,
                                           dstSubnet = ns.getCidr)
        val localRoute = newLocalRoute(rtrPort.getId, rtrPort.getPortAddress)

        val midoOps = new OperationListBuffer
        midoOps ++= rtrPortOps

        // Convert Neutron/network port to router interface port if it isn't
        // already one.
        // NOTE(yamamoto): This isn't necessary for Neutron 8.0.0 (Mitaka)
        // and later, because the way to update device_owner has been
        // changed.  (If0178887282456842b6078a851a9233cb58a391a)
        if (nPort.getDeviceOwner != DeviceOwner.ROUTER_INTERFACE)
            midoOps ++= convertPortOps(nPort, isUplink, ri.getId)

        midoOps += Create(rtrPort)

        if (isUplink) {
            midoOps ++= bindPortOps(rtrPort,
                                    getHostIdByName(nPort.getHostId),
                                    nPort.getProfile.getInterfaceName)
        } else {
            // Only create the metadata service route if this router interface
            // port has the DHCP's gateway IP.
            if (rtrPort.getPortAddress == ns.getGatewayIp) {
                midoOps ++= createMetadataServiceRoute(
                    rtrPort.getId, ri.getSubnetId, ns.getCidr)
            }

            // Add dynamic SNAT rules and the reverse SNAT on the router chains
            // so that for any traffic that was DNATed back to the same network
            // would still work by forcing it to come back to the router.  One
            // such case is VIP.
            val rtr = storage.get(classOf[Router], rtrPort.getRouterId).await()
            midoOps += Create(sameSubnetSnatRule(rtr.getOutboundFilterId,
                                                 rtrPort))
            midoOps += Create(sameSubnetRevSnatRule(rtr.getInboundFilterId,
                                                    rtrPort))

            // Add a BgpNetwork if the router has a BGP container.
            if (storage.exists(classOf[Port],
                               quaggaPortId(rtr.getId)).await()) {
                midoOps += Create(
                    BgpPeerTranslator.makeBgpNetwork(
                        rtr.getId, ns.getCidr, nPort.getId))
            }
        }

        // Need to do these after the update returned by bindPortOps(), since
        // these creates add route IDs to the port's routeIds list, which would
        // be overwritten by the update.
        midoOps += Create(rifRoute)
        midoOps += Create(localRoute)

        midoOps.toList
    }

    private def sameSubnetSnatRule(chainId: UUID, port: Port): Rule = {
        val cond = Condition.newBuilder()
                .addInPortIds(port.getId)
                .addOutPortIds(port.getId)
                .setNwDstIp(RouteManager.META_DATA_SRVC)
                .setNwDstInv(true)
                .setMatchForwardFlow(true).build()

        val natTarget = natRuleData(port.getPortAddress, dnat = false,
                                    dynamic = true,
                                    config.translators.dynamicNatPortStart,
                                    config.translators.dynamicNatPortEnd)
        newRule(chainId)
                .setId(sameSubnetSnatRuleId(chainId, port.getId))
                .setType(Rule.Type.NAT_RULE)
                .setCondition(cond)
                .setNatRuleData(natTarget)
                .setAction(Rule.Action.ACCEPT).build()
    }

    private def sameSubnetRevSnatRule(chainId: UUID, port: Port): Rule = {
        val cond = Condition.newBuilder()
                .addInPortIds(port.getId)
                .setNwDstIp(fromAddr(port.getPortAddress))
                .setMatchReturnFlow(true).build()
        val natTarget = revNatRuleData(dnat = false)
        newRule(chainId)
            .setId(sameSubnetSnatRuleId(chainId, port.getId))
            .setType(Rule.Type.NAT_RULE)
            .setCondition(cond)
            .setNatRuleData(natTarget)
            .setAction(Rule.Action.ACCEPT).build()
    }

    // Returns operations needed to convert non RIF port to RIF port.
    private def convertPortOps(nPort: NeutronPort,
                               isUplink: Boolean,
                               routerId: UUID): OperationList = {
        assert(nPort.getDeviceOwner != DeviceOwner.ROUTER_INTERFACE)

        val midoOps = new OperationListBuffer
        midoOps += Update(nPort.toBuilder
                              .setDeviceOwner(DeviceOwner.ROUTER_INTERFACE)
                              .setDeviceId(fromProto(routerId).toString)
                              .build())

        // If it's a VIF port, remove chains from the Midonet port. Unless it's
        // on an uplink network, in which case there's no Midonet port.
        if (!isUplink && PortManager.isVifPort(nPort)) {
            midoOps ++= deleteSecurityChainsOps(nPort.getId)
            midoOps ++= removeIpsFromIpAddrGroupsOps(nPort)

            // Delete DHCP hosts.
            val dhcps = mutable.Map[UUID, Dhcp.Builder]()
            updateDhcpEntries(nPort, dhcps, delDhcpHost)
            midoOps ++= dhcps.values.map(bldr => Update(bldr.build()))
        }

        midoOps.toList
    }

    private def buildRouterPort(nPort: NeutronPort, isUplink: Boolean,
                                ri: NeutronRouterInterface,
                                ns: NeutronSubnet): (OperationList, Port) = {
        // Router ID is given as the router interface's id.
        val routerId = ri.getId

        val routerPortId = routerInterfacePortPeerId(nPort.getId)
        val routerPortBldr = newRouterPortBldr(routerPortId, routerId)
        val ops = new OperationListBuffer

        // Set the router port address. The port should have at most one IP
        // address. If it has none, use the subnet's default gateway.
        val gatewayIp = if (nPort.getFixedIpsCount > 0) {
            if (nPort.getFixedIpsCount > 1)
                log.error("More than 1 fixed IP assigned to a Neutron Port " +
                          s"${fromProto(nPort.getId)}")
            nPort.getFixedIps(0).getIpAddress
        } else ns.getGatewayIp
        routerPortBldr.setPortAddress(gatewayIp)
        routerPortBldr.setPortMac(nPort.getMacAddress)

        if (isUplink) {
            // The port will be bound to a host rather than connected to a
            // network port. Add it to the edge router's port group.
            routerPortBldr.addPortGroupIds(PortManager.portGroupId(routerId))
            assignTunnelKey(routerPortBldr, sequenceDispenser)
        } else {
            val (pgOps, pgId) = ensureRouterInterfacePortGroup(routerId)
            ops ++= pgOps
            routerPortBldr.addPortGroupIds(pgId)

            // Connect the router port to the network port, which has the same
            // ID as nPort.
            routerPortBldr.setPeerId(nPort.getId)

            // If this router port is the subnet's gateway, set the dhcp_id
            // field. There's a field binding that will cause Zoom to set the
            // Dhcp's router_if_port_id field to the router port's ID, which
            // allows us to find the Dhcp's gateway port easily when creating
            // a Dhcp port.
            if (ns.getGatewayIp == routerPortBldr.getPortAddress)
                routerPortBldr.setDhcpId(ri.getSubnetId)
        }

        routerPortBldr.setPortSubnet(ns.getCidr)

        (ops.toList, routerPortBldr.build())
    }

    private def createMetadataServiceRoute(routerPortId: UUID,
                                           subnetId: UUID,
                                           subnetAddr: IPSubnet)
    : Option[Operation[Route]] = {
        // If a DHCP port exists, add a Meta Data Service Route. We can tell by
        // looking at the Dhcp's server address. If it has no associated DHCP
        // port, then its serverAdress field will be the same as its gatewayIp.
        // Otherwise it will be the address of the DHCP port.
        val dhcp = storage.get(classOf[Dhcp], subnetId).await()
        if (!dhcp.hasServerAddress ||
            dhcp.getServerAddress == dhcp.getDefaultGateway) None else {
            Some(Create(newMetaDataServiceRoute(
                subnetAddr, routerPortId, dhcp.getServerAddress)))
        }
    }

    override protected def translateDelete(ri: NeutronRouterInterface)
    : OperationList = {
        // The id field of a router interface is the router ID. Since a router
        // can have multiple interfaces, this doesn't uniquely identify it.
        // We need to handle router interface deletion when we delete the peer
        // port on the network, so there's nothing to do here.
        List()
    }

    override protected def translateUpdate(nm: NeutronRouterInterface)
    : OperationList = {
        throw new IllegalArgumentException(
            "NeutronRouterInterface update not supported.")
    }
}

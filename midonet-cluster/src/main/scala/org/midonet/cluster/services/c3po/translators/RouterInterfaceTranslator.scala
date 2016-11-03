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
import org.midonet.cluster.models.Commons.{Condition, IPSubnet, UUID, _}
import org.midonet.cluster.models.Neutron.NeutronPort.DeviceOwner
import org.midonet.cluster.models.Neutron._
import org.midonet.cluster.models.Topology.Rule.NatTarget
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.NeutronTranslatorManager.Operation
import org.midonet.cluster.services.c3po.translators.PortManager.routerInterfacePortPeerId
import org.midonet.cluster.util.IPSubnetUtil
import org.midonet.cluster.util.IPSubnetUtil._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.containers

object RouterInterfaceTranslator {

    val Nat64Pool = IPSubnetUtil.toProto("20.0.0.1/32")

    /**
      * Deterministically generate 'same subnet' SNAT rule ID from chain ID
      * and port ID.  'Same subnet' SNAT is a rule applied to traffic that
      * ingresses in from and egresses out of the same tenant router port,
      * without ever going to the uplink.
      */
    def sameSubnetSnatRuleId(chainId: UUID, portId: UUID) =
        chainId.xorWith(portId.getMsb, portId.getLsb)
            .xorWith(0x3bcf2eb64be211e5L, 0x84ae0242ac110003L)

    /**
      * Deterministically generate the NAT64 rule ID from the router port ID.
      */
    def nat64RuleId(portId: UUID) =
        portId.xorWith(0xc91ba547c2a6019fL, 0x39d255685b595dffL)
}

class RouterInterfaceTranslator(config: ClusterConfig)
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
        val nSubnet = tx.get(classOf[NeutronSubnet], ri.getSubnetId)

        // Router ID is given as the router interface's id.
        val routerId = ri.getId

        val routerPortId = routerInterfacePortPeerId(nPort.getId)
        val routerInterfaceRouteId =
            RouteManager.routerInterfaceRouteId(routerPortId)
        val routerPortBuilder = newRouterPortBuilder(routerPortId, routerId)

        routerPortBuilder.setPortMac(nPort.getMacAddress)

        // Set the router port address. The port should have at most one IP
        // address. If it has none, use the subnet's default gateway.
        val portAddress = if (nPort.getFixedIpsCount > 0) {
            if (nPort.getFixedIpsCount > 1) {
                throw new IllegalArgumentException (
                    s"Neutron router ${routerId.asJava} assigned to port " +
                    s"${nPort.getId.asJava} with more than one fixed IP " +
                    s"address")
            }
            nPort.getFixedIps(0).getIpAddress
        } else nSubnet.getGatewayIp

        val isV6 = portAddress.getVersion == IPVersion.V6

        // Convert Neutron/network port to router interface port if it isn't
        // already one.
        // NOTE(yamamoto): This isn't necessary for Neutron 8.0.0 (Mitaka)
        // and later, because the way to update device_owner has been
        // changed.  (If0178887282456842b6078a851a9233cb58a391a)
        if (nPort.getDeviceOwner != DeviceOwner.ROUTER_INTERFACE)
            convertPortOps(tx, nPort, isUplink, ri.getId)

        if (isUplink) {
            // Uplink ports use the Neutron port address, either V4 or V6.
            routerPortBuilder.setPortAddress(portAddress)
            routerPortBuilder.setPortSubnet(nSubnet.getCidr)

            // The port will be bound to a host rather than connected to a
            // network port. Add it to the edge router's port group.
            routerPortBuilder
                .addPortGroupIds(PortManager.portGroupId(routerId))
        } else if (isV6) {
            val router = tx.get(classOf[Router], routerId)
            val routerPorts = tx.getAll(classOf[Port],
                                        router.getPortIdsList.asScala)
            val localSubnet = containers.findLocalSubnet(routerPorts)
            val localAddress = containers.routerPortAddress(localSubnet)

            routerPortBuilder.setPortAddress(localAddress)
            routerPortBuilder.setPortSubnet(localSubnet)
        } else {
            routerPortBuilder.setPortAddress(portAddress)
            routerPortBuilder.setPortSubnet(nSubnet.getCidr)

            val pgId = ensureRouterInterfacePortGroup(tx, routerId)
            routerPortBuilder.addPortGroupIds(pgId)

            // Connect the router port to the network port, which has the same
            // ID as nPort.
            routerPortBuilder.setPeerId(nPort.getId)

            // If this router port is the subnet's gateway, set the dhcp_id
            // field. There's a field binding that will cause Zoom to set the
            // Dhcp's router_if_port_id field to the router port's ID, which
            // allows us to find the Dhcp's gateway port easily when creating
            // a Dhcp port.
            if (nSubnet.getGatewayIp == routerPortBuilder.getPortAddress)
                routerPortBuilder.setDhcpId(ri.getSubnetId)
        }

        val routerPort = routerPortBuilder.build()
        tx.create(routerPort)

        if (isUplink) {
            bindPort(tx, routerPort, getHostIdByName(tx, nPort.getHostId),
                     nPort.getProfile.getInterfaceName)
        } else if (isV6) {
            // Create the NAT64 rule containing the port IPv6 address and the
            // NAT64 pool.
            val portSubnet = IPSubnet.newBuilder()
                .setAddress(portAddress.getAddress)
                .setPrefixLength(128)
                .setVersion(IPVersion.V6)
            val natPoolAddress = IPAddress.newBuilder()
                .setAddress(Nat64Pool.getAddress)
                .setVersion(IPVersion.V4)
            val nat64Rule = Rule.newBuilder()
                .setId(nat64RuleId(routerPortId))
                .setFipPortId(routerPortId)
                .setType(Rule.Type.NAT64_RULE)
                .setNat64RuleData(Rule.Nat64RuleData.newBuilder()
                                      .setPortAddress(portSubnet)
                                      .setNatPool(NatTarget.newBuilder()
                                          .setNwStart(natPoolAddress)
                                          .setNwEnd(natPoolAddress)
                                          .setTpStart(0)
                                          .setTpEnd(0)))
                .build()
            tx.create(nat64Rule)
        } else {
            // Only create the metadata service route if this router interface
            // port has the DHCP's gateway IP.
            if (routerPort.getPortAddress == nSubnet.getGatewayIp) {
                createMetadataServiceRoute(tx, routerPortId,
                                           ri.getSubnetId, nSubnet.getCidr)
            }

            // Add dynamic SNAT rules and the reverse SNAT on the router chains
            // so that for any traffic that was DNATed back to the same network
            // would still work by forcing it to come back to the router.  One
            // such case is VIP.
            val router = tx.get(classOf[Router], routerId)
            tx.create(sameSubnetSnatRule(router.getOutboundFilterId, routerPort))
            tx.create(sameSubnetRevSnatRule(router.getInboundFilterId, routerPort))

            // Add a BgpNetwork if the router has a BGP container.
            if (tx.exists(classOf[Port], quaggaPortId(router.getId))) {
                tx.create(BgpPeerTranslator.makeBgpNetwork(router.getId,
                                                           nSubnet.getCidr,
                                                           nPort.getId))
            }
        }

        // For IPv4 ports, the port route is the subnet CIDR, for IPv6 ports
        // is the NAT64 pool.
        val portRouteSubnet = if (isV6) Nat64Pool else nSubnet.getCidr

        val localRoute = newLocalRoute(routerPortId, routerPort.getPortAddress)
        val portRoute = newNextHopPortRoute(nextHopPortId = routerPortId,
                                            id = routerInterfaceRouteId,
                                            srcSubnet = univSubnet4,
                                            dstSubnet = portRouteSubnet)

        // Need to do these after the update returned by bindPortOps(), since
        // these creates add route IDs to the port's routeIds list, which would
        // be overwritten by the update.
        tx.create(portRoute)
        tx.create(localRoute)
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
    private def convertPortOps(tx: Transaction,
                               nPort: NeutronPort,
                               isUplink: Boolean,
                               routerId: UUID): Unit = {
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
                                           subnetId: UUID,
                                           subnetAddr: IPSubnet): Unit = {
        // If a DHCP port exists, add a Meta Data Service Route. We can tell by
        // looking at the Dhcp's server address. If it has no associated DHCP
        // port, then its serverAdress field will be the same as its gatewayIp.
        // Otherwise it will be the address of the DHCP port.
        val dhcp = tx.get(classOf[Dhcp], subnetId)

        if (dhcp.hasServerAddress &&
            dhcp.getServerAddress != dhcp.getDefaultGateway) {
            tx.create(newMetaDataServiceRoute(subnetAddr,
                                              routerPortId,
                                              dhcp.getServerAddress))
        }
    }

    override protected def translateDelete(tx: Transaction,
                                           ri: NeutronRouterInterface): Unit = {
        // The id field of a router interface is the router ID. Since a router
        // can have multiple interfaces, this doesn't uniquely identify it.
        // We need to handle router interface deletion when we delete the peer
        // port on the network, so there's nothing to do here.
    }

    override protected def translateUpdate(tx: Transaction,
                                           nm: NeutronRouterInterface): Unit = {
        throw new IllegalArgumentException(
            "NeutronRouterInterface update not supported.")
    }
}

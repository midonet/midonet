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

import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.{Condition, IPSubnet, UUID}
import org.midonet.cluster.models.Neutron.NeutronPort.DeviceOwner
import org.midonet.cluster.models.Neutron.{NeutronPort, NeutronRouterInterface, NeutronSubnet}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.midonet.{Create, Delete, MidoOp, Update}
import org.midonet.cluster.services.c3po.neutron.NeutronOp
import org.midonet.cluster.services.c3po.translators.PortManager.{isDhcpPort, routerInterfacePortPeerId}
import org.midonet.cluster.util.IPSubnetUtil._
import org.midonet.cluster.util.UUIDUtil.asRichProtoUuid
import org.midonet.cluster.util.UUIDUtil.fromProto
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

class RouterInterfaceTranslator(val storage: ReadOnlyStorage)
    extends NeutronTranslator[NeutronRouterInterface]
            with ChainManager
            with PortManager
            with RuleManager {
    import RouterInterfaceTranslator._

    /* NeutronRouterInterface is a binding information and has no unique ID.
     * We don't persist it in Storage. */
    override protected def retainNeutronModel(
            op: NeutronOp[NeutronRouterInterface]) = List()

    override protected def translateCreate(ri : NeutronRouterInterface)
    : MidoOpList = {
        // At this point, we will already have translated the task to create
        // the NeutronPort with id nm.getPortId.
        val nPort = storage.get(classOf[NeutronPort], ri.getPortId).await()

        // A NeutronRouterInterface is a link between a Neutron router and a
        // Neutron network, so we will need to create a Midonet port on the
        // router with ID nPort.getDeviceId. If nPort is on an uplink network,
        // then there is no corresponding Midonet network, and the router port
        // is bound to a host interface.
        val isUplink = isOnUplinkNetwork(nPort)

        val ns = storage.get(classOf[NeutronSubnet], ri.getSubnetId).await()

        val rtrPort = buildRouterPort(nPort, isUplink, ri, ns)

        // Add a route to the Interface subnet.
        val routerInterfaceRouteId =
            RouteManager.routerInterfaceRouteId(rtrPort.getId)
        val rifRoute = newNextHopPortRoute(nextHopPortId = rtrPort.getId,
                                           id = routerInterfaceRouteId,
                                           srcSubnet = univSubnet4,
                                           dstSubnet = ns.getCidr)
        val localRoute = newLocalRoute(rtrPort.getId, rtrPort.getPortAddress)

        val midoOps = new MidoOpListBuffer

        // Convert Neutron/network port to router interface port if it isn't
        // already one.
        if (nPort.getDeviceOwner != DeviceOwner.ROUTER_INTERFACE)
            midoOps ++= convertPortOps(nPort, isUplink, ri.getId)

        midoOps += Create(rtrPort)

        if (isUplink) {
            midoOps ++= bindPortOps(rtrPort,
                                    getHostIdByName(nPort.getHostId),
                                    nPort.getProfile.getInterfaceName)
        } else {
            midoOps ++= createMetadataServiceRoute(
                rtrPort.getId, nPort.getNetworkId, ns.getCidr)
            midoOps ++= updateGatewayRoutesOps(rtrPort.getPortAddress, ns.getId)

            // Add dynamic SNAT rules and the reverse SNAT on the router chains
            // so that for any traffic that was DNATed back to the same network
            // would still work by forcing it to come back to the router.  One
            // such case is VIP.
            val rtr = storage.get(classOf[Router], rtrPort.getRouterId).await()
            midoOps += Create(sameSubnetSnatRule(rtr.getOutboundFilterId,
                                                 rtrPort))
            midoOps += Create(sameSubnetRevSnatRule(rtr.getInboundFilterId,
                                                    rtrPort))
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
                                    dynamic = true)
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
                               routerId: UUID): MidoOpList = {
        assert(nPort.getDeviceOwner != DeviceOwner.ROUTER_INTERFACE)

        val midoOps = new MidoOpListBuffer
        midoOps += Update(nPort.toBuilder
                              .setDeviceOwner(DeviceOwner.ROUTER_INTERFACE)
                              .setDeviceId(fromProto(routerId).toString)
                              .build())

        // If it's a VIF port, remove chains from the Midonet port. Unless it's
        // on an uplink network, in which case there's no Midonet port.
        if (!isUplink && nPort.getDeviceOwner == DeviceOwner.COMPUTE) {
            midoOps += Delete(classOf[Chain], inChainId(nPort.getId))
            midoOps += Delete(classOf[Chain], outChainId(nPort.getId))

            val mPort = storage.get(classOf[Port], nPort.getId).await()
            midoOps += Update(mPort.toBuilder
                                  .clearInboundFilterId()
                                  .clearOutboundFilterId()
                                  .build())

            // Also need to delete DHCP hosts.
            val dhcps = mutable.Map[UUID, Dhcp.Builder]()
            updateDhcpEntries(nPort, dhcps, delDhcpHost)
            midoOps ++= dhcps.values.map(bldr => Update(bldr.build()))
        }

        midoOps.toList
    }

    private def buildRouterPort(nPort: NeutronPort, isUplink: Boolean,
                                ri: NeutronRouterInterface,
                                ns: NeutronSubnet): Port = {
        // Router ID is given as the router interface's id.
        val routerId = ri.getId

        val routerPortId = routerInterfacePortPeerId(nPort.getId)
        val routerPortBldr = newRouterPortBldr(routerPortId, routerId)

        if (isUplink) {
            // The port will be bound to a host rather than connected to a
            // network port. Add it to the edge router's port group.
            routerPortBldr.addPortGroupIds(PortManager.portGroupId(routerId))
        } else {
            // Connect the router port to the network port, which has the same
            // ID as nPort. Also add a reference to the DHCP. Zoom will add a
            // backreference from the DHCP to the edge router port, allowing us
            // to find it easily when creating a tenant router gateway.
            routerPortBldr.setDhcpId(ri.getSubnetId)
                .setPeerId(nPort.getId)
        }

        routerPortBldr.setPortSubnet(ns.getCidr)

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

        routerPortBldr.build()
    }

    private def createMetadataServiceRoute(routerPortId: UUID,
                                           networkId: UUID,
                                           subnetAddr: IPSubnet)
    : Option[MidoOp[Route]] = {
        // If a DHCP port exists, add a Meta Data Service Route. This requires
        // fetching all ports from ZK. We await the futures in parallel to
        // reduce latency, but this may still become a problem when a Network
        // has many ports. Consider giving the Network a specific reference to
        // its DHCP Port.
        val mNetwork = storage.get(classOf[Network], networkId).await()
        val portIds = mNetwork.getPortIdsList.asScala
        val ports = storage.getAll(classOf[NeutronPort], portIds).await()
        val dhcpPortOpt = ports.find(
            port => isDhcpPort(port) && port.getFixedIpsCount > 0)
        dhcpPortOpt.map(p => Create(newMetaDataServiceRoute(
            subnetAddr, routerPortId, p.getFixedIps(0).getIpAddress)))
    }

    override protected def translateDelete(id: UUID): MidoOpList = {
        // The id field of a router interface is the router ID. Since a router
        // can have multiple interfaces, this doesn't uniquely identify it.
        // We need to handle router interface deletion when we delete the peer
        // port on the network, so there's nothing to do here.
        List()
    }

    override protected def translateUpdate(nm: NeutronRouterInterface)
    : MidoOpList = {
        throw new IllegalArgumentException(
            "NeutronRouterInterface update not supported.")
    }
}

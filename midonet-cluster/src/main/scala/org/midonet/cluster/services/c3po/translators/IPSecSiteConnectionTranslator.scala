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

import java.util.{UUID => JUUID}

import scala.collection.JavaConverters._
import scala.collection.immutable.TreeSet

import org.midonet.cluster.data.storage.Transaction
import org.midonet.cluster.models.Commons.{IPAddress, IPSubnet, IPVersion, UUID}
import org.midonet.cluster.models.Neutron.{IPSecSiteConnection, VpnService}
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.c3po.NeutronTranslatorManager.Operation
import org.midonet.cluster.services.c3po.translators.IPSecSiteConnectionTranslator._
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.cluster.util.IPSubnetUtil._
import org.midonet.cluster.util.{IPSubnetUtil, RangeUtil, SequenceDispenser, UUIDUtil}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.containers
import org.midonet.packets.{IPv4Subnet, MAC}

class IPSecSiteConnectionTranslator(sequenceDispenser: SequenceDispenser)
    extends Translator[IPSecSiteConnection] with RouteManager
            with ChainManager with RuleManager with PortManager {

    override protected def translateCreate(tx: Transaction,
                                           cnxn: IPSecSiteConnection): Unit = {
        val vpn = tx.get(classOf[VpnService], cnxn.getVpnserviceId)
        val containerId =
            if (vpn.hasContainerId) {
                vpn.getContainerId
            } else {
                createServiceContainer(tx, vpn).asProto
            }
        tx.create(cnxn)
        createRemoteRoutes(tx, cnxn, containerId).foreach(r => tx.create(r))
    }

    override protected def translateDelete(tx: Transaction,
                                           cnxn: IPSecSiteConnection): Unit = {
        tx.delete(classOf[IPSecSiteConnection], cnxn.getId, ignoresNeo = true)

        val vpnService = tx.get(classOf[VpnService], cnxn.getVpnserviceId)
        val router = tx.get(classOf[Router], vpnService.getRouterId)
        val serviceUnused = tx.getAll(classOf[VpnService],
                                      router.getVpnServiceIdsList)
            .forall(other =>
                other.getContainerId == vpnService.getContainerId
                    && other.getIpsecSiteConnectionIdsCount == 0)

        if (serviceUnused) {
            deleteServiceContainer(tx, router, vpnService)
        }
    }

    override protected def translateUpdate(tx: Transaction,
                                           cnxn: IPSecSiteConnection): Unit = {
        val vpn = tx.get(classOf[VpnService], cnxn.getVpnserviceId)
        val (delRoutes, addRoutes, currentRoutes) = updateRemoteRoutes(tx, cnxn, vpn)

        val newCnxn = cnxn.toBuilder
            .addAllRouteIds(currentRoutes.map(_.asProto).asJava).build

        delRoutes.foreach(r => tx.delete(classOf[Route], r.getId, ignoresNeo = true))
        addRoutes.foreach(r => tx.create(r))
        tx.update(newCnxn)

    }

    override protected def retainHighLevelModel(tx: Transaction,
                                                op: Operation[IPSecSiteConnection])
    : List[Operation[IPSecSiteConnection]] = List()


    private def createServiceContainer(tx: Transaction, vpn: VpnService):
        JUUID = {
        val routerId = vpn.getRouterId
        val router = tx.get(classOf[Router], routerId)
        val containerId = JUUID.randomUUID
        val portSubnet = findPortSubnet(tx, router)
        val routerPort = createRouterPort(tx, router, portSubnet)
        val vpnRoute = newNextHopPortRoute(id = vpnContainerRouteId(containerId.asProto),
                                           nextHopPortId = routerPort.getId,
                                           dstSubnet = portSubnet.asProto)
        val localRoute = newLocalRoute(routerPort.getId,
                                       routerPort.getPortAddress)

        val chainId = if (router.hasLocalRedirectChainId) {
            router.getLocalRedirectChainId
        } else {
            val id = JUUID.randomUUID
            val chain = newChain(id.asProto, "LOCAL_REDIRECT_" + routerId.asJava)
            tx.create(chain)

            val routerWithChain = router.toBuilder
                .setLocalRedirectChainId(id.asProto)
                .build()
            tx.update(routerWithChain)

            id.asProto
        }

        val redirectRules = makeRedirectRules(
            chainId, vpn.getExternalIp, routerPort.getId)

        // TODO: Set port group ID (MI-300).
        val scg = ServiceContainerGroup.newBuilder
            .setId(UUIDUtil.randomUuidProto)
            .setPolicy(ServiceContainerPolicy.LEAST_SCHEDULER)
            .build()

        val sc = ServiceContainer.newBuilder
            .setId(containerId.asProto)
            .setServiceGroupId(scg.getId)
            .setPortId(routerPort.getId)
            .setServiceType(VpnServiceType)
            .setConfigurationId(routerId)
            .build()

        for (redirectRule <- redirectRules) {
            tx.create(redirectRule)
        }

        val allVpnServices = tx.getAll(classOf[VpnService],
                                       router.getVpnServiceIdsList)
        allVpnServices foreach { vpn =>
            val vpnUpdated = vpn.toBuilder
                .setContainerId(sc.getId).build()
            tx.update(vpnUpdated)
        }

        tx.create(routerPort)
        tx.create(vpnRoute)
        tx.create(localRoute)
        tx.create(scg)
        tx.create(sc)
        containerId
    }

    private def findPortSubnet(tx: Transaction, router: Router): IPv4Subnet = {
        val currentPorts = tx.getAll(classOf[Port], router.getPortIdsList)
        containers.findLocalSubnet(currentPorts)
    }

    @throws[NoSuchElementException]
    private def createRouterPort(tx: Transaction, router: Router,
                                 subnet: IPv4Subnet): Port = {

        val routerAddr = containers.routerPortAddress(subnet)
        val routerSubnet = new IPv4Subnet(routerAddr, subnet.getPrefixLen)

        val portId = JUUID.randomUUID
        val interfaceName = s"vpn-${portId.toString.substring(0, 8)}"
        val builder = Port.newBuilder
            .setId(portId.asProto)
            .setRouterId(router.getId)
            .addPortSubnet(routerSubnet.asProto)
            .setPortAddress(routerAddr.asProto)
            .setPortMac(MAC.random().toString)
            .setInterfaceName(interfaceName)
        assignTunnelKey(builder, sequenceDispenser)
        builder.build()
    }

    private def makeRedirectRules(chainId: UUID,
                                  externalIp: IPAddress,
                                  portId: UUID): List[Rule] = {
        val localEndpointIp = IPSubnetUtil.fromAddress(externalIp)

        // Redirect ESP traffic addressed to local endpoint to VPN port.
        val espRuleBldr = redirectRuleBuilder(
            id = Some(UUIDUtil.randomUuidProto),
            chainId = chainId,
            targetPortId = portId)
        espRuleBldr.getConditionBuilder
            .setNwDstIp(localEndpointIp)
            .setNwProto(50) // ESP

        // Redirect UDP traffic addressed to local endpoint on port 500 to VPN
        // port.
        val udpRuleBldr = redirectRuleBuilder(
            id = Some(UUIDUtil.randomUuidProto),
            chainId = chainId,
            targetPortId = portId)
        udpRuleBldr.getConditionBuilder
            .setNwDstIp(localEndpointIp)
            .setNwProto(17) // UDP
            .setTpDst(RangeUtil.toProto(500, 500)) // IKE UDP port.

        val udp4500RuleBldr = redirectRuleBuilder(
            id = Some(UUIDUtil.randomUuidProto),
            chainId = chainId,
            targetPortId = portId)
        udp4500RuleBldr.getConditionBuilder
            .setNwDstIp(localEndpointIp)
            .setNwProto(17) // UDP
            .setTpDst(RangeUtil.toProto(4500, 4500)) // IKE Nat traversal

        List(espRuleBldr.build(), udpRuleBldr.build(), udp4500RuleBldr.build())
    }

    /** Generate options to create routes for Cartesian product of cnxn's
      * local CIDRs and peer CIDRs.
      */
    private def createRemoteRoutes(tx: Transaction,
                                   cnxn: IPSecSiteConnection,
                                   containerId: UUID): Seq[Route] = {

        def findPortSubnet(port: Port): IPv4Subnet = {
            for (subnet <- port.getPortSubnetList.asScala) {
                if (subnet.getVersion == IPVersion.V4) {
                    return IPSubnetUtil.fromV4Proto(subnet)
                }
            }
            null
        }

        val container = tx.get(classOf[ServiceContainer],
                               containerId)
        val routerPort = tx.get(classOf[Port],
                                container.getPortId)
        val routerPortId = routerPort.getId
        val routerPortSubnet = findPortSubnet(routerPort)

        if (routerPortSubnet eq null) {
            return Seq.empty
        }

        val localPeerCidrPairs = for {
            localCidr <- cnxn.getLocalCidrsList.asScala
            peerCidr <- cnxn.getPeerCidrsList.asScala
        } yield (localCidr, peerCidr)


        localPeerCidrPairs.map { case (localCidr, peerCidr) =>
            newNextHopPortRoute(
                id = UUIDUtil.randomUuidProto,
                ipSecSiteConnectionId = cnxn.getId,
                nextHopPortId = routerPortId,
                nextHopGateway =
                    containers.containerPortAddress(routerPortSubnet).asProto,
                srcSubnet = localCidr,
                dstSubnet = peerCidr)
        }.toList
    }

    private def updateRemoteRoutes(tx: Transaction,
                                   cnxn: IPSecSiteConnection,
                                   vpn: VpnService): (List[Route],
                                                      List[Route],
                                                      List[JUUID]) = {

        val oldCnxn = tx.get(classOf[IPSecSiteConnection], cnxn.getId)
        val oldRoutes = TreeSet(tx.getAll(classOf[Route],
                                oldCnxn.getRouteIdsList) : _*) (routeOrdering)
        val newRoutes = TreeSet(createRemoteRoutes(tx, cnxn,
                                                   vpn.getContainerId): _*) (routeOrdering)

        val toDelete = oldRoutes &~ newRoutes
        val toCreate = newRoutes &~ oldRoutes
        // keep the old route ids + the new ones
        val updatedIds = ((oldRoutes & newRoutes) | toCreate).map(_.getId.asJava)
        (toDelete.toList, toCreate.toList, updatedIds.toList)
    }

    private def deleteServiceContainer(tx: Transaction, router: Router,
                                       vpn: VpnService): Unit = {
        if (router.hasLocalRedirectChainId) {
            val chainId = router.getLocalRedirectChainId
            val chain = tx.get(classOf[Chain], chainId)
            val rules = tx.getAll(classOf[Rule], chain.getRuleIdsList())
            val rulesToDelete = filterVpnRedirectRules(vpn.getExternalIp, rules)
                .map(_.getId)
            if (rulesToDelete.size == chain.getRuleIdsCount) {
                tx.update(router.toBuilder.clearLocalRedirectChainId()
                    .build())
                tx.delete(classOf[Chain], chainId, ignoresNeo = true)
            } else { // just delete the rules
                for (ruleId <- rulesToDelete) {
                    tx.delete(classOf[Rule], ruleId, ignoresNeo = true)
                }
            }
        }

        val container = tx.get(classOf[ServiceContainer], vpn.getContainerId)
        tx.delete(classOf[Port], container.getPortId, ignoresNeo = true)
        tx.delete(classOf[ServiceContainerGroup], container.getServiceGroupId,
                  ignoresNeo = true)
        tx.getAll(classOf[VpnService],
                  router.getVpnServiceIdsList) foreach { vpn =>
            val vpnUpdated = vpn.toBuilder
                .clearContainerId().build()
            tx.update(vpnUpdated)
        }
    }
}

object IPSecSiteConnectionTranslator {

    val VpnServiceType = "IPSEC"

    private val routeOrdering = scala.Ordering.fromLessThan[Route](
        (a: Route, b: Route) => {
            a.getSrcSubnet != b.getSrcSubnet ||
            a.getDstSubnet != b.getDstSubnet
        })

    /** ID of route directing traffic addressed to 169.254.x.x/30 to the VPN. */
    def vpnContainerRouteId(containerId: UUID): UUID =
        containerId.xorWith(0x645a41fb3e1641a3L, 0x90d28456127bee31L)

    def isRedirectRule(r: Rule): Boolean = {
        r.getType == Rule.Type.L2TRANSFORM_RULE &&
        r.getAction == Rule.Action.REDIRECT
    }
    def isRedirectForEndpointRule(r: Rule, ip: IPSubnet): Boolean = {
        val isForEndpoint = r.getCondition.hasNwDstIp &&
                            r.getCondition.getNwDstIp.equals(ip)
        isRedirectRule(r) && isForEndpoint
    }

    def isESPRule(r: Rule): Boolean = {
        val condition = r.getCondition
        condition.hasNwProto &&
        condition.getNwProto == 50 &&
        !condition.getNwProtoInv
    }

    def isUDP500Rule(r: Rule): Boolean = {
        val condition = r.getCondition
        condition.hasNwProto &&
        condition.getNwProto == 17 &&
        !condition.getNwProtoInv &&
        condition.getTpDst.getStart == 500 &&
        condition.getTpDst.getEnd == 500 &&
        !condition.getTpDstInv
    }

    def isUDP4500Rule(r: Rule): Boolean = {
        val condition = r.getCondition

        condition.hasNwProto &&
        condition.getNwProto == 17 &&
        !condition.getNwProtoInv &&
        condition.getTpDst.getStart == 4500 &&
        condition.getTpDst.getEnd == 4500 &&
        !condition.getTpDstInv
    }

    def updateVpnRedirectRules(newExternalIp: IPAddress, rules: List[Rule]):
    List[Rule] = {
        val localEndpointIp = IPSubnetUtil.fromAddress(newExternalIp)
        for (rule <- rules) yield {
            val bldr = rule.toBuilder
            bldr.getConditionBuilder.setNwDstIp(localEndpointIp)
            bldr.build()
        }
    }

    def filterVpnRedirectRules(externalIp: IPAddress, rules: Seq[Rule]):
    List[Rule] = {
        val localEndpointIp = IPSubnetUtil.fromAddress(externalIp)

        rules.filter((r:Rule) => {
            isRedirectForEndpointRule(r, localEndpointIp) &&
            (isESPRule(r) || isUDP500Rule(r) || isUDP4500Rule(r))
        })
            .toList
    }
}
